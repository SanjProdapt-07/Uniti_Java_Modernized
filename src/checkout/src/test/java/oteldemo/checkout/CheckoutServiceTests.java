/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.grpc.BindableService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import oteldemo.CartServiceGrpc;
import oteldemo.CheckoutServiceGrpc;
import oteldemo.CurrencyServiceGrpc;
import oteldemo.Demo.Address;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.ChargeRequest;
import oteldemo.Demo.ChargeResponse;
import oteldemo.Demo.CreditCardInfo;
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
import oteldemo.PaymentServiceGrpc;
import oteldemo.ProductCatalogServiceGrpc;
import oteldemo.checkout.TestHttpServer.Response;

/**
 * End-to-end tests of PlaceOrder: the real service over the in-process gRPC transport, fake cart,
 * catalog, currency and payment services, a stub HTTP server for shipping and email, an
 * in-memory feature flag provider and an embedded Kafka broker.
 */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "CHECKOUT_PORT=0",
      "CART_ADDR=cart:1",
      "CURRENCY_ADDR=currency:1",
      "PAYMENT_ADDR=payment:1",
      "PRODUCT_CATALOG_ADDR=product-catalog:1",
      "KAFKA_ADDR=${spring.embedded.kafka.brokers}"
    })
@AutoConfigureTestGrpcTransport
@EmbeddedKafka(partitions = 1, topics = "orders")
class CheckoutServiceTests {

  private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());
  private static final TestHttpServer HTTP = new TestHttpServer(CALLS);
  private static final AtomicInteger CHARGES = new AtomicInteger();
  private static volatile ChargeRequest lastCharge;
  private static volatile Status cartFailure;
  private static volatile Status emptyCartFailure;
  private static volatile Status productFailure;
  private static volatile Status currencyFailure;
  private static volatile Status paymentFailure;

  private static final Address ADDRESS =
      Address.newBuilder()
          .setStreetAddress("1600 Amphitheatre Parkway")
          .setCity("Mountain View")
          .setState("CA")
          .setCountry("United States")
          .setZipCode("94043")
          .build();
  private static final CreditCardInfo CARD =
      CreditCardInfo.newBuilder()
          .setCreditCardNumber("4432-8015-6152-0454")
          .setCreditCardCvv(672)
          .setCreditCardExpirationYear(2039)
          .setCreditCardExpirationMonth(1)
          .build();

  @Autowired private GrpcChannelFactory channels;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private InMemoryLogRecordExporter logs;
  @Autowired private InMemoryProvider featureFlags;
  @Autowired private EmbeddedKafkaBroker kafka;

  @DynamicPropertySource
  static void httpServices(DynamicPropertyRegistry registry) {
    registry.add("SHIPPING_ADDR", HTTP::baseUrl);
    registry.add("EMAIL_ADDR", HTTP::baseUrl);
  }

  @AfterAll
  static void stopHttp() {
    HTTP.close();
  }

  @BeforeEach
  void setUp() {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setFlags(false, 0, false);
    CALLS.clear();
    CHARGES.set(0);
    lastCharge = null;
    cartFailure = null;
    emptyCartFailure = null;
    productFailure = null;
    currencyFailure = null;
    paymentFailure = null;
    HTTP.reset();
    HTTP.on(
        "/get-quote",
        request ->
            new Response(200, "{\"cost_usd\":{\"currency_code\":\"USD\",\"units\":8,\"nanos\":990000000}}"));
    HTTP.on("/ship-order", request -> new Response(200, "{\"tracking_id\":\"TRACK-123\"}"));
    HTTP.on("/send_order_confirmation", request -> new Response(200, ""));
    spans.reset();
    logs.reset();
  }

  @AfterEach
  void tearDown() {
    setFlags(false, 0, false);
  }

  @Test
  void placesTheOrderThroughTheWholeFlowInOrder() {
    PlaceOrderResponse response = placeOrder("USD");

    OrderResult order = response.getOrder();
    assertThat(UUID.fromString(order.getOrderId()).version()).isEqualTo(1);
    assertThat(order.getShippingTrackingId()).isEqualTo("TRACK-123");
    assertThat(order.getShippingCost())
        .isEqualTo(Money.newBuilder().setCurrencyCode("USD").setUnits(8).setNanos(990_000_000).build());
    assertThat(order.getShippingAddress()).isEqualTo(ADDRESS);
    assertThat(order.getItemsList()).hasSize(2);
    assertThat(order.getItems(0).getItem().getProductId()).isEqualTo("OLJCESPC7Z");
    assertThat(order.getItems(0).getItem().getQuantity()).isEqualTo(2);
    assertThat(order.getItems(0).getCost().getUnits()).isEqualTo(19);
    assertThat(order.getItems(1).getItem().getProductId()).isEqualTo("66VCHSJNUP");

    // prepare items, shipping quote, charge, ship, empty cart, confirmation email
    assertThat(CALLS)
        .containsExactly(
            "GetCart",
            "GetProduct OLJCESPC7Z",
            "Convert USD",
            "GetProduct 66VCHSJNUP",
            "Convert USD",
            "HTTP /get-quote",
            "Convert USD",
            "Charge",
            "HTTP /ship-order",
            "EmptyCart",
            "HTTP /send_order_confirmation");

    // 8.99 shipping + 2 x 19.99 + 1 x 45.00
    assertThat(lastCharge.getAmount())
        .isEqualTo(Money.newBuilder().setCurrencyCode("USD").setUnits(93).setNanos(970_000_000).build());
    assertThat(lastCharge.getCreditCard()).isEqualTo(CARD);

    // the order event reaches Kafka as the protobuf OrderResult
    assertThat(consumeOrders(order.getOrderId(), 1)).singleElement().satisfies(record ->
        assertThat(parseOrder(record)).isEqualTo(order));
  }

  @Test
  void sendsTheOrderToTheShippingAndEmailServicesAsJson() {
    PlaceOrderResponse response = placeOrder("USD");

    List<TestHttpServer.Request> requests = HTTP.requests();
    assertThat(requests).extracting(TestHttpServer.Request::path)
        .containsExactly("/get-quote", "/ship-order", "/send_order_confirmation");
    assertThat(requests.get(0).body())
        .isEqualTo(
            "{\"address\":{\"street_address\":\"1600 Amphitheatre Parkway\",\"city\":\"Mountain View\","
                + "\"state\":\"CA\",\"country\":\"United States\",\"zip_code\":\"94043\"},"
                + "\"items\":[{\"product_id\":\"OLJCESPC7Z\",\"quantity\":2},"
                + "{\"product_id\":\"66VCHSJNUP\",\"quantity\":1}]}");
    assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
    assertThat(requests.get(2).body())
        .startsWith("{\"email\":\"someone@example.com\",\"order\":{\"order_id\":\""
            + response.getOrder().getOrderId() + "\",\"shipping_tracking_id\":\"TRACK-123\",");
  }

  @Test
  void recordsSpansEventsAndAttributes() {
    PlaceOrderResponse response = placeOrder("USD");
    String orderId = response.getOrder().getOrderId();

    SpanData server = spanNamed("oteldemo.CheckoutService/PlaceOrder", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("user.id"))).isEqualTo("user-1");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.user_context.selected_currency")))
        .isEqualTo("USD");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.order.id"))).isEqualTo(orderId);
    assertThat(server.getAttributes().get(AttributeKey.doubleKey("demo.shipping.amount"))).isEqualTo(8.99);
    assertThat(server.getAttributes().get(AttributeKey.doubleKey("demo.order.amount"))).isEqualTo(93.97);
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.order.items.count"))).isEqualTo(2L);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.shipping.tracking.id")))
        .isEqualTo("TRACK-123");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("user.email"))).isNull();
    assertThat(server.getAttributes().get(AttributeKey.stringKey("user_agent.synthetic.type"))).isNull();

    List<String> eventNames = server.getEvents().stream().map(EventData::getName).toList();
    assertThat(eventNames.stream().filter(name -> !name.equals("feature_flag") && !name.equals("message")))
        .containsExactly("prepared", "charged", "shipped");
    EventData charged = event(server, "charged");
    assertThat(charged.getAttributes().get(AttributeKey.stringKey("demo.payment.transaction.id")))
        .isEqualTo("tx-123");
    EventData shipped = event(server, "shipped");
    assertThat(shipped.getAttributes().get(AttributeKey.stringKey("demo.shipping.tracking.id")))
        .isEqualTo("TRACK-123");

    SpanData prepare = spanNamed("prepareOrderItemsAndShippingQuoteFromCart", SpanKind.INTERNAL);
    assertThat(prepare.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(prepare.getAttributes().get(AttributeKey.doubleKey("demo.shipping.amount"))).isEqualTo(8.99);
    assertThat(prepare.getAttributes().get(AttributeKey.longKey("demo.cart.items.count"))).isEqualTo(3L);
    assertThat(prepare.getAttributes().get(AttributeKey.longKey("demo.order.items.count"))).isEqualTo(2L);

    SpanData cartCall = spanNamed("oteldemo.CartService/GetCart", SpanKind.CLIENT);
    assertThat(cartCall.getParentSpanId()).isEqualTo(prepare.getSpanId());
    SpanData chargeCall = spanNamed("oteldemo.PaymentService/Charge", SpanKind.CLIENT);
    assertThat(chargeCall.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(chargeCall.getTraceId()).isEqualTo(server.getTraceId());

    SpanData publish = spanNamed("orders publish", SpanKind.PRODUCER);
    assertThat(publish.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(publish.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
        .isEqualTo("orders");
    assertThat(publish.getAttributes().get(AttributeKey.booleanKey("messaging.kafka.producer.success")))
        .isTrue();
  }

  @Test
  void propagatesTheTraceContextToTheHttpServicesAndKafka() {
    PlaceOrderResponse response = placeOrder("USD");

    SpanData server = spanNamed("oteldemo.CheckoutService/PlaceOrder", SpanKind.SERVER);
    for (TestHttpServer.Request request : HTTP.requests()) {
      String traceparent = request.header("traceparent");
      assertThat(traceparent).as("traceparent of %s", request.path()).isNotNull();
      assertThat(traceparent.split("-")[1]).isEqualTo(server.getTraceId());
    }

    SpanData publish = spanNamed("orders publish", SpanKind.PRODUCER);
    ConsumerRecord<String, byte[]> record = consumeOrders(response.getOrder().getOrderId(), 1).get(0);
    String traceparent = new String(record.headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8);
    assertThat(traceparent).startsWith("00-" + publish.getTraceId() + "-" + publish.getSpanId() + "-");
    assertThat(publish.getTraceId()).isEqualTo(server.getTraceId());
  }

  @Test
  void marksSyntheticRequestsFromTheBaggage() {
    Baggage baggage = Baggage.builder().put("synthetic_request", "true").build();
    try (Scope ignored = baggage.makeCurrent()) {
      placeOrder("USD");
    }

    SpanData server = spanNamed("oteldemo.CheckoutService/PlaceOrder", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("user_agent.synthetic.type")))
        .isEqualTo("test");
  }

  @Test
  void emitRawPiiFlagAddsTheRawPersonalDataAttributes() {
    setFlags(true, 0, false);

    placeOrder("USD");

    SpanData server = spanNamed("oteldemo.CheckoutService/PlaceOrder", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("user.email")))
        .isEqualTo("someone@example.com");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.payment.card_number")))
        .isEqualTo("4432-8015-6152-0454");
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.payment.card_cvv"))).isEqualTo(672L);
  }

  @Test
  void convertsPricesAndShippingToTheUserCurrency() {
    PlaceOrderResponse response = placeOrder("EUR");

    OrderResult order = response.getOrder();
    // the fake currency service halves USD amounts
    assertThat(order.getShippingCost())
        .isEqualTo(Money.newBuilder().setCurrencyCode("EUR").setUnits(4).setNanos(495_000_000).build());
    assertThat(order.getItems(0).getCost())
        .isEqualTo(Money.newBuilder().setCurrencyCode("EUR").setUnits(9).setNanos(995_000_000).build());
    assertThat(lastCharge.getAmount().getCurrencyCode()).isEqualTo("EUR");
    // 4.495 + 2 x 9.995 + 22.5 = 46.985
    assertThat(lastCharge.getAmount().getUnits()).isEqualTo(46);
    assertThat(lastCharge.getAmount().getNanos()).isEqualTo(985_000_000);
  }

  @Test
  void failsWithInternalWhenTheCartCannotBeRead() {
    cartFailure = Status.UNAVAILABLE.withDescription("cart down");

    assertThat(failure(() -> placeOrder("USD")))
        .satisfies(
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo(
                      "cart failure: failed to get user cart during checkout: rpc error: code = Unavailable desc = cart down");
            });

    SpanData server = spanNamed("oteldemo.CheckoutService/PlaceOrder", SpanKind.SERVER);
    assertThat(server.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    assertThat(CALLS).containsExactly("GetCart");
    assertThat(CHARGES).hasValue(0);
  }

  @Test
  void failsWithInternalWhenAProductIsUnknown() {
    productFailure = Status.NOT_FOUND.withDescription("no product");

    assertThat(failure(() -> placeOrder("USD")))
        .satisfies(
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo("failed to prepare order: failed to get product #\"OLJCESPC7Z\"");
            });
  }

  @Test
  void failsWithInternalWhenAPriceCannotBeConverted() {
    currencyFailure = Status.INTERNAL.withDescription("no rate");

    assertThat(failure(() -> placeOrder("USD")).getStatus().getDescription())
        .isEqualTo("failed to prepare order: failed to convert price of \"OLJCESPC7Z\" to USD");
  }

  @Test
  void failsWithInternalWhenTheShippingQuoteFails() {
    HTTP.on("/get-quote", request -> new Response(500, ""));

    assertThat(failure(() -> placeOrder("USD")))
        .satisfies(
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo(
                      "shipping quote failure: failed POST to shipping service: expected 200, got 500");
            });
    assertThat(CHARGES).hasValue(0);
  }

  @Test
  void failsWithInternalWhenThePaymentFails() {
    paymentFailure = Status.UNAVAILABLE.withDescription("payment down");

    assertThat(failure(() -> placeOrder("USD")))
        .satisfies(
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo(
                      "failed to charge card: could not charge the card: rpc error: code = Unavailable desc = payment down");
            });

    assertThat(HTTP.requests()).extracting(TestHttpServer.Request::path).containsExactly("/get-quote");
    assertThat(CALLS).doesNotContain("EmptyCart");
    SpanData server = spanNamed("oteldemo.CheckoutService/PlaceOrder", SpanKind.SERVER);
    assertThat(server.getEvents()).anyMatch(event -> event.getName().equals("exception"));
  }

  @Test
  void failsWithUnavailableWhenShippingTheOrderFails() {
    HTTP.on("/ship-order", request -> new Response(500, ""));

    assertThat(failure(() -> placeOrder("USD")))
        .satisfies(
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo(
                      "shipping error: failed POST to shipping service: expected 200, got 500");
            });

    // the card is already charged at this point, the cart is not emptied, no email, no event
    assertThat(CHARGES).hasValue(1);
    assertThat(CALLS).doesNotContain("EmptyCart", "HTTP /send_order_confirmation");
  }

  @Test
  void aFailingEmptyCartDoesNotFailTheOrder() {
    emptyCartFailure = Status.UNAVAILABLE.withDescription("cart down");

    PlaceOrderResponse response = placeOrder("USD");

    assertThat(response.getOrder().getShippingTrackingId()).isEqualTo("TRACK-123");
    assertThat(CALLS).contains("EmptyCart", "HTTP /send_order_confirmation");
  }

  @Test
  void aFailingConfirmationEmailDoesNotFailTheOrder() {
    HTTP.on("/send_order_confirmation", request -> new Response(500, ""));

    PlaceOrderResponse response = placeOrder("USD");

    assertThat(response.getOrder().getShippingTrackingId()).isEqualTo("TRACK-123");
    // the order event is still produced
    assertThat(consumeOrders(response.getOrder().getOrderId(), 1)).hasSize(1);
  }

  @Test
  void aSlowConfirmationEmailIsCutOffAfterOneSecond() {
    HTTP.on(
        "/send_order_confirmation",
        request -> {
          try {
            Thread.sleep(4_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new Response(200, "");
        });

    long start = System.nanoTime();
    PlaceOrderResponse response = placeOrder("USD");

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    assertThat(response.getOrder().getShippingTrackingId()).isEqualTo("TRACK-123");
  }

  @Test
  void kafkaQueueProblemsFlagSendsExtraCopiesOfTheOrder() {
    setFlags(false, 3, false);

    PlaceOrderResponse response = placeOrder("USD");

    List<ConsumerRecord<String, byte[]>> records = consumeOrders(response.getOrder().getOrderId(), 4);
    assertThat(records).hasSize(4);
    assertThat(records).allSatisfy(record -> assertThat(parseOrder(record)).isEqualTo(response.getOrder()));
  }

  @Test
  void logsTheStepsWithStructuredAttributesAndTraceCorrelation() {
    PlaceOrderResponse response = placeOrder("USD");
    String orderId = response.getOrder().getOrderId();

    LogRecordData start = logNamed("[PlaceOrder]");
    assertThat(start.getAttributes().get(AttributeKey.stringKey("user_id"))).isEqualTo("user-1");
    assertThat(start.getAttributes().get(AttributeKey.stringKey("user_currency"))).isEqualTo("USD");

    LogRecordData payment = logNamed("payment went through");
    assertThat(payment.getAttributes().get(AttributeKey.stringKey("transaction_id"))).isEqualTo("tx-123");

    LogRecordData placed = logNamed("order placed");
    assertThat(placed.getAttributes().get(AttributeKey.stringKey("demo.order.id"))).isEqualTo(orderId);
    assertThat(placed.getAttributes().get(AttributeKey.doubleKey("demo.order.amount"))).isEqualTo(93.97);
    assertThat(placed.getAttributes().get(AttributeKey.longKey("demo.order.items.count"))).isEqualTo(2L);
    assertThat(placed.getAttributes().get(AttributeKey.stringKey("demo.shipping.tracking.id")))
        .isEqualTo("TRACK-123");

    SpanData server = spanNamed("oteldemo.CheckoutService/PlaceOrder", SpanKind.SERVER);
    assertThat(placed.getSpanContext().getTraceId()).isEqualTo(server.getTraceId());
    logNamed("order confirmation email sent");
    logNamed("sending to postProcessor");
  }

  private LogRecordData logNamed(String body) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () ->
                logs.getFinishedLogRecordItems().stream()
                    .filter(record -> record.getBodyValue() != null
                        && body.equals(record.getBodyValue().asString()))
                    .findFirst(),
            Optional::isPresent)
        .orElseThrow();
  }

  @Test
  void reportsServingThroughTheStandardHealthService() {
    HealthCheckResponse response =
        HealthGrpc.newBlockingStub(channels.createChannel("checkout"))
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .check(HealthCheckRequest.getDefaultInstance());

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    // health checks are not traced
    assertThat(spans.getFinishedSpanItems())
        .noneMatch(
            span ->
                span.getKind() == SpanKind.SERVER && span.getName().startsWith("grpc.health.v1.Health"));
  }

  // -- helpers --------------------------------------------------------------------------------

  private PlaceOrderResponse placeOrder(String currency) {
    return CheckoutServiceGrpc.newBlockingStub(channels.createChannel("checkout"))
        .withDeadlineAfter(30, TimeUnit.SECONDS)
        .placeOrder(
            PlaceOrderRequest.newBuilder()
                .setUserId("user-1")
                .setUserCurrency(currency)
                .setAddress(ADDRESS)
                .setEmail("someone@example.com")
                .setCreditCard(CARD)
                .build());
  }

  private static StatusRuntimeException failure(Runnable action) {
    try {
      action.run();
    } catch (StatusRuntimeException e) {
      return e;
    }
    throw new AssertionError("expected a StatusRuntimeException");
  }

  private SpanData spanNamed(String name, SpanKind kind) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () ->
                spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals(name) && span.getKind() == kind)
                    .findFirst(),
            Optional::isPresent)
        .orElseThrow();
  }

  private static EventData event(SpanData span, String name) {
    return span.getEvents().stream().filter(event -> event.getName().equals(name)).findFirst().orElseThrow();
  }

  private static OrderResult parseOrder(ConsumerRecord<String, byte[]> record) {
    try {
      return OrderResult.parseFrom(record.value());
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
  }

  /** Reads the topic from the start until {@code expected} records for the order have arrived. */
  private List<ConsumerRecord<String, byte[]>> consumeOrders(String orderId, int expected) {
    Map<String, Object> config = new HashMap<>();
    config.put("bootstrap.servers", kafka.getBrokersAsString());
    config.put("group.id", "checkout-test-" + UUID.randomUUID());
    config.put("auto.offset.reset", "earliest");
    config.put("key.deserializer", StringDeserializer.class);
    config.put("value.deserializer", ByteArrayDeserializer.class);
    List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of("orders"));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (found.size() < expected && System.nanoTime() < deadline) {
        for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
          if (parseOrder(record).getOrderId().equals(orderId)) {
            found.add(record);
          }
        }
      }
    }
    return found;
  }

  private void setFlags(boolean emitRawPii, int kafkaQueueProblems, boolean paymentUnreachable) {
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put(
        "emitRawPii",
        Flag.<Boolean>builder()
            .variant("on", true)
            .variant("off", false)
            .defaultVariant(emitRawPii ? "on" : "off")
            .build());
    flags.put(
        "paymentUnreachable",
        Flag.<Boolean>builder()
            .variant("on", true)
            .variant("off", false)
            .defaultVariant(paymentUnreachable ? "on" : "off")
            .build());
    flags.put(
        "kafkaQueueProblems",
        Flag.<Integer>builder()
            .variant("value", kafkaQueueProblems)
            .defaultVariant("value")
            .build());
    featureFlags.updateFlags(flags);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    InMemoryLogRecordExporter inMemoryLogRecordExporter() {
      return InMemoryLogRecordExporter.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(
        InMemorySpanExporter spanExporter, InMemoryLogRecordExporter logExporter) {
      return customizer ->
          customizer
              .addTracerProviderCustomizer(
                  (tracerProvider, config) ->
                      tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addLoggerProviderCustomizer(
                  (loggerProvider, config) ->
                      loggerProvider.addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter)));
    }

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }

    @Bean
    BindableService fakeCart() {
      return new CartServiceGrpc.CartServiceImplBase() {
        @Override
        public void getCart(GetCartRequest request, StreamObserver<oteldemo.Demo.Cart> observer) {
          CALLS.add("GetCart");
          if (cartFailure != null) {
            observer.onError(cartFailure.asRuntimeException());
            return;
          }
          observer.onNext(
              oteldemo.Demo.Cart.newBuilder()
                  .setUserId(request.getUserId())
                  .addItems(CartItem.newBuilder().setProductId("OLJCESPC7Z").setQuantity(2))
                  .addItems(CartItem.newBuilder().setProductId("66VCHSJNUP").setQuantity(1))
                  .build());
          observer.onCompleted();
        }

        @Override
        public void emptyCart(EmptyCartRequest request, StreamObserver<Empty> observer) {
          CALLS.add("EmptyCart");
          if (emptyCartFailure != null) {
            observer.onError(emptyCartFailure.asRuntimeException());
            return;
          }
          observer.onNext(Empty.getDefaultInstance());
          observer.onCompleted();
        }
      };
    }

    @Bean
    BindableService fakeProductCatalog() {
      return new ProductCatalogServiceGrpc.ProductCatalogServiceImplBase() {
        @Override
        public void getProduct(GetProductRequest request, StreamObserver<Product> observer) {
          CALLS.add("GetProduct " + request.getId());
          if (productFailure != null) {
            observer.onError(productFailure.asRuntimeException());
            return;
          }
          Money price =
              request.getId().equals("OLJCESPC7Z")
                  ? Money.newBuilder().setCurrencyCode("USD").setUnits(19).setNanos(990_000_000).build()
                  : Money.newBuilder().setCurrencyCode("USD").setUnits(45).build();
          observer.onNext(Product.newBuilder().setId(request.getId()).setPriceUsd(price).build());
          observer.onCompleted();
        }
      };
    }

    @Bean
    BindableService fakeCurrency() {
      return new CurrencyServiceGrpc.CurrencyServiceImplBase() {
        @Override
        public void convert(CurrencyConversionRequest request, StreamObserver<Money> observer) {
          CALLS.add("Convert " + request.getToCode());
          if (currencyFailure != null) {
            observer.onError(currencyFailure.asRuntimeException());
            return;
          }
          Money from = request.getFrom();
          Money converted;
          if (request.getToCode().equals("EUR")) {
            // halves the amount (exact in nanos)
            long nanos = (from.getUnits() * 1_000_000_000L + from.getNanos()) / 2;
            converted =
                Money.newBuilder()
                    .setCurrencyCode("EUR")
                    .setUnits(nanos / 1_000_000_000L)
                    .setNanos((int) (nanos % 1_000_000_000L))
                    .build();
          } else {
            converted = from.toBuilder().setCurrencyCode(request.getToCode()).build();
          }
          observer.onNext(converted);
          observer.onCompleted();
        }
      };
    }

    @Bean
    BindableService fakePayment() {
      return new PaymentServiceGrpc.PaymentServiceImplBase() {
        @Override
        public void charge(ChargeRequest request, StreamObserver<ChargeResponse> observer) {
          CALLS.add("Charge");
          if (paymentFailure != null) {
            observer.onError(paymentFailure.asRuntimeException());
            return;
          }
          CHARGES.incrementAndGet();
          lastCharge = request;
          observer.onNext(ChargeResponse.newBuilder().setTransactionId("tx-123").build());
          observer.onCompleted();
        }
      };
    }
  }
}
