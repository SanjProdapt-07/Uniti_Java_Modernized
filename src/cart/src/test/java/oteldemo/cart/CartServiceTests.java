/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.GrpcChannelFactory;
import oteldemo.CartServiceGrpc;
import oteldemo.Demo.AddItemRequest;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.EmptyCartRequest;
import oteldemo.Demo.GetCartRequest;

/**
 * The tests of the original xunit {@code CartServiceTests} (empty cart for an unknown user, add an
 * existing item, add a new item) plus the behavior of the service around them, through a real
 * in-process gRPC server and an in-memory cart store.
 */
@SpringBootTest(
    properties = {
      "cart.valkey.enabled=false",
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none"
    })
@AutoConfigureTestGrpcTransport
class CartServiceTests {

  private static final String CART_FAILURE_FLAG = "cartFailure";
  private static final String READINESS_FLAG = "failedReadinessProbe";

  @Autowired private GrpcChannelFactory channels;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private InMemoryProvider featureFlags;
  @Autowired private ResourceConfiguration.ResourceHolder resourceHolder;

  @Autowired
  @Qualifier("cartStore")
  private InMemoryCartStore cartStore;

  @Autowired
  @Qualifier("badCartStore")
  private FailingCartStore badCartStore;

  @BeforeEach
  void setUp() {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setFlags(0.0, false);
    cartStore.down = false;
    badCartStore.calls.set(0);
    spans.reset();
  }

  @AfterEach
  void tearDown() {
    setFlags(0.0, false);
    cartStore.down = false;
  }

  @Test
  void getCartWithoutAddItemBeforeReturnsEmptyCart() {
    GetCartRequest request = GetCartRequest.newBuilder().setUserId(newUserId()).build();

    Cart cart = cartClient().getCart(request);

    assertThat(cart).isNotNull();
    assertThat(cart).isEqualTo(Cart.getDefaultInstance());
  }

  @Test
  void addItemOfAnExistingItemUpdatesTheQuantity() {
    String userId = newUserId();
    AddItemRequest request = addItemRequest(userId, "1", 1);

    // First add - nothing should fail
    cartClient().addItem(request);

    // Second add of existing product - quantity should be updated
    cartClient().addItem(request);

    Cart cart = cartClient().getCart(GetCartRequest.newBuilder().setUserId(userId).build());
    assertThat(cart).isNotNull();
    assertThat(cart.getUserId()).isEqualTo(userId);
    assertThat(cart.getItemsList()).hasSize(1);
    assertThat(cart.getItems(0).getQuantity()).isEqualTo(2);

    // Cleanup
    cartClient().emptyCart(EmptyCartRequest.newBuilder().setUserId(userId).build());
  }

  @Test
  void addItemOfANewItemInsertsIt() {
    String userId = newUserId();

    cartClient().addItem(addItemRequest(userId, "1", 1));

    GetCartRequest getCartRequest = GetCartRequest.newBuilder().setUserId(userId).build();
    Cart cart = cartClient().getCart(getCartRequest);
    assertThat(cart).isNotNull();
    assertThat(cart.getUserId()).isEqualTo(userId);
    assertThat(cart.getItemsList()).hasSize(1);

    cartClient().emptyCart(EmptyCartRequest.newBuilder().setUserId(userId).build());
    cart = cartClient().getCart(getCartRequest);
    assertThat(cart.getItemsList()).isEmpty();
  }

  @Test
  void addItemOfADifferentProductAppendsAnItem() {
    String userId = newUserId();

    cartClient().addItem(addItemRequest(userId, "1", 1));
    cartClient().addItem(addItemRequest(userId, "2", 3));

    Cart cart = cartClient().getCart(GetCartRequest.newBuilder().setUserId(userId).build());
    assertThat(cart.getItemsList())
        .extracting(CartItem::getProductId, CartItem::getQuantity)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("1", 1), org.assertj.core.groups.Tuple.tuple("2", 3));
  }

  @Test
  void addItemAndGetCartSetSpanAttributes() {
    String userId = newUserId();
    cartClient().addItem(addItemRequest(userId, "OLJCESPC7Z", 3));
    cartClient().addItem(addItemRequest(userId, "66VCHSJNUP", 2));
    spans.reset();

    cartClient().getCart(GetCartRequest.newBuilder().setUserId(userId).build());

    SpanData getCart = spanNamed("oteldemo.CartService/GetCart");
    assertThat(getCart.getAttributes().get(AttributeKey.stringKey("user.id"))).isEqualTo(userId);
    assertThat(getCart.getAttributes().get(AttributeKey.longKey("demo.cart.items.count")))
        .isEqualTo(5L);
    assertThat(getCart.getEvents()).anyMatch(event -> event.getName().equals("Fetch cart"));

    spans.reset();
    cartClient().addItem(addItemRequest(userId, "OLJCESPC7Z", 4));

    SpanData addItem = spanNamed("oteldemo.CartService/AddItem");
    assertThat(addItem.getAttributes().get(AttributeKey.stringKey("user.id"))).isEqualTo(userId);
    assertThat(addItem.getAttributes().get(AttributeKey.stringKey("demo.product.id")))
        .isEqualTo("OLJCESPC7Z");
    assertThat(addItem.getAttributes().get(AttributeKey.longKey("demo.product.quantity")))
        .isEqualTo(4L);
  }

  @Test
  void emptyCartAddsAnEventToTheSpan() {
    cartClient().emptyCart(EmptyCartRequest.newBuilder().setUserId(newUserId()).build());

    SpanData emptyCart = spanNamed("oteldemo.CartService/EmptyCart");
    assertThat(emptyCart.getEvents()).anyMatch(event -> event.getName().equals("Empty cart"));
    assertThat(emptyCart.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
  }

  @Test
  void storageFailureIsFailedPreconditionAndMarksTheSpanAsError() {
    cartStore.down = true;
    String userId = newUserId();

    assertThatThrownBy(() -> cartClient().addItem(addItemRequest(userId, "1", 1)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).startsWith("Can't access cart storage.");
            });
    assertFailedSpan("oteldemo.CartService/AddItem");

    spans.reset();
    assertThatThrownBy(
            () -> cartClient().getCart(GetCartRequest.newBuilder().setUserId(userId).build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertFailedSpan("oteldemo.CartService/GetCart");

    spans.reset();
    assertThatThrownBy(
            () -> cartClient().emptyCart(EmptyCartRequest.newBuilder().setUserId(userId).build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
    assertFailedSpan("oteldemo.CartService/EmptyCart");
  }

  @Test
  void emptyCartUsesTheBadStoreWhenCartFailureIsAlwaysOn() {
    setFlags(1.0, false);
    String userId = newUserId();
    cartClient().addItem(addItemRequest(userId, "1", 1));

    assertThatThrownBy(
            () -> cartClient().emptyCart(EmptyCartRequest.newBuilder().setUserId(userId).build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));

    assertThat(badCartStore.calls.get()).isEqualTo(1);
    // the real store was not touched
    assertThat(cartStore.getCart(userId).getItemsList()).hasSize(1);
    assertFailedSpan("oteldemo.CartService/EmptyCart");
  }

  @Test
  void emptyCartUsesTheRealStoreWhenCartFailureIsOff() {
    String userId = newUserId();
    cartClient().addItem(addItemRequest(userId, "1", 1));

    cartClient().emptyCart(EmptyCartRequest.newBuilder().setUserId(userId).build());

    assertThat(badCartStore.calls.get()).isZero();
    assertThat(cartStore.getCart(userId).getItemsList()).isEmpty();
  }

  @Test
  void emptyCartFailsAboutHalfTheTimeWhenCartFailureIsFiftyPercent() {
    setFlags(0.5, false);
    int failures = 0;
    int calls = 200;
    for (int i = 0; i < calls; i++) {
      try {
        cartClient().emptyCart(EmptyCartRequest.newBuilder().setUserId(newUserId()).build());
      } catch (StatusRuntimeException e) {
        failures++;
      }
    }

    assertThat(failures).isBetween(50, 150);
    assertThat(badCartStore.calls.get()).isEqualTo(failures);
  }

  @Test
  void healthIsServingForTheOverallAndTheCartServiceChecks() {
    assertThat(health("").getStatus()).isEqualTo(ServingStatus.SERVING);
    assertThat(health("oteldemo.CartService").getStatus()).isEqualTo(ServingStatus.SERVING);
  }

  @Test
  void healthIsNotServingWhileTheFailedReadinessProbeFlagIsOn() {
    setFlags(0.0, true);

    assertThat(health("").getStatus()).isEqualTo(ServingStatus.NOT_SERVING);
    assertThat(health("oteldemo.CartService").getStatus()).isEqualTo(ServingStatus.NOT_SERVING);
  }

  @Test
  void healthOfAnUnknownServiceIsUnknownStatus() {
    assertThatThrownBy(() -> health("nope"))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN));
  }

  @Test
  void healthWatchSendsTheCurrentStatusOnceAndCompletes() {
    Iterator<HealthCheckResponse> responses =
        HealthGrpc.newBlockingStub(channels.createChannel("cart"))
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .watch(HealthCheckRequest.newBuilder().setService("oteldemo.CartService").build());

    assertThat(responses.next().getStatus()).isEqualTo(ServingStatus.SERVING);
    assertThat(responses.hasNext()).isFalse();
  }

  @Test
  void resourceHasServiceNameAndInstanceId() {
    assertThat(resourceHolder.get().getAttribute(AttributeKey.stringKey("service.name")))
        .isEqualTo("cart");
    assertThat(resourceHolder.get().getAttribute(AttributeKey.stringKey("service.instance.id")))
        .isNotBlank();
  }

  @Test
  void grpcServerSpansAreRecordedAndFlagEvaluationsAreOnTheSpan() {
    cartClient().emptyCart(EmptyCartRequest.newBuilder().setUserId(newUserId()).build());

    SpanData emptyCart = spanNamed("oteldemo.CartService/EmptyCart");
    assertThat(emptyCart.getKind()).isEqualTo(SpanKind.SERVER);
    // the TracesHook records the cartFailure evaluation on the current span
    assertThat(emptyCart.getEvents()).anyMatch(event -> event.getName().equals("feature_flag"));
  }

  private CartServiceGrpc.CartServiceBlockingStub cartClient() {
    return CartServiceGrpc.newBlockingStub(channels.createChannel("cart"))
        .withDeadlineAfter(5, TimeUnit.SECONDS);
  }

  private HealthCheckResponse health(String service) {
    return HealthGrpc.newBlockingStub(channels.createChannel("cart"))
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .check(HealthCheckRequest.newBuilder().setService(service).build());
  }

  private static AddItemRequest addItemRequest(String userId, String productId, int quantity) {
    return AddItemRequest.newBuilder()
        .setUserId(userId)
        .setItem(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
        .build();
  }

  private static String newUserId() {
    return UUID.randomUUID().toString();
  }

  private void assertFailedSpan(String name) {
    SpanData span = spanNamed(name);
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
  }

  // The server span ends after the client already has its response, so give it a moment.
  private SpanData spanNamed(String name) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> findSpan(name), Optional::isPresent)
        .orElseThrow();
  }

  private Optional<SpanData> findSpan(String name) {
    return spans.getFinishedSpanItems().stream()
        .filter(span -> span.getName().equals(name) && span.getKind() == SpanKind.SERVER)
        .findFirst();
  }

  private void setFlags(double cartFailure, boolean failedReadinessProbe) {
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put(
        CART_FAILURE_FLAG,
        Flag.<Double>builder()
            .variant("rate", cartFailure)
            .variant("off", 0.0)
            .defaultVariant("rate")
            .build());
    flags.put(
        READINESS_FLAG,
        Flag.<Boolean>builder()
            .variant("on", true)
            .variant("off", false)
            .defaultVariant(failedReadinessProbe ? "on" : "off")
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
    InMemoryMetricReader inMemoryMetricReader() {
      return InMemoryMetricReader.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(
        InMemorySpanExporter spanExporter, InMemoryMetricReader metricReader) {
      return customizer ->
          customizer
              .addTracerProviderCustomizer(
                  (tracerProvider, config) ->
                      tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addMeterProviderCustomizer(
                  (meterProvider, config) -> meterProvider.registerMetricReader(metricReader));
    }

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }

    @Bean
    InMemoryCartStore cartStore() {
      return new InMemoryCartStore();
    }

    @Bean
    FailingCartStore badCartStore() {
      return new FailingCartStore();
    }
  }
}
