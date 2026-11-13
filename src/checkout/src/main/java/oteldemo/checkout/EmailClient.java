/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import oteldemo.Demo.OrderResult;

/**
 * Client of the HTTP email service ({@code POST /send_order_confirmation}). The request is
 * bounded to {@link #REQUEST_TIMEOUT}, as {@code emailRequestTimeout} was in the Go service.
 */
class EmailClient {

  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);

  private final RestClient restClient;
  private final String baseUrl;

  EmailClient(RestClient restClient, String baseUrl) {
    this.restClient = restClient;
    this.baseUrl = baseUrl;
  }

  void sendOrderConfirmation(String email, OrderResult order) {
    String url = baseUrl + "/send_order_confirmation";
    try {
      restClient
          .post()
          .uri(URI.create(url))
          .contentType(MediaType.APPLICATION_JSON)
          .body(JsonPayloads.orderConfirmation(email, order))
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status != 200) {
                  throw new StepFailure(
                      "failed POST to email service: expected 200, got " + status);
                }
                return null;
              });
    } catch (StepFailure e) {
      throw e;
    } catch (RestClientException e) {
      throw new StepFailure("failed POST to email service: " + ShippingClient.goUrlError(url, e), e);
    }
  }
}
