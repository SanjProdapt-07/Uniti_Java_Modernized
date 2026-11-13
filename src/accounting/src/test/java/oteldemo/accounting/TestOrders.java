/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import oteldemo.Demo.Address;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.Money;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;

final class TestOrders {

  /** H2 in PostgreSQL mode with the accounting schema of init.sql. */
  static String h2Url(String name) {
    return "jdbc:h2:mem:"
        + name
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        + ";INIT=RUNSCRIPT FROM 'classpath:accounting-schema.sql'";
  }

  private TestOrders() {}

  static OrderResult.Builder order(String orderId, String trackingId) {
    return OrderResult.newBuilder()
        .setOrderId(orderId)
        .setShippingTrackingId(trackingId)
        .setShippingCost(money("USD", 8, 990000000))
        .setShippingAddress(
            Address.newBuilder()
                .setStreetAddress("1600 Amphitheatre Parkway")
                .setCity("Mountain View")
                .setState("CA")
                .setCountry("United States")
                .setZipCode("94043"))
        .addItems(item("OLJCESPC7Z", 2, "USD", 19, 990000000))
        .addItems(item("66VCHSJNUP", 1, "USD", 349, 950000000));
  }

  static OrderItem item(String productId, int quantity, String currency, long units, int nanos) {
    return OrderItem.newBuilder()
        .setItem(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
        .setCost(money(currency, units, nanos))
        .build();
  }

  static Money money(String currency, long units, int nanos) {
    return Money.newBuilder().setCurrencyCode(currency).setUnits(units).setNanos(nanos).build();
  }
}
