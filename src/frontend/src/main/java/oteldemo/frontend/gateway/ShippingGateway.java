/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import oteldemo.Demo.Money;
import tools.jackson.databind.JsonNode;

/**
 * Port of {@code gateways/http/Shipping.gateway.ts}: the shipping service is an HTTP service
 * ({@code POST {SHIPPING_ADDR}/get-quote}) with a snake_case JSON contract.
 */
@Component
public class ShippingGateway {

  private final RestClient restClient;

  public ShippingGateway(
      RestClient.Builder builder, @Value("${shipping.addr:}") String shippingAddr) {
    this.restClient = builder.baseUrl(shippingAddr).build();
  }

  /**
   * Requests a quote.
   *
   * @param items one entry per cart line with {@code productId} and {@code quantity}
   * @param address camelCase address as received by the API
   * @return the USD cost, or {@code null} when the service answered without {@code cost_usd}
   */
  public Money getShippingCost(List<Map<String, Object>> items, Map<String, Object> address) {
    Map<String, Object> body = new LinkedHashMap<>();
    List<Map<String, Object>> quoteItems = new ArrayList<>();
    for (Map<String, Object> item : items) {
      Map<String, Object> quoteItem = new LinkedHashMap<>();
      quoteItem.put("product_id", item.get("productId"));
      quoteItem.put("quantity", item.get("quantity"));
      quoteItems.add(quoteItem);
    }
    Map<String, Object> quoteAddress = new LinkedHashMap<>();
    quoteAddress.put("street_address", address.get("streetAddress"));
    quoteAddress.put("city", address.get("city"));
    quoteAddress.put("state", address.get("state"));
    quoteAddress.put("country", address.get("country"));
    quoteAddress.put("zip_code", address.get("zipCode"));
    body.put("items", quoteItems);
    body.put("address", quoteAddress);

    JsonNode data;
    try {
      data =
          restClient
              .post()
              .uri("/get-quote")
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(JsonNode.class);
    } catch (RestClientResponseException e) {
      throw new IllegalStateException(
          "HTTP error: "
              + e.getStatusCode().value()
              + " "
              + e.getStatusText()
              + " - "
              + e.getResponseBodyAsString(),
          e);
    }
    JsonNode cost = data == null ? null : data.get("cost_usd");
    if (cost == null || cost.isNull() || cost.isMissingNode()) {
      return null;
    }
    return Money.newBuilder()
        .setCurrencyCode(cost.path("currency_code").asString(""))
        .setUnits(cost.path("units").asLong(0))
        .setNanos(cost.path("nanos").asInt(0))
        .build();
  }
}
