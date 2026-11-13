/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import oteldemo.Demo.Address;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.Money;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;

/**
 * Builds the JSON bodies for the shipping and email services exactly the way Go's
 * {@code encoding/json} rendered the generated protobuf structs in the original: snake_case
 * field names, zero-valued scalar fields omitted ({@code omitempty}), unset message fields
 * omitted, int64 as a JSON number, and {@code null} for a nil pointer or nil slice that is a
 * value of the payload map.
 */
final class JsonPayloads {

  private JsonPayloads() {}

  static Map<String, Object> money(Money money) {
    Map<String, Object> json = new LinkedHashMap<>();
    putIfNotEmpty(json, "currency_code", money.getCurrencyCode());
    if (money.getUnits() != 0) {
      json.put("units", money.getUnits());
    }
    if (money.getNanos() != 0) {
      json.put("nanos", money.getNanos());
    }
    return json;
  }

  static Map<String, Object> address(Address address) {
    Map<String, Object> json = new LinkedHashMap<>();
    putIfNotEmpty(json, "street_address", address.getStreetAddress());
    putIfNotEmpty(json, "city", address.getCity());
    putIfNotEmpty(json, "state", address.getState());
    putIfNotEmpty(json, "country", address.getCountry());
    putIfNotEmpty(json, "zip_code", address.getZipCode());
    return json;
  }

  static Map<String, Object> cartItem(CartItem item) {
    Map<String, Object> json = new LinkedHashMap<>();
    putIfNotEmpty(json, "product_id", item.getProductId());
    if (item.getQuantity() != 0) {
      json.put("quantity", item.getQuantity());
    }
    return json;
  }

  static Map<String, Object> orderItem(OrderItem item) {
    Map<String, Object> json = new LinkedHashMap<>();
    if (item.hasItem()) {
      json.put("item", cartItem(item.getItem()));
    }
    if (item.hasCost()) {
      json.put("cost", money(item.getCost()));
    }
    return json;
  }

  static Map<String, Object> orderResult(OrderResult order) {
    Map<String, Object> json = new LinkedHashMap<>();
    putIfNotEmpty(json, "order_id", order.getOrderId());
    putIfNotEmpty(json, "shipping_tracking_id", order.getShippingTrackingId());
    if (order.hasShippingCost()) {
      json.put("shipping_cost", money(order.getShippingCost()));
    }
    if (order.hasShippingAddress()) {
      json.put("shipping_address", address(order.getShippingAddress()));
    }
    if (order.getItemsCount() > 0) {
      List<Object> items = new ArrayList<>();
      order.getItemsList().forEach(item -> items.add(orderItem(item)));
      json.put("items", items);
    }
    return json;
  }

  /** Body of POST /get-quote and POST /ship-order: {"address": ..., "items": ...}. */
  static Map<String, Object> addressAndItems(Address address, List<CartItem> items) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("address", address == null ? null : address(address));
    if (items == null || items.isEmpty()) {
      // an empty cart arrives as a nil slice in Go, which encoding/json renders as null
      json.put("items", null);
    } else {
      List<Object> list = new ArrayList<>();
      items.forEach(item -> list.add(cartItem(item)));
      json.put("items", list);
    }
    return json;
  }

  /** Body of POST /send_order_confirmation: {"email": ..., "order": ...}. */
  static Map<String, Object> orderConfirmation(String email, OrderResult order) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("email", email);
    json.put("order", orderResult(order));
    return json;
  }

  /**
   * The "%d.%02d" amount used for the demo.*.amount attributes: whole units and the first two
   * decimals of the nanos, parsed back into a double (0 if it does not parse, like Go).
   */
  static double amount(Money money) {
    String text =
        String.format(Locale.ROOT, "%d.%02d", money.getUnits(), money.getNanos() / 10_000_000);
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static void putIfNotEmpty(Map<String, Object> json, String key, String value) {
    if (!value.isEmpty()) {
      json.put(key, value);
    }
  }
}
