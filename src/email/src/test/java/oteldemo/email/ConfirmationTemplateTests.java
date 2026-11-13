/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import oteldemo.email.ConfirmationTemplate.TemplateRenderException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConfirmationTemplateTests {

  private static final String HEAD =
      String.join(
          "\n",
          "<!DOCTYPE html>",
          "<!--",
          " Copyright 2020 Google LLC",
          "",
          " Licensed under the Apache License, Version 2.0 (the \"License\");",
          " you may not use this file except in compliance with the License.",
          " You may obtain a copy of the License at",
          "",
          "      http://www.apache.org/licenses/LICENSE-2.0",
          "",
          " Unless required by applicable law or agreed to in writing, software",
          " distributed under the License is distributed on an \"AS IS\" BASIS,",
          " WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
          " See the License for the specific language governing permissions and",
          " limitations under the License.",
          "-->",
          "",
          "<html>",
          "  <head>",
          "    <title>Your Order Confirmation</title>",
          "    <link href=\"https://fonts.googleapis.com/css2?family=DM+Sans:ital,wght@0,400;0,700;1,400;1,700&display=swap\" rel=\"stylesheet\">",
          "  </head>",
          "  <style>",
          "    body{",
          "      font-family: 'DM Sans', sans-serif;",
          "    }",
          "  </style>",
          "  <body>",
          "    <h2>Your Order Confirmation</h2>",
          "    <p>Thanks for shopping with us!<p>",
          "    <h3>Order ID</h3>",
          "");

  private static final String TABLE_HEAD =
      String.join(
          "\n",
          "    <h3>Items</h3>",
          "    <table style=\"width:100%\">",
          "        <tr>",
          "          <th>Item No.</th>",
          "          <th>Quantity</th>",
          "          <th>Price</th>",
          "        </tr>",
          // The loop tag is indented, so Tilt's trim mode keeps its line: 8 spaces precede the rows.
          "        ");

  private static final String TAIL = String.join("\n", "", "    </table>", "  </body>", "</html>", "");

  private final ConfirmationTemplate template = new ConfirmationTemplate();

  @Test
  void rendersTheOrderExactlyAsSinatraDid() {
    JsonNode order =
        json(
            """
            {"order_id": "3f1c", "shipping_tracking_id": "TRK-9",
             "shipping_cost": {"currency_code": "USD", "units": 5, "nanos": 990000000},
             "shipping_address": {"street_address": "1600 Amphitheatre Parkway",
               "city": "Mountain View", "state": "CA", "country": "United States",
               "zip_code": "94043"},
             "items": [
               {"item": {"product_id": "OLJCESPC7Z", "quantity": 2},
                "cost": {"currency_code": "USD", "units": 67, "nanos": 980000000}},
               {"item": {"product_id": "66VCHSJNUP", "quantity": 1},
                "cost": {"currency_code": "USD", "units": 349, "nanos": 950000000}}]}
            """);

    String expected =
        HEAD
            + "    <p>3f1c</p>\n"
            + "    <h3>Shipping</h3>\n"
            + "    <p>TRK-9</p>\n"
            + "    <p>5.990000000 USD</p>\n"
            // The template reads street_address_1/_2, which the proto does not have: both are empty
            + "    <p>, , Mountain View, United States 94043</p>\n"
            + TABLE_HEAD
            + row("OLJCESPC7Z", "2", "67.980000000 USD")
            + row("66VCHSJNUP", "1", "349.950000000 USD")
            + TAIL;

    assertThat(template.render(order)).isEqualTo(expected);
  }

  @Test
  void rendersNoRowsForAnEmptyItemList() {
    String rendered = template.render(json(minimalOrder("[]")));

    assertThat(rendered)
        .contains(TABLE_HEAD + String.join("\n", "", "    </table>"))
        .doesNotContain("<td>");
  }

  @Test
  void printsMissingFieldsAsEmptyText() {
    // Go's JSON encoding omits zero values, so a free shipping cost has no units or nanos
    JsonNode order =
        json(
            """
            {"shipping_cost": {"currency_code": "USD"}, "shipping_address": {},
             "items": [{"item": {"product_id": "P1"}, "cost": {"units": 3}}]}
            """);

    String rendered = template.render(order);

    assertThat(rendered)
        .contains("    <p></p>\n    <h3>Shipping</h3>\n    <p></p>\n    <p>. USD</p>\n")
        .contains("    <p>, , ,  </p>\n")
        .contains("            <td>P1</td>\n            <td></td>\n            <td>3. </td>\n");
  }

  @Test
  void doesNotEscapeHtml() {
    JsonNode order = json(minimalOrder("[]").replace("\"o1\"", "\"<b>&</b>\""));

    assertThat(template.render(order)).contains("<p><b>&</b></p>");
  }

  @Test
  void failsLikeNoMethodErrorWhenAnObjectIsMissing() {
    assertThatThrownBy(() -> template.render(json("{\"order_id\": \"x\", \"items\": []}")))
        .isInstanceOf(TemplateRenderException.class)
        .hasMessageContaining("units")
        .hasMessageContaining("nil");

    String noItems =
        "{\"shipping_cost\": {}, \"shipping_address\": {}}";
    assertThatThrownBy(() -> template.render(json(noItems)))
        .isInstanceOf(TemplateRenderException.class)
        .hasMessageContaining("each");

    String noItemDetails =
        "{\"shipping_cost\": {}, \"shipping_address\": {}, \"items\": [{\"cost\": {}}]}";
    assertThatThrownBy(() -> template.render(json(noItemDetails)))
        .isInstanceOf(TemplateRenderException.class)
        .hasMessageContaining("product_id");
  }

  private static String row(String productId, String quantity, String cost) {
    return String.join(
        "\n",
        "",
        "          <tr>",
        "            <td>" + productId + "</td>",
        "            <td>" + quantity + "</td>",
        "            <td>" + cost + "</td>",
        "          </tr>",
        "        ");
  }

  private static String minimalOrder(String items) {
    return "{\"order_id\": \"o1\", \"shipping_tracking_id\": \"t1\","
        + " \"shipping_cost\": {\"currency_code\": \"USD\", \"units\": 1, \"nanos\": 2},"
        + " \"shipping_address\": {\"city\": \"c\", \"country\": \"k\", \"zip_code\": \"z\"},"
        + " \"items\": "
        + items
        + "}";
  }

  private static JsonNode json(String text) {
    return JsonMapper.builder().build().readTree(text);
  }
}
