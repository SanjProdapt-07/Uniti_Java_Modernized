/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import oteldemo.Demo.Address;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.Money;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;

/** Tests of the Go-compatibility helpers: error text, JSON payloads, v1 UUIDs, amount floats. */
class SupportTests {

  @Test
  void describesGrpcFailuresLikeGrpcGo() {
    assertThat(GoErrors.describe(Status.UNAVAILABLE.withDescription("cart down").asRuntimeException()))
        .isEqualTo("rpc error: code = Unavailable desc = cart down");
    assertThat(GoErrors.describe(Status.DEADLINE_EXCEEDED.asRuntimeException()))
        .isEqualTo("rpc error: code = DeadlineExceeded desc = ");
    assertThat(GoErrors.describe(Status.CANCELLED.withDescription("x").asRuntimeException()))
        .isEqualTo("rpc error: code = Canceled desc = x");
    assertThat(GoErrors.describe(new IllegalStateException("boom"))).isEqualTo("boom");
  }

  @Test
  void quotesLikeGoPercentQ() {
    assertThat(GoErrors.quote("OLJCESPC7Z")).isEqualTo("\"OLJCESPC7Z\"");
    assertThat(GoErrors.quote("a\"b\\c\n")).isEqualTo("\"a\\\"b\\\\c\\n\"");
  }

  @Test
  void generatesVersion1Uuids() {
    UUID first = UuidV1.next();
    UUID second = UuidV1.next();

    assertThat(first.version()).isEqualTo(1);
    assertThat(first.variant()).isEqualTo(2);
    assertThat(second).isNotEqualTo(first);
    assertThat(second.timestamp()).isGreaterThan(first.timestamp());
    assertThat(first.toString()).matches("[0-9a-f]{8}-[0-9a-f]{4}-1[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    // the UUID timestamp is the current time (100ns since 1582-10-15)
    long epochMillis = (first.timestamp() - 122_192_928_000_000_000L) / 10_000;
    assertThat(epochMillis).isBetween(System.currentTimeMillis() - 5_000, System.currentTimeMillis() + 5_000);
  }

  @Test
  void rendersAmountsWithTwoDecimalsLikeTheOriginal() {
    assertThat(JsonPayloads.amount(money("USD", 8, 990_000_000))).isEqualTo(8.99);
    assertThat(JsonPayloads.amount(money("USD", 93, 970_000_000))).isEqualTo(93.97);
    // only the first two decimals are kept (nanos / 10_000_000)
    assertThat(JsonPayloads.amount(money("USD", 1, 999_999_999))).isEqualTo(1.99);
    assertThat(JsonPayloads.amount(money("USD", 0, 0))).isEqualTo(0.0);
    assertThat(JsonPayloads.amount(money("USD", 5, 50_000_000))).isEqualTo(5.05);
  }

  @Test
  void omitsEmptyFieldsLikeGoOmitempty() {
    Address address = Address.newBuilder().setStreetAddress("1600 Amphitheatre").setCity("Mountain View").build();

    assertThat(JsonPayloads.address(address))
        .containsExactly(
            Map.entry("street_address", "1600 Amphitheatre"), Map.entry("city", "Mountain View"));
    assertThat(JsonPayloads.money(money("USD", 0, 0))).containsExactly(Map.entry("currency_code", "USD"));
    assertThat(JsonPayloads.money(money("USD", 3, 5)))
        .containsExactly(
            Map.entry("currency_code", "USD"), Map.entry("units", 3L), Map.entry("nanos", 5));
    assertThat(JsonPayloads.cartItem(CartItem.newBuilder().setProductId("P").build()))
        .containsExactly(Map.entry("product_id", "P"));
  }

  @Test
  void shippingPayloadHasNullsForNilAddressAndEmptyCart() {
    Map<String, Object> empty = JsonPayloads.addressAndItems(null, List.of());

    assertThat(empty).containsEntry("address", null).containsEntry("items", null);

    Map<String, Object> full =
        JsonPayloads.addressAndItems(
            Address.newBuilder().setCountry("US").build(),
            List.of(CartItem.newBuilder().setProductId("P").setQuantity(2).build()));
    assertThat(full.get("address")).isEqualTo(Map.of("country", "US"));
    assertThat(full.get("items")).isEqualTo(List.of(Map.of("product_id", "P", "quantity", 2)));
  }

  @Test
  void orderConfirmationPayloadCarriesTheWholeOrder() {
    OrderResult order =
        OrderResult.newBuilder()
            .setOrderId("o-1")
            .setShippingTrackingId("t-1")
            .setShippingCost(money("USD", 8, 990_000_000))
            .setShippingAddress(Address.newBuilder().setCity("Zurich"))
            .addItems(
                OrderItem.newBuilder()
                    .setItem(CartItem.newBuilder().setProductId("P").setQuantity(2))
                    .setCost(money("USD", 19, 990_000_000)))
            .build();

    Map<String, Object> payload = JsonPayloads.orderConfirmation("someone@example.com", order);

    assertThat(payload).containsEntry("email", "someone@example.com");
    assertThat(payload.get("order"))
        .isEqualTo(
            Map.of(
                "order_id", "o-1",
                "shipping_tracking_id", "t-1",
                "shipping_cost", Map.of("currency_code", "USD", "units", 8L, "nanos", 990_000_000),
                "shipping_address", Map.of("city", "Zurich"),
                "items",
                    List.of(
                        Map.of(
                            "item", Map.of("product_id", "P", "quantity", 2),
                            "cost",
                                Map.of(
                                    "currency_code", "USD", "units", 19L, "nanos", 990_000_000)))));
    // the email key is always present, even when empty
    assertThat(JsonPayloads.orderConfirmation("", OrderResult.getDefaultInstance())).containsEntry("email", "");
  }

  private static Money money(String currency, long units, int nanos) {
    return Money.newBuilder().setCurrencyCode(currency).setUnits(units).setNanos(nanos).build();
  }
}
