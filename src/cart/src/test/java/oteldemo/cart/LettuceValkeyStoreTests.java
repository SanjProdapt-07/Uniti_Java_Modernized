/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import dev.openfeature.sdk.Client;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.lettuce.core.resource.ClientResources;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;

/**
 * The store with the real Lettuce client against {@link FakeValkeyServer}, a minimal RESP2 server
 * written for these tests. This is NOT an integration test against a real Valkey.
 */
class LettuceValkeyStoreTests {

  private FakeValkeyServer valkey;
  private InMemorySpanExporter spans;
  private OpenTelemetrySdk openTelemetry;
  private ClientResources resources;
  private Client featureClient;

  @BeforeEach
  void setUp() throws Exception {
    valkey = new FakeValkeyServer();
    spans = InMemorySpanExporter.create();
    openTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans))
                    .build())
            .build();
    resources = new ValkeyConfiguration().valkeyClientResources(openTelemetry);
    featureClient = mock(Client.class);
  }

  @AfterEach
  void tearDown() throws Exception {
    resources.shutdown(0, 1, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS);
    valkey.close();
    openTelemetry.close();
  }

  private ValkeyCartStore store(String address, String password, boolean tls, int retries) {
    return new ValkeyCartStore(
        new LettuceValkeyLinkFactory(resources, address, password, tls),
        ValkeyAddress.parse(address).connectionString(tls),
        featureClient,
        openTelemetry,
        retries,
        Duration.ofMillis(10),
        Duration.ofMillis(20));
  }

  private ValkeyCartStore store() {
    return store("127.0.0.1:" + valkey.port(), null, false, 3);
  }

  @Test
  void connectingRunsTheSmallTest() {
    store().initialize();

    assertThat(valkey.commands()).anyMatch(c -> c.get(0).equalsIgnoreCase("SET") && c.get(1).equals("cart"));
    assertThat(valkey.commands()).anyMatch(c -> c.get(0).equalsIgnoreCase("GET") && c.get(1).equals("cart"));
  }

  @Test
  void addItemAndGetCartRoundTripThroughTheCartHash() throws Exception {
    ValkeyCartStore store = store();
    String userId = "user-roundtrip";

    store.addItem(userId, "OLJCESPC7Z", 2);
    store.addItem(userId, "OLJCESPC7Z", 3);
    store.addItem(userId, "66VCHSJNUP", 1);

    Cart cart = store.getCart(userId);
    assertThat(cart.getUserId()).isEqualTo(userId);
    assertThat(cart.getItemsList())
        .containsExactly(
            CartItem.newBuilder().setProductId("OLJCESPC7Z").setQuantity(5).build(),
            CartItem.newBuilder().setProductId("66VCHSJNUP").setQuantity(1).build());

    // stored as the protobuf encoded cart in the "cart" field of a hash keyed by the user id
    assertThat(Cart.parseFrom(valkey.hash(userId).get("cart"))).isEqualTo(cart);
    assertThat(valkey.expirySeconds(userId)).isEqualTo(3600L);
  }

  @Test
  void emptyCartStoresAnEmptyCart() {
    ValkeyCartStore store = store();
    store.addItem("u", "p", 1);

    store.emptyCart("u");

    assertThat(store.getCart("u").getItemsList()).isEmpty();
    assertThat(valkey.hash("u").get("cart")).isEmpty();
    assertThat(valkey.expirySeconds("u")).isEqualTo(3600L);
  }

  @Test
  void getCartOfAnUnknownUserIsAnEmptyCart() {
    assertThat(store().getCart("nobody")).isEqualTo(Cart.getDefaultInstance());
  }

  @Test
  void pingAnswersOnceConnected() {
    ValkeyCartStore store = store();
    assertThat(store.ping()).isFalse();

    store.initialize();

    assertThat(store.ping()).isTrue();
  }

  @Test
  void thePasswordIsSentWhenConfigured() {
    valkey.requirePassword("s3cret");

    store("127.0.0.1:" + valkey.port(), "s3cret", false, 1).addItem("u", "p", 1);

    assertThat(valkey.commands())
        .anyMatch(c -> c.get(0).equalsIgnoreCase("AUTH") && c.contains("s3cret"));
    assertThat(valkey.hash("u")).isNotNull();
  }

  @Test
  void aWrongPasswordMakesOperationsFailWithFailedPrecondition() {
    valkey.requirePassword("s3cret");
    ValkeyCartStore store = store("127.0.0.1:" + valkey.port(), "wrong", false, 1);

    assertThatThrownBy(() -> store.addItem("u", "p", 1))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
  }

  @Test
  void anUnreachableValkeyMakesOperationsFailWithFailedPrecondition() throws Exception {
    int freePort;
    try (ServerSocket socket = new ServerSocket(0)) {
      freePort = socket.getLocalPort();
    }
    ValkeyCartStore store = store("127.0.0.1:" + freePort, null, false, 2);

    assertThatThrownBy(() -> store.addItem("u", "p", 1))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(e.getStatus().getDescription()).startsWith("Can't access cart storage.");
            });
  }

  @Test
  void theStoreRecoversAfterTheConnectionIsDropped() {
    ValkeyCartStore store = store();
    store.addItem("u", "p", 1);

    valkey.dropConnections();

    await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> assertThat(store.getCart("u").getItemsList()).hasSize(1));
  }

  @Test
  void redisCommandsAreTracedWithTheFullStatement() {
    ValkeyCartStore store = store();
    store.addItem("user-traced", "OLJCESPC7Z", 2);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(spans.getFinishedSpanItems())
                  .anyMatch(
                      span ->
                          span.getKind() == SpanKind.CLIENT
                              && span.getName().toUpperCase().contains("HGET"));
            });
    SpanData hget =
        spans.getFinishedSpanItems().stream()
            .filter(span -> span.getName().toUpperCase().contains("HGET"))
            .findFirst()
            .orElseThrow();
    String statement =
        hget.getAttributes().asMap().entrySet().stream()
            .filter(e -> e.getKey().getKey().equals("db.statement") || e.getKey().getKey().equals("db.query.text"))
            .map(e -> String.valueOf(e.getValue()))
            .findFirst()
            .orElse("");
    // not sanitized: the key and field are part of the statement
    assertThat(statement).contains("user-traced");
  }

  @Test
  void theCartDataIsBinarySafe() {
    ValkeyCartStore store = store();
    String userId = "üser-ключ";
    store.addItem(userId, new String(new byte[] {'a', 'b'}, StandardCharsets.UTF_8), 1);

    assertThat(store.getCart(userId).getItems(0).getProductId()).isEqualTo("ab");
  }
}
