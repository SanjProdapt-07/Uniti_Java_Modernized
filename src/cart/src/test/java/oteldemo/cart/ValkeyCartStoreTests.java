/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;

/**
 * {@link ValkeyCartStore} against a mocked connection. No Valkey is involved: what is verified is
 * the commands the store issues, its error contract, its connection handling and its metrics.
 */
class ValkeyCartStoreTests {

  private static final String USER = "user-1";
  private static final Duration TTL = Duration.ofMinutes(60);

  private ValkeyLinkFactory factory;
  private ValkeyLink link;
  private RedisCommands<String, byte[]> redis;
  private Client featureClient;
  private InMemoryMetricReader metricReader;
  private OpenTelemetrySdk openTelemetry;
  private ValkeyCartStore store;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() throws Exception {
    redis = mock(RedisCommands.class);
    link = mock(ValkeyLink.class);
    when(link.isOpen()).thenReturn(true);
    when(link.commands()).thenReturn(redis);
    when(redis.get("cart")).thenReturn("OK".getBytes());
    factory = mock(ValkeyLinkFactory.class);
    when(factory.connect()).thenReturn(link);
    featureClient = mock(Client.class);
    metricReader = InMemoryMetricReader.create();
    openTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .build();
    store = newStore(factory, 3);
  }

  @AfterEach
  void tearDown() {
    openTelemetry.close();
  }

  private ValkeyCartStore newStore(ValkeyLinkFactory linkFactory, int retries) {
    return new ValkeyCartStore(
        linkFactory,
        "valkey:6379,ssl=false,allowAdmin=true,abortConnect=false",
        featureClient,
        openTelemetry,
        retries,
        Duration.ofMillis(1),
        Duration.ofMillis(2));
  }

  // --- connection handling

  @Test
  void initializeConnectsAndRunsTheSmallTest() throws Exception {
    store.initialize();

    verify(factory, times(1)).connect();
    verify(redis).set(eq("cart"), eq("OK".getBytes()));
    verify(redis).get("cart");
    verify(link).setStateListener(any());
  }

  @Test
  void theConnectionIsOpenedOnlyOnce() throws Exception {
    store.initialize();
    store.initialize();
    when(redis.hget(USER, "cart")).thenReturn(null);
    store.getCart(USER);
    store.addItem(USER, "p", 1);

    verify(factory, times(1)).connect();
  }

  @Test
  void connectingIsRetriedUntilItSucceeds() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    ValkeyLinkFactory flaky =
        () -> {
          if (attempts.incrementAndGet() < 3) {
            throw new RedisException("connection refused");
          }
          return link;
        };

    newStore(flaky, ValkeyCartStore.REDIS_RETRY_NUMBER).initialize();

    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  void connectingGivesUpAfterTheConfiguredNumberOfAttempts() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    ValkeyLinkFactory down =
        () -> {
          attempts.incrementAndGet();
          throw new RedisException("connection refused");
        };
    ValkeyCartStore failing = newStore(down, 4);

    assertThatThrownBy(failing::initialize)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Wasn't able to connect to redis");
    assertThat(attempts.get()).isEqualTo(4);
  }

  @Test
  void theRetryNumberOfTheOriginalIsThirty() {
    assertThat(ValkeyCartStore.REDIS_RETRY_NUMBER).isEqualTo(30);
  }

  @Test
  void operationsFailWithFailedPreconditionWhenValkeyCantBeReached() throws Exception {
    ValkeyLinkFactory down =
        () -> {
          throw new RedisException("connection refused");
        };
    ValkeyCartStore failing = newStore(down, 1);

    assertFailedPrecondition(() -> failing.addItem(USER, "p", 1));
    assertFailedPrecondition(() -> failing.getCart(USER));
    assertFailedPrecondition(() -> failing.emptyCart(USER));
  }

  @Test
  void aFailingSmallTestClosesTheConnectionAndIsRetriedByTheNextOperation() throws Exception {
    when(redis.set(anyString(), any())).thenThrow(new RedisException("READONLY")).thenReturn("OK");

    assertFailedPrecondition(() -> store.getCart(USER));
    verify(link).close();

    when(redis.hget(USER, "cart")).thenReturn(null);
    assertThat(store.getCart(USER)).isEqualTo(Cart.getDefaultInstance());
    verify(factory, times(2)).connect();
  }

  @Test
  void aLostConnectionIsReopenedByTheNextOperation() throws Exception {
    store.initialize();
    ArgumentCaptor<ValkeyLink.StateListener> listener =
        ArgumentCaptor.forClass(ValkeyLink.StateListener.class);
    verify(link).setStateListener(listener.capture());

    listener.getValue().onLost();
    when(redis.hget(USER, "cart")).thenReturn(null);
    store.getCart(USER);

    verify(link).close();
    verify(factory, times(2)).connect();
  }

  @Test
  void aRestoredConnectionIsUsedAgainWithoutReconnecting() throws Exception {
    store.initialize();
    ArgumentCaptor<ValkeyLink.StateListener> listener =
        ArgumentCaptor.forClass(ValkeyLink.StateListener.class);
    verify(link).setStateListener(listener.capture());

    listener.getValue().onLost();
    listener.getValue().onRestored();
    listener.getValue().onInternalError(new RedisException("boom"));
    when(redis.hget(USER, "cart")).thenReturn(null);
    store.getCart(USER);

    verify(factory, times(1)).connect();
    verify(link, never()).close();
  }

  @Test
  void closeClosesTheConnection() {
    store.initialize();

    store.close();

    verify(link).close();
    assertThat(store.ping()).isFalse();
  }

  // --- AddItem

  @Test
  void addItemOfANewUserCreatesTheCartAndExpiresItAfterSixtyMinutes() throws Exception {
    when(redis.hget(USER, "cart")).thenReturn(null);

    store.addItem(USER, "OLJCESPC7Z", 2);

    Cart saved = savedCart();
    assertThat(saved.getUserId()).isEqualTo(USER);
    assertThat(saved.getItemsList())
        .containsExactly(CartItem.newBuilder().setProductId("OLJCESPC7Z").setQuantity(2).build());
    verify(redis).expire(USER, TTL);
  }

  @Test
  void addItemOfANewProductAppendsAnItem() throws Exception {
    when(redis.hget(USER, "cart")).thenReturn(cartOf("a", 1).toByteArray());

    store.addItem(USER, "b", 5);

    Cart saved = savedCart();
    assertThat(saved.getUserId()).isEqualTo(USER);
    assertThat(saved.getItemsList())
        .containsExactly(
            CartItem.newBuilder().setProductId("a").setQuantity(1).build(),
            CartItem.newBuilder().setProductId("b").setQuantity(5).build());
    verify(redis).expire(USER, TTL);
  }

  @Test
  void addItemOfAnExistingProductAddsTheQuantities() throws Exception {
    when(redis.hget(USER, "cart")).thenReturn(cartOf("a", 1).toByteArray());

    store.addItem(USER, "a", 4);

    assertThat(savedCart().getItemsList())
        .containsExactly(CartItem.newBuilder().setProductId("a").setQuantity(5).build());
  }

  @Test
  void addItemFailsWhenTheCartHasTheProductTwice() {
    Cart duplicated =
        cartOf("a", 1).toBuilder()
            .addItems(CartItem.newBuilder().setProductId("a").setQuantity(1))
            .build();
    when(redis.hget(USER, "cart")).thenReturn(duplicated.toByteArray());

    assertFailedPrecondition(() -> store.addItem(USER, "a", 1));
    verify(redis, never()).hset(anyString(), anyString(), any(byte[].class));
  }

  @Test
  void addItemFailsWithFailedPreconditionWhenValkeyFails() {
    when(redis.hget(USER, "cart")).thenThrow(new RedisException("connection reset"));

    StatusRuntimeException e = assertFailedPrecondition(() -> store.addItem(USER, "a", 1));

    assertThat(e.getStatus().getDescription())
        .startsWith("Can't access cart storage. ")
        .contains("connection reset");
  }

  @Test
  void addItemFailsWithFailedPreconditionWhenTheStoredCartIsCorrupt() {
    when(redis.hget(USER, "cart")).thenReturn(new byte[] {(byte) 0xff, (byte) 0xff, 1});

    assertFailedPrecondition(() -> store.addItem(USER, "a", 1));
  }

  @Test
  void addItemRecordsTheLatencyAlsoWhenItFails() {
    when(redis.hget(USER, "cart")).thenThrow(new RedisException("connection reset"));

    assertFailedPrecondition(() -> store.addItem(USER, "a", 1));

    assertThat(histogramCount("demo.cart.add_item.latency")).isEqualTo(1);
  }

  // --- EmptyCart

  @Test
  void emptyCartStoresAnEmptyCartAndExpiresItAfterSixtyMinutes() {
    store.emptyCart(USER);

    ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
    verify(redis).hset(eq(USER), eq("cart"), value.capture());
    assertThat(value.getValue()).isEmpty();
    verify(redis).expire(USER, TTL);
  }

  @Test
  void emptyCartFailsWithFailedPreconditionWhenValkeyFails() {
    when(redis.hset(anyString(), anyString(), any(byte[].class)))
        .thenThrow(new RedisException("connection reset"));

    assertFailedPrecondition(() -> store.emptyCart(USER));
  }

  // --- GetCart

  @Test
  void getCartOfAnUnknownUserIsAnEmptyCart() {
    when(redis.hget(USER, "cart")).thenReturn(null);

    assertThat(store.getCart(USER)).isEqualTo(Cart.getDefaultInstance());
  }

  @Test
  void getCartReturnsTheStoredCart() {
    Cart stored = cartOf("a", 3);
    when(redis.hget(USER, "cart")).thenReturn(stored.toByteArray());

    assertThat(store.getCart(USER)).isEqualTo(stored);
  }

  @Test
  void getCartFailsWithFailedPreconditionWhenValkeyFails() {
    when(redis.hget(USER, "cart")).thenThrow(new RedisException("connection reset"));

    assertFailedPrecondition(() -> store.getCart(USER));
    assertThat(histogramCount("demo.cart.get_cart.latency")).isEqualTo(1);
  }

  @Test
  void getCartLatencyHasNoAttributesWhenTheOverflowFlagIsOff() {
    when(featureClient.getBooleanValue("cartMetricCardinalityOverflow", false)).thenReturn(false);
    when(redis.hget(USER, "cart")).thenReturn(null);

    try (Scope ignored = newSpan().makeCurrent()) {
      store.getCart(USER);
    }

    List<HistogramPointData> points = histogramPoints("demo.cart.get_cart.latency");
    assertThat(points).hasSize(1);
    assertThat(points.get(0).getAttributes().isEmpty()).isTrue();
    assertThat(points.get(0).getCount()).isEqualTo(1);
  }

  @Test
  void getCartLatencyCarriesTheTraceIdWhenTheOverflowFlagIsOn() {
    when(featureClient.getBooleanValue("cartMetricCardinalityOverflow", false)).thenReturn(true);
    when(redis.hget(USER, "cart")).thenReturn(null);
    Span span = newSpan();

    try (Scope ignored = span.makeCurrent()) {
      store.getCart(USER);
    }

    List<HistogramPointData> points = histogramPoints("demo.cart.get_cart.latency");
    assertThat(points).hasSize(1);
    assertThat(points.get(0).getAttributes().get(AttributeKey.stringKey("demo.cart.trace_id")))
        .isEqualTo(span.getSpanContext().getTraceId());
  }

  @Test
  void getCartLatencyHasNoTraceIdWithoutASpanEvenWhenTheOverflowFlagIsOn() {
    when(featureClient.getBooleanValue("cartMetricCardinalityOverflow", false)).thenReturn(true);
    when(redis.hget(USER, "cart")).thenReturn(null);

    store.getCart(USER);

    List<HistogramPointData> points = histogramPoints("demo.cart.get_cart.latency");
    assertThat(points).hasSize(1);
    assertThat(points.get(0).getAttributes().isEmpty()).isTrue();
  }

  @Test
  void latencyHistogramsUseTheOriginalBucketBoundariesAndUnit() {
    when(redis.hget(USER, "cart")).thenReturn(null);
    store.addItem(USER, "a", 1);
    store.getCart(USER);

    List<Double> expected =
        List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0);
    for (String name : List.of("demo.cart.add_item.latency", "demo.cart.get_cart.latency")) {
      MetricData metric = metric(name);
      assertThat(metric.getUnit()).isEqualTo("s");
      assertThat(metric.getInstrumentationScopeInfo().getName()).isEqualTo("OpenTelemetry.Demo.Cart");
      assertThat(metric.getHistogramData().getPoints().iterator().next().getBoundaries())
          .isEqualTo(expected);
    }
  }

  // --- Ping

  @Test
  void pingIsFalseBeforeTheConnectionIsOpened() {
    assertThat(store.ping()).isFalse();
  }

  @Test
  void pingIsTrueWhileValkeyAnswers() {
    store.initialize();
    when(redis.ping()).thenReturn("PONG");

    assertThat(store.ping()).isTrue();
  }

  @Test
  void pingIsFalseWhenValkeyFails() {
    store.initialize();
    doThrow(new RedisException("down")).when(redis).ping();

    assertThat(store.ping()).isFalse();
  }

  // --- helpers

  private Cart savedCart() {
    try {
      ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
      verify(redis).hset(eq(USER), eq("cart"), value.capture());
      return Cart.parseFrom(value.getValue());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static Cart cartOf(String productId, int quantity) {
    return Cart.newBuilder()
        .setUserId(USER)
        .addItems(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
        .build();
  }

  private Span newSpan() {
    return openTelemetry.getTracer("test").spanBuilder("test").startSpan();
  }

  private static StatusRuntimeException assertFailedPrecondition(Runnable operation) {
    StatusRuntimeException[] caught = new StatusRuntimeException[1];
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).startsWith("Can't access cart storage.");
              caught[0] = e;
            });
    return caught[0];
  }

  private MetricData metric(String name) {
    return metricReader.collectAllMetrics().stream()
        .filter(m -> m.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no metric " + name));
  }

  private List<HistogramPointData> histogramPoints(String name) {
    return List.copyOf(metric(name).getHistogramData().getPoints());
  }

  private long histogramCount(String name) {
    return histogramPoints(name).stream().mapToLong(HistogramPointData::getCount).sum();
  }
}
