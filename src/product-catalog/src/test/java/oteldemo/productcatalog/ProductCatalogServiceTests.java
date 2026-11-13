/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import oteldemo.Demo.Empty;
import oteldemo.Demo.GetProductRequest;
import oteldemo.Demo.ListProductsResponse;
import oteldemo.Demo.Product;
import oteldemo.Demo.SearchProductsRequest;
import oteldemo.Demo.SearchProductsResponse;
import oteldemo.ProductCatalogServiceGrpc;
import oteldemo.productcatalog.ProductRepository.ProductQueryException;

@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "db.connection-string=jdbc:h2:mem:catalog;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;"
          + "INIT=RUNSCRIPT FROM 'classpath:catalog-schema.sql'",
      // not interesting here, see LockContentionTriggerTests
      "product-catalog.lock-contention.interval=1h"
    })
@AutoConfigureTestGrpcTransport
class ProductCatalogServiceTests {

  private static final List<String> ALL_IDS =
      List.of(
          "0PUK6V6EV0",
          "1YMWWN1N4O",
          "2ZYFJ3GM2N",
          "66VCHSJNUP",
          "6E92ZMYYFZ",
          "9SIQT8TOJO",
          "HQTGWGPNH4",
          "L9ECAV7KIM",
          "LS4PSXUNUM",
          "OLJCESPC7Z");

  @Autowired private GrpcChannelFactory channels;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private InMemoryProvider featureFlags;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private ProductRepository repository;

  @BeforeEach
  void setUp() {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setFlags(false, null);
    spans.reset();
  }

  @AfterEach
  void tearDown() {
    reset(repository);
    setFlags(false, null);
  }

  // ---- ListProducts -----------------------------------------------------------------------

  @Test
  void listProductsReturnsTheWholeCatalogOrderedById() {
    ListProductsResponse response = catalog().listProducts(Empty.getDefaultInstance());

    assertThat(response.getProductsList()).extracting(Product::getId).containsExactlyElementsOf(ALL_IDS);
  }

  @Test
  void listProductsMapsTheColumnsOfAProduct() {
    Product product =
        catalog().listProducts(Empty.getDefaultInstance()).getProductsList().stream()
            .filter(p -> p.getId().equals("2ZYFJ3GM2N"))
            .findFirst()
            .orElseThrow();

    assertThat(product.getName()).isEqualTo("Roof Binoculars");
    assertThat(product.getDescription()).startsWith("This versatile, all-around binocular");
    assertThat(product.getPicture()).isEqualTo("RoofBinoculars.jpg");
    assertThat(product.getPriceUsd().getCurrencyCode()).isEqualTo("USD");
    assertThat(product.getPriceUsd().getUnits()).isEqualTo(209);
    assertThat(product.getPriceUsd().getNanos()).isEqualTo(950000000);
    assertThat(product.getCategoriesList()).containsExactly("binoculars");
  }

  @Test
  void commaSeparatedCategoriesAreSplitAndTrimmed() {
    Product product =
        catalog().getProduct(GetProductRequest.newBuilder().setId("9SIQT8TOJO").build());

    assertThat(product.getCategoriesList()).containsExactly("accessories", "telescopes", "assembly");
    assertThat(ProductRepository.parseProductRow("i", "n", "d", "p", "USD", " a , b,c ", 1, 2).getCategoriesList())
        .containsExactly("a", "b", "c");
    assertThat(ProductRepository.parseProductRow("i", "n", "d", "p", "USD", "", 1, 2).getCategoriesList())
        .isEmpty();
  }

  @Test
  void listProductsRecordsTheProductCountOnTheServerSpan() {
    catalog().listProducts(Empty.getDefaultInstance());

    SpanData server = spanNamed("oteldemo.ProductCatalogService/ListProducts", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.product.count"))).isEqualTo(10L);
  }

  @Test
  void databaseCallsAreTracedAsChildrenOfTheServerSpan() {
    catalog().listProducts(Empty.getDefaultInstance());

    SpanData server = spanNamed("oteldemo.ProductCatalogService/ListProducts", SpanKind.SERVER);
    SpanData select =
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(
                () ->
                    spans.getFinishedSpanItems().stream()
                        .filter(s -> s.getKind() == SpanKind.CLIENT && s.getName().contains("SELECT"))
                        .findFirst(),
                Optional::isPresent)
            .orElseThrow();
    assertThat(select.getTraceId()).isEqualTo(server.getTraceId());
  }

  @Test
  void theTraceContextIsAppendedToTheSqlLikeSqlcommenter() {
    jdbc.execute("SET QUERY_STATISTICS TRUE");
    jdbc.execute("SET QUERY_STATISTICS_MAX_ENTRIES 1000");
    catalog().getProduct(GetProductRequest.newBuilder().setId("OLJCESPC7Z").build());

    List<String> statements =
        jdbc.queryForList("SELECT SQL_STATEMENT FROM INFORMATION_SCHEMA.QUERY_STATISTICS", String.class);
    assertThat(statements).anyMatch(sql -> sql.contains("catalog.products") && sql.contains("traceparent"));
  }

  @Test
  void aDatabaseFailureIsAnInternalErrorWithTheOriginalMessageShape() {
    doThrow(new ProductQueryException("failed to query products: connection refused", null))
        .when(repository)
        .loadProducts();

    assertThatThrownBy(() -> catalog().listProducts(Empty.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo("failed to load products: failed to query products: connection refused");
            });

    SpanData server = spanNamed("oteldemo.ProductCatalogService/ListProducts", SpanKind.SERVER);
    assertThat(server.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    // The gRPC instrumentation sets the final ERROR status of INTERNAL calls itself, which replaces
    // the description the service gave it (see PORTING-NOTES.md).
  }

  // ---- GetProduct -------------------------------------------------------------------------

  @Test
  void getProductReturnsTheProduct() {
    Product product =
        catalog().getProduct(GetProductRequest.newBuilder().setId("OLJCESPC7Z").build());

    assertThat(product.getId()).isEqualTo("OLJCESPC7Z");
    assertThat(product.getName()).isEqualTo("National Park Foundation Explorascope");
    assertThat(product.getDescription()).contains("The National Park Foundation's (NPF) Explorascope");
    assertThat(product.getPriceUsd().getUnits()).isEqualTo(101);
    assertThat(product.getPriceUsd().getNanos()).isEqualTo(960000000);
    assertThat(product.getCategoriesList()).containsExactly("telescopes");
  }

  @Test
  void getProductRecordsAttributesAndTheFoundEvent() {
    catalog().getProduct(GetProductRequest.newBuilder().setId("66VCHSJNUP").build());

    SpanData server = spanNamed("oteldemo.ProductCatalogService/GetProduct", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.product.id"))).isEqualTo("66VCHSJNUP");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.product.name")))
        .isEqualTo("Starsense Explorer Refractor Telescope");
    assertThat(server.getEvents()).extracting(e -> e.getName()).contains("Product Found");
    assertThat(server.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
  }

  @Test
  void getProductOfAnUnknownIdIsNotFound() {
    assertThatThrownBy(() -> catalog().getProduct(GetProductRequest.newBuilder().setId("NOPE").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
              assertThat(e.getStatus().getDescription()).isEqualTo("Product Not Found: NOPE");
            });

    SpanData server = spanNamed("oteldemo.ProductCatalogService/GetProduct", SpanKind.SERVER);
    assertThat(server.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(server.getStatus().getDescription()).isEqualTo("Product Not Found: NOPE");
    assertThat(server.getEvents()).extracting(e -> e.getName()).contains("Product Not Found: NOPE");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.product.id"))).isEqualTo("NOPE");
  }

  @Test
  void anyDatabaseErrorWhileLoadingOneProductIsReportedAsNotFound() {
    doThrow(new ProductRepository.ProductNotFoundException("failed to scan product row: boom", null))
        .when(repository)
        .getProduct("OLJCESPC7Z");

    assertThatThrownBy(() -> catalog().getProduct(GetProductRequest.newBuilder().setId("OLJCESPC7Z").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
              assertThat(e.getStatus().getDescription()).isEqualTo("Product Not Found: OLJCESPC7Z");
            });
  }

  @Test
  void theProductCatalogFailureFlagFailsOnlyTheFlaggedProduct() {
    setFlags(true, "OLJCESPC7Z");

    assertThatThrownBy(() -> catalog().getProduct(GetProductRequest.newBuilder().setId("OLJCESPC7Z").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo("Error: Product Catalog Fail Feature Flag Enabled");
            });

    SpanData server = spanNamed("oteldemo.ProductCatalogService/GetProduct", SpanKind.SERVER);
    assertThat(server.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(server.getEvents())
        .extracting(e -> e.getName())
        .contains("Error: Product Catalog Fail Feature Flag Enabled");

    // other products are not affected
    assertThat(catalog().getProduct(GetProductRequest.newBuilder().setId("66VCHSJNUP").build()).getName())
        .isEqualTo("Starsense Explorer Refractor Telescope");
  }

  @Test
  void theFailureFlagOnlyAffectsGetProduct() {
    setFlags(true, null);

    assertThat(catalog().listProducts(Empty.getDefaultInstance()).getProductsCount()).isEqualTo(10);
    assertThat(
            catalog()
                .searchProducts(SearchProductsRequest.newBuilder().setQuery("binocular").build())
                .getResultsCount())
        .isPositive();
    assertThatThrownBy(() -> catalog().getProduct(GetProductRequest.newBuilder().setId("66VCHSJNUP").build()))
        .isInstanceOf(StatusRuntimeException.class);
  }

  // ---- SearchProducts -----------------------------------------------------------------------

  @Test
  void searchMatchesNameOrDescriptionIgnoringCase() {
    assertThat(search("roof binoculars")).containsExactly("2ZYFJ3GM2N");
    assertThat(search("ROOF BINOCULARS")).containsExactly("2ZYFJ3GM2N");
    // only in a description
    assertThat(search("smartphone")).containsExactly("66VCHSJNUP");
    // name or description, ordered by id
    assertThat(search("telescope")).contains("1YMWWN1N4O", "66VCHSJNUP", "OLJCESPC7Z").isSorted();
  }

  @Test
  void searchWithoutMatchesAndWithAnEmptyQuery() {
    assertThat(search("no-such-product-xyz")).isEmpty();
    assertThat(search("")).containsExactlyElementsOf(ALL_IDS);
  }

  @Test
  void searchRecordsTheResultCount() {
    search("comet");

    SpanData server = spanNamed("oteldemo.ProductCatalogService/SearchProducts", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.product.search.count"))).isEqualTo(1L);
  }

  @Test
  void searchTreatsLikeWildcardsAsWildcardsJustLikeTheGoService() {
    // the query is not escaped
    assertThat(search("%")).containsExactlyElementsOf(ALL_IDS);
    assertThat(search("roof_binoculars")).containsExactly("2ZYFJ3GM2N");
  }

  @Test
  void aSearchFailureIsAnInternalError() {
    doThrow(new ProductQueryException("failed to query products: boom", null))
        .when(repository)
        .searchProducts("x");

    assertThatThrownBy(() -> catalog().searchProducts(SearchProductsRequest.newBuilder().setQuery("x").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo("failed to search products: failed to query products: boom");
            });
    SpanData server = spanNamed("oteldemo.ProductCatalogService/SearchProducts", SpanKind.SERVER);
    assertThat(server.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
  }

  // ---- NULL columns (database/sql cannot scan NULL into a string) -------------------------------

  @Test
  void aRowWithANullColumnFailsLikeTheGoScan() {
    jdbc.update(
        "INSERT INTO catalog.products (id, name, description, picture, price_currency_code, price_units,"
            + " price_nanos, categories) VALUES ('NULLDESC', 'n', NULL, 'p', 'USD', 1, 0, 'c')");
    try {
      assertThatThrownBy(() -> catalog().listProducts(Empty.getDefaultInstance()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              e -> {
                assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
                assertThat(e.getStatus().getDescription())
                    .startsWith("failed to load products: failed to get products from rows: failed to scan product row: ")
                    .contains("converting NULL to string is unsupported");
              });
      assertThatThrownBy(() -> catalog().getProduct(GetProductRequest.newBuilder().setId("NULLDESC").build()))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    } finally {
      jdbc.update("DELETE FROM catalog.products WHERE id = 'NULLDESC'");
    }
  }

  // ---- health ----------------------------------------------------------------------------------

  @Test
  void theStandardGrpcHealthServiceIsServingAndNotTraced() {
    Channel channel = channels.createChannel("product-catalog");
    HealthCheckResponse response =
        HealthGrpc.newBlockingStub(channel)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .check(HealthCheckRequest.getDefaultInstance());
    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);

    // a traced call right after proves that spans are flowing, but there is none for the health check
    catalog().getProduct(GetProductRequest.newBuilder().setId("OLJCESPC7Z").build());
    spanNamed("oteldemo.ProductCatalogService/GetProduct", SpanKind.SERVER);
    assertThat(spans.getFinishedSpanItems()).noneMatch(s -> s.getName().startsWith("grpc.health.v1.Health"));
  }

  @Test
  void serverReflectionListsTheServices() throws Exception {
    Channel channel = channels.createChannel("product-catalog");
    java.util.concurrent.CompletableFuture<List<String>> services = new java.util.concurrent.CompletableFuture<>();
    io.grpc.stub.StreamObserver<io.grpc.reflection.v1.ServerReflectionRequest> requests =
        io.grpc.reflection.v1.ServerReflectionGrpc.newStub(channel)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .serverReflectionInfo(
                new io.grpc.stub.StreamObserver<>() {
                  @Override
                  public void onNext(io.grpc.reflection.v1.ServerReflectionResponse response) {
                    services.complete(
                        response.getListServicesResponse().getServiceList().stream()
                            .map(io.grpc.reflection.v1.ServiceResponse::getName)
                            .toList());
                  }

                  @Override
                  public void onError(Throwable t) {
                    services.completeExceptionally(t);
                  }

                  @Override
                  public void onCompleted() {}
                });
    requests.onNext(io.grpc.reflection.v1.ServerReflectionRequest.newBuilder().setListServices("").build());

    assertThat(services.get(5, TimeUnit.SECONDS))
        .contains("oteldemo.ProductCatalogService", "grpc.health.v1.Health");
    requests.onCompleted();
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub catalog() {
    return ProductCatalogServiceGrpc.newBlockingStub(channels.createChannel("product-catalog"))
        .withDeadlineAfter(10, TimeUnit.SECONDS);
  }

  private List<String> search(String query) {
    SearchProductsResponse response =
        catalog().searchProducts(SearchProductsRequest.newBuilder().setQuery(query).build());
    return response.getResultsList().stream().map(Product::getId).toList();
  }

  // The server span ends after the client already has its response, so give it a moment.
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

  /**
   * Sets {@code productCatalogFailure}: on for every product, or only for {@code failingProductId}
   * (the flag is evaluated with the {@code product_id} context value, like in flagd targeting).
   */
  private void setFlags(boolean failure, String failingProductId) {
    Flag.FlagBuilder<Boolean> builder =
        Flag.<Boolean>builder().variant("on", true).variant("off", false).defaultVariant("off");
    if (failure) {
      builder.contextEvaluator(
          (flag, ctx) -> {
            var productId = ctx.getValue("product_id");
            boolean match =
                failingProductId == null
                    || (productId != null && failingProductId.equals(productId.asString()));
            return match;
          });
    }
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put(ProductCatalogService.PRODUCT_CATALOG_FAILURE_FLAG, builder.build());
    featureFlags.updateFlags(flags);
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
      return new InMemoryProvider(new HashMap<>());
    }
  }
}
