/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import dev.openfeature.sdk.Client;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Service;
import oteldemo.CartServiceGrpc.CartServiceBlockingStub;
import oteldemo.CheckoutServiceGrpc;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.Demo.Address;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.ChargeRequest;
import oteldemo.Demo.CreditCardInfo;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.EmptyCartRequest;
import oteldemo.Demo.GetCartRequest;
import oteldemo.Demo.GetProductRequest;
import oteldemo.Demo.Money;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;
import oteldemo.Demo.PlaceOrderRequest;
import oteldemo.Demo.PlaceOrderResponse;
import oteldemo.PaymentServiceGrpc;
import oteldemo.PaymentServiceGrpc.PaymentServiceBlockingStub;
import oteldemo.ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub;

/**
 * {@code oteldemo.CheckoutService/PlaceOrder}. Orchestrates cart, product catalog, currency and
 * payment (gRPC), shipping and email (HTTP) and the order event on Kafka, in the same order and
 * with the same status codes, messages, spans, attributes and logs as the original Go service.
 */
@Service
public class CheckoutService extends CheckoutServiceGrpc.CheckoutServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(CheckoutService.class);

  private static final String EMIT_RAW_PII_FLAG = "emitRawPii";
  private static final String PAYMENT_UNREACHABLE_FLAG = "paymentUnreachable";
  private static final String UNREACHABLE_PAYMENT_ADDRESS = "badAddress:50051";

  private static final AttributeKey<String> TRANSACTION_ID =
      AttributeKey.stringKey("demo.payment.transaction.id");
  private static final AttributeKey<String> TRACKING_ID =
      AttributeKey.stringKey("demo.shipping.tracking.id");

  private final CartServiceBlockingStub cartClient;
  private final ProductCatalogServiceBlockingStub productCatalogClient;
  private final CurrencyServiceBlockingStub currencyClient;
  private final PaymentServiceBlockingStub paymentClient;
  private final GrpcChannelFactory channelFactory;
  private final ShippingClient shippingClient;
  private final EmailClient emailClient;
  private final OrderPublisher orderPublisher;
  private final Client featureFlags;
  private final Tracer tracer;
  private final boolean kafkaEnabled;

  CheckoutService(
      CartServiceBlockingStub cartClient,
      ProductCatalogServiceBlockingStub productCatalogClient,
      CurrencyServiceBlockingStub currencyClient,
      PaymentServiceBlockingStub paymentClient,
      GrpcChannelFactory channelFactory,
      ShippingClient shippingClient,
      EmailClient emailClient,
      OrderPublisher orderPublisher,
      Client featureFlags,
      OpenTelemetry openTelemetry,
      @Value("${checkout.kafka.addr:}") String kafkaAddr) {
    this.cartClient = cartClient;
    this.productCatalogClient = productCatalogClient;
    this.currencyClient = currencyClient;
    this.paymentClient = paymentClient;
    this.channelFactory = channelFactory;
    this.shippingClient = shippingClient;
    this.emailClient = emailClient;
    this.orderPublisher = orderPublisher;
    this.featureFlags = featureFlags;
    this.tracer = openTelemetry.getTracer("checkout");
    // send to kafka only if kafka broker address is set
    this.kafkaEnabled = kafkaAddr != null && !kafkaAddr.isEmpty();
  }

  @Override
  public void placeOrder(
      PlaceOrderRequest request, StreamObserver<PlaceOrderResponse> responseObserver) {
    PlaceOrderResponse response;
    try {
      response = placeOrder(request);
    } catch (io.grpc.StatusRuntimeException e) {
      responseObserver.onError(e);
      return;
    }
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  PlaceOrderResponse placeOrder(PlaceOrderRequest req) {
    Span span = Span.current();
    span.setAttribute("user.id", req.getUserId());
    span.setAttribute("demo.user_context.selected_currency", req.getUserCurrency());

    if (featureFlags.getBooleanValue(EMIT_RAW_PII_FLAG, false)) {
      span.setAttribute("user.email", req.getEmail());
      span.setAttribute("demo.payment.card_number", req.getCreditCard().getCreditCardNumber());
      span.setAttribute("demo.payment.card_cvv", (long) req.getCreditCard().getCreditCardCvv());
    }

    if ("true".equals(Baggage.current().getEntryValue("synthetic_request"))) {
      span.setAttribute("user_agent.synthetic.type", "test");
    }

    logger
        .atInfo()
        .addKeyValue("user_id", req.getUserId())
        .addKeyValue("user_currency", req.getUserCurrency())
        .log("[PlaceOrder]");

    try {
      return doPlaceOrder(req, span);
    } catch (PlaceOrderFailure failure) {
      span.recordException(failure.error);
      throw failure.status.asRuntimeException();
    }
  }

  private PlaceOrderResponse doPlaceOrder(PlaceOrderRequest req, Span span) {
    UUID orderId = UuidV1.next();

    Address address = req.hasAddress() ? req.getAddress() : null;
    OrderPrep prep;
    try {
      prep = prepareOrderItemsAndShippingQuoteFromCart(req.getUserId(), req.getUserCurrency(), address);
    } catch (StepFailure e) {
      throw new PlaceOrderFailure(Status.INTERNAL.withDescription(e.getMessage()), e);
    }
    span.addEvent("prepared");

    Money total = Money.newBuilder().setCurrencyCode(req.getUserCurrency()).setUnits(0).setNanos(0).build();
    total = MoneyOps.sum(total, prep.shippingCostLocalized);
    for (OrderItem item : prep.orderItems) {
      Money multPrice = MoneyOps.multiplySlow(item.getCost(), item.getItem().getQuantity());
      total = MoneyOps.sum(total, multPrice);
    }

    String transactionId;
    try {
      transactionId = chargeCard(total, req.hasCreditCard() ? req.getCreditCard() : null);
    } catch (StepFailure e) {
      throw new PlaceOrderFailure(
          Status.INTERNAL.withDescription("failed to charge card: " + e.getMessage()), e);
    }

    span.addEvent("charged", Attributes.of(TRANSACTION_ID, transactionId));
    logger.atInfo().addKeyValue("transaction_id", transactionId).log("payment went through");

    String shippingTrackingId;
    try {
      shippingTrackingId = shippingClient.shipOrder(address, prep.cartItems);
    } catch (StepFailure e) {
      throw new PlaceOrderFailure(
          Status.UNAVAILABLE.withDescription("shipping error: " + e.getMessage()), e);
    }
    span.addEvent("shipped", Attributes.of(TRACKING_ID, shippingTrackingId));

    emptyUserCart(req.getUserId());

    OrderResult.Builder orderBuilder =
        OrderResult.newBuilder()
            .setOrderId(orderId.toString())
            .setShippingTrackingId(shippingTrackingId)
            .setShippingCost(prep.shippingCostLocalized)
            .addAllItems(prep.orderItems);
    if (address != null) {
      orderBuilder.setShippingAddress(address);
    }
    OrderResult orderResult = orderBuilder.build();

    double shippingCost = JsonPayloads.amount(prep.shippingCostLocalized);
    double totalPrice = JsonPayloads.amount(total);

    span.setAttribute("demo.order.id", orderId.toString());
    span.setAttribute("demo.shipping.amount", shippingCost);
    span.setAttribute("demo.order.amount", totalPrice);
    span.setAttribute("demo.order.items.count", (long) prep.orderItems.size());
    span.setAttribute(TRACKING_ID, shippingTrackingId);
    logger
        .atInfo()
        .addKeyValue("demo.order.id", orderId.toString())
        .addKeyValue("demo.shipping.amount", shippingCost)
        .addKeyValue("demo.order.amount", totalPrice)
        .addKeyValue("demo.order.items.count", prep.orderItems.size())
        .addKeyValue("demo.shipping.tracking.id", shippingTrackingId)
        .log("order placed");

    try {
      emailClient.sendOrderConfirmation(req.getEmail(), orderResult);
      logger.info("order confirmation email sent");
    } catch (StepFailure e) {
      logger.warn("failed to send order confirmation: {}", e.getMessage());
    }

    // send to kafka only if kafka broker address is set
    if (kafkaEnabled) {
      logger.info("sending to postProcessor");
      orderPublisher.publish(orderResult);
    }

    return PlaceOrderResponse.newBuilder().setOrder(orderResult).build();
  }

  private OrderPrep prepareOrderItemsAndShippingQuoteFromCart(
      String userId, String userCurrency, Address address) {
    Span span = tracer.spanBuilder("prepareOrderItemsAndShippingQuoteFromCart").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      List<CartItem> cartItems;
      try {
        cartItems = getUserCart(userId);
      } catch (StepFailure e) {
        throw new StepFailure("cart failure: " + e.getMessage(), e);
      }
      List<OrderItem> orderItems;
      try {
        orderItems = prepOrderItems(cartItems, userCurrency);
      } catch (StepFailure e) {
        throw new StepFailure("failed to prepare order: " + e.getMessage(), e);
      }
      Money shippingUsd;
      try {
        shippingUsd = shippingClient.quoteShipping(address, cartItems);
      } catch (StepFailure e) {
        throw new StepFailure("shipping quote failure: " + e.getMessage(), e);
      }
      Money shippingPrice;
      try {
        shippingPrice = convertCurrency(shippingUsd, userCurrency);
      } catch (StepFailure e) {
        throw new StepFailure("failed to convert shipping cost to currency: " + e.getMessage(), e);
      }

      int totalCart = 0;
      for (CartItem item : cartItems) {
        totalCart += item.getQuantity();
      }
      span.setAttribute("demo.shipping.amount", JsonPayloads.amount(shippingPrice));
      span.setAttribute("demo.cart.items.count", (long) totalCart);
      span.setAttribute("demo.order.items.count", (long) orderItems.size());
      return new OrderPrep(orderItems, cartItems, shippingPrice);
    } finally {
      span.end();
    }
  }

  private List<CartItem> getUserCart(String userId) {
    try {
      return cartClient.getCart(GetCartRequest.newBuilder().setUserId(userId).build()).getItemsList();
    } catch (io.grpc.StatusRuntimeException e) {
      throw new StepFailure("failed to get user cart during checkout: " + GoErrors.describe(e), e);
    }
  }

  /** Failures are ignored, as in the original ({@code _ = cs.emptyUserCart(...)}). */
  private void emptyUserCart(String userId) {
    try {
      cartClient.emptyCart(EmptyCartRequest.newBuilder().setUserId(userId).build());
    } catch (io.grpc.StatusRuntimeException e) {
      // the order is already charged and shipped; the cart failure is deliberately not reported
    }
  }

  private List<OrderItem> prepOrderItems(List<CartItem> items, String userCurrency) {
    OrderItem[] out = new OrderItem[items.size()];
    for (int i = 0; i < items.size(); i++) {
      CartItem item = items.get(i);
      oteldemo.Demo.Product product;
      try {
        product =
            productCatalogClient.getProduct(
                GetProductRequest.newBuilder().setId(item.getProductId()).build());
      } catch (io.grpc.StatusRuntimeException e) {
        throw new StepFailure("failed to get product #" + GoErrors.quote(item.getProductId()), e);
      }
      Money price;
      try {
        price = convertCurrency(product.getPriceUsd(), userCurrency);
      } catch (StepFailure e) {
        throw new StepFailure(
            "failed to convert price of " + GoErrors.quote(item.getProductId()) + " to " + userCurrency,
            e);
      }
      out[i] = OrderItem.newBuilder().setItem(item).setCost(price).build();
    }
    return List.of(out);
  }

  private Money convertCurrency(Money from, String toCurrency) {
    try {
      return currencyClient.convert(
          CurrencyConversionRequest.newBuilder().setFrom(from).setToCode(toCurrency).build());
    } catch (io.grpc.StatusRuntimeException e) {
      throw new StepFailure("failed to convert currency: " + GoErrors.describe(e), e);
    }
  }

  private String chargeCard(Money amount, CreditCardInfo paymentInfo) {
    ChargeRequest.Builder request = ChargeRequest.newBuilder().setAmount(amount);
    if (paymentInfo != null) {
      request.setCreditCard(paymentInfo);
    }
    ManagedChannel unreachableChannel = null;
    try {
      PaymentServiceBlockingStub paymentService = paymentClient;
      if (featureFlags.getBooleanValue(PAYMENT_UNREACHABLE_FLAG, false)) {
        unreachableChannel = channelFactory.createChannel(UNREACHABLE_PAYMENT_ADDRESS);
        paymentService = PaymentServiceGrpc.newBlockingStub(unreachableChannel);
      }
      return paymentService.charge(request.build()).getTransactionId();
    } catch (io.grpc.StatusRuntimeException e) {
      throw new StepFailure("could not charge the card: " + GoErrors.describe(e), e);
    } finally {
      if (unreachableChannel != null) {
        unreachableChannel.shutdownNow();
      }
    }
  }

  private record OrderPrep(
      List<OrderItem> orderItems, List<CartItem> cartItems, Money shippingCostLocalized) {}

  /** The Go {@code err} that ends PlaceOrder, with the gRPC status it is reported as. */
  private static final class PlaceOrderFailure extends RuntimeException {
    private final Status status;
    private final StepFailure error;

    PlaceOrderFailure(Status status, StepFailure error) {
      super(status.getDescription(), error);
      this.status = status;
      this.error = error;
    }
  }
}
