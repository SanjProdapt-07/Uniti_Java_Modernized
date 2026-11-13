/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;

class OrderStoreTests {

  private JdbcTemplate jdbc;
  private OrderStore store;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(TestOrders.h2Url("store-" + UUID.randomUUID()), "sa", "");
    jdbc = new JdbcTemplate(dataSource);
    store = new OrderStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  @Test
  void writesOrderItemsAndShippingWithTheOriginalColumnNames() {
    store.save(TestOrders.order("order-1", "track-1").build());

    assertThat(jdbc.queryForList("SELECT order_id FROM accounting.\"order\""))
        .containsExactly(Map.of("order_id", "order-1"));

    List<Map<String, Object>> items =
        jdbc.queryForList(
            "SELECT item_cost_currency_code, item_cost_units, item_cost_nanos, product_id, quantity, order_id "
                + "FROM accounting.orderitem ORDER BY product_id");
    assertThat(items).hasSize(2);
    assertThat(items.get(0))
        .containsEntry("product_id", "66VCHSJNUP")
        .containsEntry("quantity", 1)
        .containsEntry("item_cost_currency_code", "USD")
        .containsEntry("item_cost_units", 349L)
        .containsEntry("item_cost_nanos", 950000000)
        .containsEntry("order_id", "order-1");
    assertThat(items.get(1)).containsEntry("product_id", "OLJCESPC7Z").containsEntry("quantity", 2);

    Map<String, Object> shipping =
        jdbc.queryForMap(
            "SELECT shipping_tracking_id, shipping_cost_currency_code, shipping_cost_units, shipping_cost_nanos, "
                + "street_address, city, state, country, zip_code, order_id FROM accounting.shipping");
    assertThat(shipping)
        .containsEntry("shipping_tracking_id", "track-1")
        .containsEntry("shipping_cost_currency_code", "USD")
        .containsEntry("shipping_cost_units", 8L)
        .containsEntry("shipping_cost_nanos", 990000000)
        .containsEntry("street_address", "1600 Amphitheatre Parkway")
        .containsEntry("city", "Mountain View")
        .containsEntry("state", "CA")
        .containsEntry("country", "United States")
        .containsEntry("zip_code", "94043")
        .containsEntry("order_id", "order-1");
  }

  @Test
  void orderWithoutItemsOnlyWritesOrderAndShipping() {
    store.save(TestOrders.order("order-2", "track-2").clearItems().build());

    assertThat(count("accounting.\"order\"")).isEqualTo(1);
    assertThat(count("accounting.orderitem")).isZero();
    assertThat(count("accounting.shipping")).isEqualTo(1);
  }

  @Test
  void duplicateOrderIsReportedAsUniqueViolation() {
    store.save(TestOrders.order("order-3", "track-3").build());

    assertThatThrownBy(() -> store.save(TestOrders.order("order-3", "track-3").build()))
        .isInstanceOf(DuplicateKeyException.class)
        .satisfies(failure -> assertThat(OrderStore.isUniqueViolation(failure)).isTrue());
    assertThat(count("accounting.\"order\"")).isEqualTo(1);
  }

  @Test
  void failureRollsBackTheWholeOrder() {
    store.save(TestOrders.order("order-4", "shared-tracking").build());

    // new order id but an already used tracking id: the order and item rows must not remain
    assertThatThrownBy(() -> store.save(TestOrders.order("order-5", "shared-tracking").build()))
        .satisfies(failure -> assertThat(OrderStore.isUniqueViolation(failure)).isTrue());

    assertThat(jdbc.queryForList("SELECT order_id FROM accounting.\"order\"")).hasSize(1);
    assertThat(count("accounting.orderitem")).isEqualTo(2);
  }

  @Test
  void missingNestedMessagesFailLikeTheNullReferenceOfTheOriginal() {
    OrderResult noShippingCost = TestOrders.order("o", "t").clearShippingCost().build();
    OrderResult noAddress = TestOrders.order("o", "t").clearShippingAddress().build();
    OrderResult itemWithoutCost =
        TestOrders.order("o", "t").addItems(OrderItem.newBuilder().setItem(TestOrders.item("P", 1, "USD", 1, 0).getItem())).build();

    for (OrderResult order : List.of(noShippingCost, noAddress, itemWithoutCost)) {
      assertThatThrownBy(() -> store.save(order))
          .isInstanceOf(NullPointerException.class)
          .satisfies(failure -> assertThat(OrderStore.isUniqueViolation(failure)).isFalse());
    }
    assertThat(count("accounting.\"order\"")).isZero();
  }

  @Test
  void sameProductTwiceIsNotMistakenForADuplicateOrder() {
    OrderResult order =
        TestOrders.order("order-6", "track-6").addItems(TestOrders.item("OLJCESPC7Z", 1, "USD", 19, 0)).build();

    assertThatThrownBy(() -> store.save(order))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(failure -> assertThat(OrderStore.isUniqueViolation(failure)).isFalse());
    assertThat(count("accounting.\"order\"")).isZero();
  }

  @Test
  void uniqueViolationDetectionWalksTheCauseChain() {
    java.sql.SQLException unique = new java.sql.SQLException("dup", OrderStore.UNIQUE_VIOLATION);
    java.sql.SQLException other = new java.sql.SQLException("fk", "23503");

    assertThat(OrderStore.isUniqueViolation(new RuntimeException("wrapper", unique))).isTrue();
    assertThat(OrderStore.isUniqueViolation(new RuntimeException("wrapper", other))).isFalse();
    assertThat(OrderStore.isUniqueViolation(new RuntimeException("no cause"))).isFalse();
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }
}
