/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes an {@link OrderResult} to the {@code accounting} schema: one {@code order} row, one
 * {@code orderitem} row per item and one {@code shipping} row, all in a single transaction (the
 * original saved the three entity kinds with one EF Core {@code SaveChanges()}).
 *
 * <p>Table and column names are the ones the EF Core model produced with its snake case naming
 * convention and that {@code src/postgresql/init.sql} creates.
 */
class OrderStore {

  /** PostgreSQL SQLSTATE for unique_violation. */
  static final String UNIQUE_VIOLATION = "23505";

  private static final String INSERT_ORDER = "INSERT INTO accounting.\"order\" (order_id) VALUES (?)";

  private static final String INSERT_ORDER_ITEM =
      "INSERT INTO accounting.orderitem (item_cost_currency_code, item_cost_units, item_cost_nanos, "
          + "product_id, quantity, order_id) VALUES (?, ?, ?, ?, ?, ?)";

  private static final String INSERT_SHIPPING =
      "INSERT INTO accounting.shipping (shipping_tracking_id, shipping_cost_currency_code, "
          + "shipping_cost_units, shipping_cost_nanos, street_address, city, state, country, zip_code, "
          + "order_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  OrderStore(JdbcTemplate jdbc, TransactionTemplate transaction) {
    this.jdbc = jdbc;
    this.transaction = transaction;
  }

  void save(OrderResult order) {
    // Done before touching the database, like building the EF entities before SaveChanges().
    validate(order);

    transaction.executeWithoutResult(
        status -> {
          jdbc.update(INSERT_ORDER, order.getOrderId());
          for (OrderItem item : order.getItemsList()) {
            jdbc.update(
                INSERT_ORDER_ITEM,
                item.getCost().getCurrencyCode(),
                item.getCost().getUnits(),
                item.getCost().getNanos(),
                item.getItem().getProductId(),
                item.getItem().getQuantity(),
                order.getOrderId());
          }
          jdbc.update(
              INSERT_SHIPPING,
              order.getShippingTrackingId(),
              order.getShippingCost().getCurrencyCode(),
              order.getShippingCost().getUnits(),
              order.getShippingCost().getNanos(),
              order.getShippingAddress().getStreetAddress(),
              order.getShippingAddress().getCity(),
              order.getShippingAddress().getState(),
              order.getShippingAddress().getCountry(),
              order.getShippingAddress().getZipCode(),
              order.getOrderId());
        });
  }

  /**
   * The .NET code dereferenced the nested messages directly, so an order without shipping cost,
   * shipping address or item details failed with a NullReferenceException, and two lines with the
   * same product id failed on EF Core's identity tracking. Both ended up as "Order parsing
   * failed", not as a duplicate order.
   */
  private static void validate(OrderResult order) {
    if (!order.hasShippingCost()) {
      throw new NullPointerException("Object reference not set to an instance of an object: ShippingCost");
    }
    if (!order.hasShippingAddress()) {
      throw new NullPointerException("Object reference not set to an instance of an object: ShippingAddress");
    }
    Set<String> productIds = new HashSet<>();
    for (OrderItem item : order.getItemsList()) {
      if (!item.hasCost() || !item.hasItem()) {
        throw new NullPointerException("Object reference not set to an instance of an object: item");
      }
      if (!productIds.add(item.getItem().getProductId())) {
        throw new IllegalStateException(
            "The instance of entity type 'OrderItemEntity' cannot be tracked because another instance with the same key value for {'ProductId', 'OrderId'} is already being tracked.");
      }
    }
  }

  /** True when the failure (or one of its causes) is a PostgreSQL unique constraint violation. */
  static boolean isUniqueViolation(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}
