/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import oteldemo.AdServiceGrpc.AdServiceBlockingStub;
import oteldemo.CartServiceGrpc.CartServiceBlockingStub;
import oteldemo.CheckoutServiceGrpc.CheckoutServiceBlockingStub;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.Demo.GetSupportedCurrenciesResponse;
import oteldemo.ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub;
import oteldemo.RecommendationServiceGrpc.RecommendationServiceBlockingStub;

/**
 * Tests that need the real servlet container (error dispatch, redirects as seen by a client,
 * the configured port), which MockMvc does not emulate.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none"
    })
@Import(AbstractFrontendTest.TestConfig.class)
class ServerTests {

  @LocalServerPort int port;


  @MockitoBean AdServiceBlockingStub ad;
  @MockitoBean CartServiceBlockingStub cart;
  @MockitoBean CheckoutServiceBlockingStub checkout;
  @MockitoBean CurrencyServiceBlockingStub currency;
  @MockitoBean ProductCatalogServiceBlockingStub productCatalog;
  @MockitoBean RecommendationServiceBlockingStub recommendation;

  private HttpResponse<String> send(String method, String path) throws Exception {
    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void unknownPageGetsTheHtml404PageWithStatus404() throws Exception {
    HttpResponse<String> response = send("GET", "/does-not-exist");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("text/html");
    assertThat(response.body())
        .contains("<title>Otel Demo - Page Not Found</title>")
        .contains("This page could not be found.");
  }

  @Test
  void unknownApiPathGetsTheHtml404PageToo() throws Exception {
    HttpResponse<String> response = send("GET", "/api/nope");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("This page could not be found.");
  }

  @Test
  void apiRoutesAnswerOverRealHttp() throws Exception {
    when(currency.getSupportedCurrencies(any()))
        .thenReturn(GetSupportedCurrenciesResponse.newBuilder().addCurrencyCodes("USD").build());
    HttpResponse<String> response = send("GET", "/api/currency");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/json");
    assertThat(response.body()).isEqualTo("[\"USD\"]");
  }

  @Test
  void trailingSlashOnAnApiRouteIsA308WithRelativeLocation() throws Exception {
    HttpResponse<String> response = send("GET", "/api/data/?contextKeys=x");
    assertThat(response.statusCode()).isEqualTo(308);
    assertThat(response.headers().firstValue("location")).hasValue("/api/data?contextKeys=x");
  }

  @Test
  void unsupportedMethodOnAnApiRouteIsAnEmpty405() throws Exception {
    HttpResponse<String> response = send("PUT", "/api/currency");
    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.body()).isEmpty();
  }
}
