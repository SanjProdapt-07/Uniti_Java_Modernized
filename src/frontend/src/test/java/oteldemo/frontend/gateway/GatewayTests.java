/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.GetProductRequest;
import oteldemo.Demo.ListProductsResponse;
import oteldemo.Demo.Money;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.Product;
import oteldemo.ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub;

class GatewayTests {

  // ---- ProtoJson ----

  @Test
  void protoJsonUsesNumbersForInt64AndIncludesDefaults() {
    Product product =
        Product.newBuilder()
            .setId("A")
            .setPriceUsd(Money.newBuilder().setCurrencyCode("USD").setUnits(3_000_000_000L).setNanos(5))
            .build();

    Map<String, Object> json = ProtoJson.toMap(product);

    assertThat(json).containsEntry("id", "A").containsEntry("name", "").containsEntry("picture", "");
    assertThat(json.get("categories")).isEqualTo(List.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> price = (Map<String, Object>) json.get("priceUsd");
    assertThat(price.get("units")).isEqualTo(3_000_000_000L).isInstanceOf(Long.class);
    assertThat(price.get("nanos")).isEqualTo(5);
    assertThat(price).containsEntry("currencyCode", "USD");
  }

  @Test
  void protoJsonOmitsUnsetMessageFields() {
    Map<String, Object> json = ProtoJson.toMap(OrderItem.newBuilder().build());
    assertThat(json).isEmpty();
  }

  @Test
  void protoJsonUsesCamelCaseNames() {
    Map<String, Object> json = ProtoJson.toMap(CartItem.newBuilder().setProductId("P").setQuantity(2).build());
    assertThat(json).containsOnlyKeys("productId", "quantity");
  }

  @Test
  void protoJsonMergeAcceptsCamelCaseAndNumericStringsAndIgnoresUnknownFields() {
    CartItem item =
        ProtoJson.merge(
                "{\"productId\":\"P\",\"quantity\":\"4\",\"somethingElse\":true}", CartItem.newBuilder())
            .build();
    assertThat(item.getProductId()).isEqualTo("P");
    assertThat(item.getQuantity()).isEqualTo(4);
  }

  @Test
  void protoJsonMergeRejectsWrongTypes() {
    assertThatThrownBy(() -> ProtoJson.merge("{\"quantity\":{\"x\":1}}", CartItem.newBuilder()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- ShippingGateway ----

  private static final String QUOTE_RESPONSE =
      "{\"cost_usd\":{\"currency_code\":\"USD\",\"units\":8,\"nanos\":990000000}}";

  private ShippingGateway gateway(MockRestServiceServer[] server) {
    RestClient.Builder builder = RestClient.builder();
    server[0] = MockRestServiceServer.bindTo(builder).build();
    return new ShippingGateway(builder, "http://shipping:50050");
  }

  @Test
  void shippingPostsTheSnakeCaseContractAndMapsTheQuote() {
    MockRestServiceServer[] server = new MockRestServiceServer[1];
    ShippingGateway shipping = gateway(server);
    server[0]
        .expect(requestTo("http://shipping:50050/get-quote"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.items[0].product_id").value("A"))
        .andExpect(jsonPath("$.items[0].quantity").value(2))
        .andExpect(jsonPath("$.address.street_address").value("1 Main"))
        .andExpect(jsonPath("$.address.city").value("Town"))
        .andExpect(jsonPath("$.address.state").value("CA"))
        .andExpect(jsonPath("$.address.country").value("US"))
        .andExpect(jsonPath("$.address.zip_code").value("90210"))
        .andRespond(withSuccess(QUOTE_RESPONSE, MediaType.APPLICATION_JSON));

    Money cost =
        shipping.getShippingCost(
            List.of(Map.of("productId", "A", "quantity", 2)),
            Map.of(
                "streetAddress", "1 Main",
                "city", "Town",
                "state", "CA",
                "country", "US",
                "zipCode", "90210"));

    assertThat(cost.getCurrencyCode()).isEqualTo("USD");
    assertThat(cost.getUnits()).isEqualTo(8);
    assertThat(cost.getNanos()).isEqualTo(990000000);
    server[0].verify();
  }

  @Test
  void shippingReturnsNullWhenTheQuoteHasNoCost() {
    MockRestServiceServer[] server = new MockRestServiceServer[1];
    ShippingGateway shipping = gateway(server);
    server[0].expect(requestTo("http://shipping:50050/get-quote")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(shipping.getShippingCost(List.of(), Map.of())).isNull();
  }

  @Test
  void shippingHttpErrorsCarryStatusAndBody() {
    MockRestServiceServer[] server = new MockRestServiceServer[1];
    ShippingGateway shipping = gateway(server);
    server[0]
        .expect(requestTo("http://shipping:50050/get-quote"))
        .andRespond(withServerError().body("boom").contentType(MediaType.TEXT_PLAIN));

    assertThatThrownBy(() -> shipping.getShippingCost(List.of(), Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP error: 500")
        .hasMessageContaining("boom");
  }

  // ---- ProductCatalogService ----

  @Test
  void catalogConvertsOnlyWhenACurrencyOtherThanUsdIsRequested() {
    ProductCatalogServiceBlockingStub catalog = mock(ProductCatalogServiceBlockingStub.class);
    CurrencyServiceBlockingStub currency = mock(CurrencyServiceBlockingStub.class);
    ProductCatalogService service = new ProductCatalogService(catalog, currency, new ParallelExecutor());
    when(catalog.getProduct(any(GetProductRequest.class)))
        .thenReturn(
            Product.newBuilder().setId("A").setPriceUsd(Money.newBuilder().setCurrencyCode("USD").setUnits(10)).build());
    when(currency.convert(any(CurrencyConversionRequest.class)))
        .thenReturn(Money.newBuilder().setCurrencyCode("JPY").setUnits(1500).build());

    assertThat(service.getProduct("A", "")).extracting("priceUsd").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("currencyCode", "USD");
    assertThat(service.getProduct("A", "USD")).extracting("priceUsd").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("currencyCode", "USD");
    verify(currency, never()).convert(any());

    assertThat(service.getProduct("A", "JPY")).extracting("priceUsd").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("currencyCode", "JPY").containsEntry("units", 1500L);
    verify(currency).convert(CurrencyConversionRequest.newBuilder().setFrom(Money.newBuilder().setCurrencyCode("USD").setUnits(10)).setToCode("JPY").build());
  }

  @Test
  void catalogListsEveryProduct() {
    ProductCatalogServiceBlockingStub catalog = mock(ProductCatalogServiceBlockingStub.class);
    CurrencyServiceBlockingStub currency = mock(CurrencyServiceBlockingStub.class);
    ProductCatalogService service = new ProductCatalogService(catalog, currency, new ParallelExecutor());
    when(catalog.listProducts(any()))
        .thenReturn(
            ListProductsResponse.newBuilder()
                .addProducts(Product.newBuilder().setId("A"))
                .addProducts(Product.newBuilder().setId("B"))
                .build());

    assertThat(service.listProducts("USD")).extracting(p -> p.get("id")).containsExactly("A", "B");
  }

  // ---- ParallelExecutor ----

  @Test
  void parallelExecutorKeepsOrderAndRunsConcurrently() {
    ParallelExecutor executor = new ParallelExecutor();
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxRunning = new AtomicInteger();

    List<Integer> result =
        executor.map(
            List.of(1, 2, 3, 4),
            n -> {
              int now = running.incrementAndGet();
              maxRunning.accumulateAndGet(now, Math::max);
              try {
                Thread.sleep(150);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              running.decrementAndGet();
              return n * 10;
            });

    assertThat(result).containsExactly(10, 20, 30, 40);
    assertThat(maxRunning.get()).isGreaterThan(1);
  }

  @Test
  void parallelExecutorRethrowsTheTaskFailureUnwrapped() {
    ParallelExecutor executor = new ParallelExecutor();
    assertThatThrownBy(
            () ->
                executor.map(
                    List.of(1, 2),
                    n -> {
                      if (n == 2) {
                        throw new IllegalStateException("task " + n + " failed");
                      }
                      return n;
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("task 2 failed");
  }
}
