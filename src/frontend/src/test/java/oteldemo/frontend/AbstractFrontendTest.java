/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import oteldemo.AdServiceGrpc.AdServiceBlockingStub;
import oteldemo.CartServiceGrpc.CartServiceBlockingStub;
import oteldemo.CheckoutServiceGrpc.CheckoutServiceBlockingStub;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.Demo.Money;
import oteldemo.Demo.Product;
import oteldemo.ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub;
import oteldemo.RecommendationServiceGrpc.RecommendationServiceBlockingStub;
import oteldemo.frontend.gateway.ShippingGateway;

/** Boots the whole application with the six gRPC stubs and the shipping gateway mocked. */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "frontend.public-otel-traces-endpoint=http://localhost:8080/otlp-http/v1/traces",
      "frontend.otel-collector-host=otel-collector-test"
    })
@AutoConfigureMockMvc
@Import(AbstractFrontendTest.TestConfig.class)
abstract class AbstractFrontendTest {

  @Autowired protected MockMvc mvc;
  @Autowired protected InMemorySpanExporter spans;

  @MockitoBean protected AdServiceBlockingStub ad;
  @MockitoBean protected CartServiceBlockingStub cart;
  @MockitoBean protected CheckoutServiceBlockingStub checkout;
  @MockitoBean protected CurrencyServiceBlockingStub currency;
  @MockitoBean protected ProductCatalogServiceBlockingStub productCatalog;
  @MockitoBean protected RecommendationServiceBlockingStub recommendation;
  @MockitoBean protected ShippingGateway shipping;

  @BeforeEach
  void resetSpans() {
    spans.reset();
  }

  protected static Money money(String currency, long units, int nanos) {
    return Money.newBuilder().setCurrencyCode(currency).setUnits(units).setNanos(nanos).build();
  }

  protected static Product product(String id, String name, String picture, Money price, String... categories) {
    Product.Builder builder =
        Product.newBuilder()
            .setId(id)
            .setName(name)
            .setDescription(name + " description")
            .setPicture(picture)
            .addAllCategories(java.util.List.of(categories));
    if (price != null) {
      builder.setPriceUsd(price);
    }
    return builder.build();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(InMemorySpanExporter spanExporter) {
      return customizer ->
          customizer.addTracerProviderCustomizer(
              (tracerProvider, config) ->
                  tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)));
    }

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      InMemoryProvider provider = new InMemoryProvider(new HashMap<>());
      OpenFeatureAPI.getInstance().setProviderAndWait(provider);
      return provider;
    }
  }
}
