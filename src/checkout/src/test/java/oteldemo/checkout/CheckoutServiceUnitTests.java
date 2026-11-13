/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.grpc.client.GrpcChannelFactory;
import oteldemo.CartServiceGrpc.CartServiceBlockingStub;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.Demo.Address;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.ChargeRequest;
import oteldemo.Demo.ChargeResponse;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.Empty;
import oteldemo.Demo.EmptyCartRequest;
import oteldemo.Demo.GetCartRequest;
import oteldemo.Demo.GetProductRequest;
import oteldemo.Demo.Money;
import oteldemo.Demo.OrderResult;
import oteldemo.Demo.PlaceOrderRequest;
import oteldemo.Demo.PlaceOrderResponse;
import oteldemo.Demo.Product;
import oteldemo.PaymentServiceGrpc.PaymentServiceBlockingStub;
import oteldemo.ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub;

/** PlaceOrder orchestration with every collaborator mocked. */
class CheckoutServiceUnitTests {

  private CartServiceBlockingStub cart;
  private ProductCatalogServiceBlockingStub catalog;
  private CurrencyServiceBlockingStub currency;
  private PaymentServiceBlockingStub payment;
  private ShippingClient shipping;
  private EmailClient email;
  private OrderPublisher publisher;
  private Client flags;
  private GrpcChannelFactory channelFactory;

  private static Money usd(long units, int nanos) {
    return Money.newBuilder().setCurrencyCode("USD").setUnits(units).setNanos(nanos).build();
  }

  @BeforeEach
  void setUp() {
    cart = mock(CartServiceBlockingStub.class);
    catalog = mock(ProductCatalogServiceBlockingStub.class);
    currency = mock(CurrencyServiceBlockingStub.class);
    payment = mock(PaymentServiceBlockingStub.class);
    shipping = mock(ShippingClient.class);
    email = mock(EmailClient.class);
    publisher = mock(OrderPublisher.class);
    flags = mock(Client.class);
    channelFactory = mock(GrpcChannelFactory.class);

    when(cart.getCart(any(GetCartRequest.class)))
        .thenReturn(
            Cart.newBuilder()
                .addItems(CartItem.newBuilder().setProductId("P1").setQuantity(2))
                .build());
    when(catalog.getProduct(any(GetProductRequest.class)))
        .thenReturn(Product.newBuilder().setId("P1").setPriceUsd(usd(10, 500_000_000)).build());
    when(currency.convert(any(CurrencyConversionRequest.class)))
        .thenAnswer(
            invocation -> {
              CurrencyConversionRequest request = invocation.getArgument(0);
              return request.getFrom().toBuilder().setCurrencyCode(request.getToCode()).build();
            });
    when(shipping.quoteShipping(any(), any())).thenReturn(usd(5, 0));
    when(shipping.shipOrder(any(), any())).thenReturn("TRACK-1");
    when(payment.charge(any(ChargeRequest.class)))
        .thenReturn(ChargeResponse.newBuilder().setTransactionId("tx-1").build());
    when(cart.emptyCart(any(EmptyCartRequest.class))).thenReturn(Empty.getDefaultInstance());
  }

  private CheckoutService service(String kafkaAddr) {
    return new CheckoutService(
        cart,
        catalog,
        currency,
        payment,
        channelFactory,
        shipping,
        email,
        publisher,
        flags,
        OpenTelemetry.noop(),
        kafkaAddr);
  }

  private static PlaceOrderRequest request() {
    return PlaceOrderRequest.newBuilder()
        .setUserId("user-1")
        .setUserCurrency("USD")
        .setEmail("a@b.c")
        .setAddress(Address.newBuilder().setCity("Zurich"))
        .build();
  }

  @Test
  void runsTheStepsInTheOriginalOrder() {
    PlaceOrderResponse response = service("kafka:9092").placeOrder(request());

    InOrder order = inOrder(cart, shipping, payment, email, publisher);
    order.verify(cart).getCart(any(GetCartRequest.class));
    order.verify(shipping).quoteShipping(any(), any());
    order.verify(payment).charge(any(ChargeRequest.class));
    order.verify(shipping).shipOrder(any(), any());
    order.verify(cart).emptyCart(any(EmptyCartRequest.class));
    order.verify(email).sendOrderConfirmation(eq("a@b.c"), any(OrderResult.class));
    order.verify(publisher).publish(response.getOrder());
  }

  @Test
  void chargesTheSumOfShippingAndItemsTimesQuantity() {
    service("").placeOrder(request());

    ArgumentCaptor<ChargeRequest> captor = ArgumentCaptor.forClass(ChargeRequest.class);
    verify(payment).charge(captor.capture());
    // 5.00 shipping + 2 x 10.50
    assertThat(captor.getValue().getAmount()).isEqualTo(usd(26, 0));
    assertThat(captor.getValue().hasCreditCard()).isFalse();
  }

  @Test
  void doesNotProduceAnOrderEventWithoutAKafkaAddress() {
    PlaceOrderResponse response = service("").placeOrder(request());

    assertThat(response.getOrder().getShippingTrackingId()).isEqualTo("TRACK-1");
    verifyNoInteractions(publisher);
  }

  @Test
  void aFailingEmailStillProducesTheOrderEvent() {
    doThrow(new StepFailure("email down")).when(email).sendOrderConfirmation(anyString(), any());

    PlaceOrderResponse response = service("kafka:9092").placeOrder(request());

    verify(publisher).publish(response.getOrder());
  }

  @Test
  void aFailingEmptyCartIsIgnored() {
    when(cart.emptyCart(any(EmptyCartRequest.class))).thenThrow(Status.UNAVAILABLE.asRuntimeException());

    PlaceOrderResponse response = service("kafka:9092").placeOrder(request());

    assertThat(response.getOrder().getOrderId()).isNotEmpty();
    verify(email).sendOrderConfirmation(anyString(), any());
  }

  @Test
  void aFailedChargeStopsBeforeShipping() {
    when(payment.charge(any(ChargeRequest.class)))
        .thenThrow(
            Status.INVALID_ARGUMENT.withDescription("Credit card info is invalid").asRuntimeException());

    assertThatThrownBy(() -> service("kafka:9092").placeOrder(request()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo(
                      "failed to charge card: could not charge the card: rpc error: code = InvalidArgument desc = Credit card info is invalid");
            });

    verify(shipping, never()).shipOrder(any(), any());
    verifyNoInteractions(email, publisher);
  }

  @Test
  void aFailedShipmentIsUnavailableAndSkipsTheRest() {
    when(shipping.shipOrder(any(), any())).thenThrow(new StepFailure("boom"));

    assertThatThrownBy(() -> service("kafka:9092").placeOrder(request()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
              assertThat(e.getStatus().getDescription()).isEqualTo("shipping error: boom");
            });

    verify(cart, never()).emptyCart(any(EmptyCartRequest.class));
    verifyNoInteractions(email, publisher);
  }

  @Test
  void mismatchingCurrenciesFromTheCurrencyServiceFailTheOrder() {
    when(currency.convert(any(CurrencyConversionRequest.class)))
        .thenReturn(Money.newBuilder().setCurrencyCode("EUR").setUnits(1).build());

    // like the Go money.Must panic: no status of its own, so gRPC reports UNKNOWN
    assertThatThrownBy(() -> service("kafka:9092").placeOrder(request()))
        .isInstanceOf(MoneyOps.MoneyException.class)
        .hasMessage(MoneyOps.ERR_MISMATCHING_CURRENCY);
    verify(payment, never()).charge(any(ChargeRequest.class));
  }

  @Test
  void orderCarriesTheItemsTheAddressAndTheShippingCost() {
    OrderResult order = service("").placeOrder(request()).getOrder();
    CartItem item = CartItem.newBuilder().setProductId("P1").setQuantity(2).build();

    assertThat(order.getShippingCost()).isEqualTo(usd(5, 0));
    assertThat(order.getShippingAddress().getCity()).isEqualTo("Zurich");
    assertThat(order.getItemsList()).hasSize(1);
    assertThat(order.getItems(0).getCost()).isEqualTo(usd(10, 500_000_000));
    assertThat(order.getItems(0).getItem()).isEqualTo(item);
    verify(shipping).shipOrder(any(), eq(List.of(item)));
  }

  @Test
  void paymentUnreachableFlagChargesThroughAChannelToAnUnreachableService() {
    when(flags.getBooleanValue("paymentUnreachable", false)).thenReturn(true);
    ManagedChannel unreachable = InProcessChannelBuilder.forName("nobody-listens-here").build();
    when(channelFactory.createChannel("badAddress:50051")).thenReturn(unreachable);

    assertThatThrownBy(() -> service("kafka:9092").placeOrder(request()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .startsWith(
                      "failed to charge card: could not charge the card: rpc error: code = Unavailable");
            });

    verify(payment, never()).charge(any(ChargeRequest.class));
    verify(shipping, never()).shipOrder(any(), any());
    assertThat(unreachable.isShutdown()).as("the throw-away channel is closed").isTrue();
  }

  @Test
  void paymentGoesThroughTheConfiguredServiceWithoutTheFlag() {
    service("").placeOrder(request());

    verify(channelFactory, never()).createChannel(anyString());
    verify(payment).charge(any(ChargeRequest.class));
  }
}
