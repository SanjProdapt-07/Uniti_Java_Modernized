/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import oteldemo.CartServiceGrpc;
import oteldemo.Demo.AddItemRequest;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.EmptyCartRequest;
import oteldemo.Demo.GetCartRequest;

/**
 * The whole application as wired for production (Valkey store, Lettuce, the gRPC server) with
 * {@link FakeValkeyServer} standing in for Valkey: the configuration properties are the same
 * ones the {@code VALKEY_*} environment variables feed. Not an integration test of a real Valkey.
 */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none"
    })
@AutoConfigureTestGrpcTransport
class CartApplicationWiringTests {

  private static FakeValkeyServer valkey;

  @AfterAll
  static void stopValkey() throws IOException {
    valkey.close();
  }

  @DynamicPropertySource
  static void valkeyProperties(DynamicPropertyRegistry registry) throws IOException {
    if (valkey == null) {
      valkey = new FakeValkeyServer();
    }
    registry.add("cart.valkey.address", () -> "127.0.0.1:" + valkey.port());
  }

  @Autowired private GrpcChannelFactory channels;
  @Autowired private InMemoryMetricReader metrics;

  @Autowired
  @Qualifier("cartStore")
  private CartStore cartStore;

  @Test
  void theProductionStoreIsTheValkeyStoreAndItsConnectionWasOpenedAtStartup() {
    assertThat(cartStore).isInstanceOf(ValkeyCartStore.class);
    assertThat(cartStore.ping()).isTrue();
    assertThat(valkey.commands()).anyMatch(c -> c.get(0).equalsIgnoreCase("SET") && c.get(1).equals("cart"));
  }

  @Test
  void cartRoundTripThroughGrpcLandsInValkeyAndRecordsTheLatencyMetrics() throws Exception {
    OpenFeatureAPI.getInstance().setProviderAndWait(new InMemoryProvider(noFlags()));
    CartServiceGrpc.CartServiceBlockingStub client =
        CartServiceGrpc.newBlockingStub(channels.createChannel("cart"))
            .withDeadlineAfter(5, TimeUnit.SECONDS);
    String userId = "wiring-user";

    client.addItem(
        AddItemRequest.newBuilder()
            .setUserId(userId)
            .setItem(CartItem.newBuilder().setProductId("OLJCESPC7Z").setQuantity(2))
            .build());
    Cart cart = client.getCart(GetCartRequest.newBuilder().setUserId(userId).build());

    assertThat(cart.getItemsList()).hasSize(1);
    assertThat(Cart.parseFrom(valkey.hash(userId).get("cart"))).isEqualTo(cart);

    client.emptyCart(EmptyCartRequest.newBuilder().setUserId(userId).build());
    assertThat(client.getCart(GetCartRequest.newBuilder().setUserId(userId).build()).getItemsList())
        .isEmpty();

    for (String name : new String[] {"demo.cart.add_item.latency", "demo.cart.get_cart.latency"}) {
      long count =
          metrics.collectAllMetrics().stream()
              .filter(metric -> metric.getName().equals(name))
              .flatMap(metric -> metric.getHistogramData().getPoints().stream())
              .mapToLong(HistogramPointData::getCount)
              .sum();
      assertThat(count).as(name).isGreaterThanOrEqualTo(1);
    }
  }

  private static Map<String, Flag<?>> noFlags() {
    return new HashMap<>();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemoryMetricReader inMemoryMetricReader() {
      return InMemoryMetricReader.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryMetrics(InMemoryMetricReader metricReader) {
      return customizer ->
          customizer.addMeterProviderCustomizer(
              (meterProvider, config) -> meterProvider.registerMetricReader(metricReader));
    }

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }
  }
}
