/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.math.BigInteger;
import java.util.List;

/**
 * Wire types of the shipping HTTP API. The JSON field names are the ones the Rust service used
 * (snake_case, with the camelCase aliases it accepted on input). Fields that were not
 * {@code Option<_>} in Rust are required and may not be null.
 */
final class ShippingTypes {

  private ShippingTypes() {}

  record CartItem(
      @JsonProperty(value = "product_id", required = true)
          @JsonAlias("productId")
          @JsonSetter(nulls = Nulls.FAIL)
          String productId,
      @JsonProperty(value = "quantity", required = true) @JsonSetter(nulls = Nulls.FAIL)
          long quantity) {

    private static final long U32_MAX = 4_294_967_295L;

    CartItem {
      // serde deserialises this field into a u32.
      if (quantity < 0 || quantity > U32_MAX) {
        throw new IllegalArgumentException("invalid value: quantity " + quantity + ", expected u32");
      }
    }
  }

  record Address(
      @JsonProperty(value = "street_address", required = true)
          @JsonAlias("streetAddress")
          @JsonSetter(nulls = Nulls.FAIL)
          String streetAddress,
      @JsonProperty(value = "city", required = true) @JsonSetter(nulls = Nulls.FAIL) String city,
      @JsonProperty(value = "state", required = true) @JsonSetter(nulls = Nulls.FAIL) String state,
      @JsonProperty(value = "country", required = true) @JsonSetter(nulls = Nulls.FAIL)
          String country,
      @JsonProperty(value = "zip_code", required = true)
          @JsonAlias("zipCode")
          @JsonSetter(nulls = Nulls.FAIL)
          String zipCode) {}

  record GetQuoteRequest(
      @JsonProperty(value = "items", required = true) @JsonSetter(nulls = Nulls.FAIL)
          List<CartItem> items,
      @JsonProperty("address") Address address) {}

  record ShipOrderRequest(
      @JsonProperty("address") Address address,
      @JsonProperty(value = "items", required = true) @JsonSetter(nulls = Nulls.FAIL)
          List<CartItem> items) {}

  /** Money as serialised by the Rust service; {@code units} is a u64. */
  record Money(
      @JsonProperty("currency_code") String currencyCode,
      @JsonProperty("units") BigInteger units,
      @JsonProperty("nanos") int nanos) {}

  record GetQuoteResponse(@JsonProperty("cost_usd") Money costUsd) {}

  record ShipOrderResponse(@JsonProperty("tracking_id") String trackingId) {}

  /** A shipping cost split in whole dollars (u64) and cents (0-99). */
  record Quote(BigInteger dollars, int cents) {

    @Override
    public String toString() {
      return "%s.%02d".formatted(dollars, cents);
    }
  }
}
