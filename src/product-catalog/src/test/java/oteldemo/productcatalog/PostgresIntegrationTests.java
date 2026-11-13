/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.GrpcChannelFactory;
import oteldemo.Demo.Empty;
import oteldemo.Demo.GetProductRequest;
import oteldemo.Demo.Product;
import oteldemo.Demo.SearchProductsRequest;
import oteldemo.ProductCatalogServiceGrpc;

/**
 * Runs the service against a real PostgreSQL that was initialised with src/postgresql/init.sql.
 * Skipped unless PRODUCT_CATALOG_TEST_DB holds a connection string in the DB_CONNECTION_STRING
 * format, e.g. {@code postgres://astronomy_user:astronomy_password@127.0.0.1:5432/astronomy_db?sslmode=disable}.
 */
@EnabledIfEnvironmentVariable(named = "PRODUCT_CATALOG_TEST_DB", matches = ".+")
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "db.connection-string=${PRODUCT_CATALOG_TEST_DB}",
      "product-catalog.lock-contention.interval=1h",
      "product-catalog.lock-contention.hold=4s"
    })
@AutoConfigureTestGrpcTransport
class PostgresIntegrationTests {

  @Autowired private GrpcChannelFactory channels;
  @Autowired private InMemoryProvider featureFlags;
  @Autowired private LockContentionTrigger lockContention;

  @AfterEach
  void tearDown() {
    setLockContentionFlag(false);
  }

  @Test
  void readsTheCatalogFromPostgres() {
    List<Product> products = catalog().listProducts(Empty.getDefaultInstance()).getProductsList();

    assertThat(products).hasSize(10);
    assertThat(products).extracting(Product::getId).isSorted();
    Product binoculars = products.stream().filter(p -> p.getId().equals("2ZYFJ3GM2N")).findFirst().orElseThrow();
    assertThat(binoculars.getName()).isEqualTo("Roof Binoculars");
    assertThat(binoculars.getPriceUsd().getUnits()).isEqualTo(209);
    assertThat(binoculars.getPriceUsd().getNanos()).isEqualTo(950000000);
    assertThat(binoculars.getCategoriesList()).containsExactly("binoculars");
  }

  @Test
  void getAndSearchWorkAgainstPostgres() {
    assertThat(catalog().getProduct(GetProductRequest.newBuilder().setId("OLJCESPC7Z").build()).getName())
        .isEqualTo("National Park Foundation Explorascope");
    assertThatThrownBy(() -> catalog().getProduct(GetProductRequest.newBuilder().setId("NOPE").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    assertThat(
            catalog().searchProducts(SearchProductsRequest.newBuilder().setQuery("ROOF").build()).getResultsList())
        .extracting(Product::getId)
        .containsExactly("2ZYFJ3GM2N");
  }

  @Test
  void theLockContentionFlagBlocksTheCatalogTableUntilTheLockIsReleased() throws Exception {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setLockContentionFlag(true);

    lockContention.tick();

    try (Connection other = otherConnection()) {
      try (Statement statement = other.createStatement()) {
        statement.execute("SET lock_timeout = '300ms'");
      }
      // while the ACCESS EXCLUSIVE lock is held, reading the table runs into the lock timeout
      await()
          .atMost(3, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThatThrownBy(
                          () -> {
                            try (Statement statement = other.createStatement()) {
                              statement.executeQuery("SELECT count(*) FROM catalog.products");
                            }
                          })
                      .isInstanceOf(SQLException.class)
                      .hasMessageContaining("lock timeout"));
      // after the hold time (4s) the transaction is rolled back and the table is readable again
      await()
          .atMost(10, TimeUnit.SECONDS)
          .ignoreExceptions() // lock timeouts until the lock is released
          .untilAsserted(
              () -> {
                try (Statement statement = other.createStatement()) {
                  assertThat(statement.executeQuery("SELECT count(*) FROM catalog.products").next()).isTrue();
                }
              });
    }
  }

  private Connection otherConnection() throws SQLException {
    ConnectionString parsed = ConnectionString.parse(System.getenv("PRODUCT_CATALOG_TEST_DB"));
    Properties properties = new Properties();
    properties.putAll(parsed.properties());
    properties.setProperty("user", parsed.username());
    properties.setProperty("password", parsed.password());
    Connection connection = DriverManager.getConnection(parsed.jdbcUrl(), properties);
    connection.setAutoCommit(true);
    return connection;
  }

  private ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub catalog() {
    return ProductCatalogServiceGrpc.newBlockingStub(channels.createChannel("product-catalog"))
        .withDeadlineAfter(10, TimeUnit.SECONDS);
  }

  private void setLockContentionFlag(boolean enabled) {
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put(
        LockContentionTrigger.LOCK_CONTENTION_FLAG,
        Flag.<Boolean>builder()
            .variant("on", true)
            .variant("off", false)
            .defaultVariant(enabled ? "on" : "off")
            .build());
    featureFlags.updateFlags(flags);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }
  }
}
