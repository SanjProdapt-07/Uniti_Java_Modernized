/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import java.math.BigInteger;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import oteldemo.Demo.Address;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.Money;

/**
 * Client of the HTTP shipping service ({@code POST /get-quote} and {@code POST /ship-order}).
 * Error texts are those of the original Go implementation.
 */
class ShippingClient {

  private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
      new ParameterizedTypeReference<>() {};

  private final RestClient restClient;
  private final String baseUrl;

  ShippingClient(RestClient restClient, String baseUrl) {
    this.restClient = restClient;
    this.baseUrl = baseUrl;
  }

  /** Asks for the shipping quote in USD. */
  Money quoteShipping(Address address, List<CartItem> items) {
    Map<String, Object> body = post("/get-quote", JsonPayloads.addressAndItems(address, items));
    if (body == null) {
      throw new StepFailure("failed to unmarshal shipping quote: unexpected end of JSON input");
    }
    Object costUsd = body.get("cost_usd");
    if (costUsd == null) {
      throw new StepFailure("shipping quote missing cost_usd field");
    }
    return parseMoney(costUsd);
  }

  /** Ships the order and returns the tracking id. */
  String shipOrder(Address address, List<CartItem> items) {
    Map<String, Object> body = post("/ship-order", JsonPayloads.addressAndItems(address, items));
    Object trackingId = body == null ? null : body.get("tracking_id");
    if (body == null) {
      throw new StepFailure("failed to unmarshal ship order response: unexpected end of JSON input");
    }
    if (trackingId != null && !(trackingId instanceof String)) {
      throw new StepFailure(
          "failed to unmarshal ship order response: json: cannot unmarshal into Go struct field"
              + " .tracking_id of type string");
    }
    if (trackingId == null || ((String) trackingId).isEmpty()) {
      throw new StepFailure("ship order response missing tracking_id field");
    }
    return (String) trackingId;
  }

  private Map<String, Object> post(String path, Map<String, Object> payload) {
    String url = baseUrl + path;
    try {
      return restClient
          .post()
          .uri(URI.create(url))
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status != 200) {
                  throw new StepFailure(
                      "failed POST to shipping service: expected 200, got " + status);
                }
                try {
                  return response.bodyTo(JSON_OBJECT);
                } catch (RestClientException e) {
                  throw new StepFailure(
                      "failed to unmarshal "
                          + ("/get-quote".equals(path) ? "shipping quote" : "ship order response")
                          + ": "
                          + e.getMessage());
                }
              });
    } catch (StepFailure e) {
      throw e;
    } catch (RestClientException e) {
      throw new StepFailure("failed POST to shipping service: " + goUrlError(url, e), e);
    }
  }

  static String goUrlError(String url, Throwable e) {
    Throwable root = e.getCause() != null ? e.getCause() : e;
    return "Post \"" + url + "\": " + root;
  }

  /** json.Unmarshal into pb.Money: currency_code string, units int64, nanos int32. */
  @SuppressWarnings("unchecked")
  private static Money parseMoney(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new StepFailure(
          "failed to unmarshal shipping quote: json: cannot unmarshal into Go struct field"
              + " .cost_usd of type oteldemo.Money");
    }
    Map<String, Object> json = (Map<String, Object>) raw;
    Money.Builder money = Money.newBuilder();
    Object currency = json.get("currency_code");
    if (currency != null) {
      if (!(currency instanceof String code)) {
        throw unmarshal("currency_code", "string");
      }
      money.setCurrencyCode(code);
    }
    Object units = json.get("units");
    if (units != null) {
      money.setUnits(integer(units, "units", "int64", Long.MIN_VALUE, Long.MAX_VALUE));
    }
    Object nanos = json.get("nanos");
    if (nanos != null) {
      money.setNanos((int) integer(nanos, "nanos", "int32", Integer.MIN_VALUE, Integer.MAX_VALUE));
    }
    return money.build();
  }

  private static long integer(Object value, String field, String type, long min, long max) {
    if (value instanceof Integer || value instanceof Long) {
      long number = ((Number) value).longValue();
      if (number >= min && number <= max) {
        return number;
      }
    } else if (value instanceof BigInteger) {
      // outside the long range, so cannot be an int64
      throw unmarshal(field, type);
    }
    throw unmarshal(field, type);
  }

  private static StepFailure unmarshal(String field, String type) {
    return new StepFailure(
        "failed to unmarshal shipping quote: json: cannot unmarshal into Go struct field"
            + " Money."
            + field
            + " of type "
            + type);
  }
}
