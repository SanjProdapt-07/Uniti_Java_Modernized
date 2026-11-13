/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** What Envoy needs: the app answers at "/" (it rewrites /loadgen/ to /) and the page uses relative URLs. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"otel.traces.exporter=none", "otel.metrics.exporter=none", "otel.logs.exporter=none"})
class UiPageTests {

  @LocalServerPort private int port;

  private HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rootServesTheUiForEnvoyRewrittenLoadgenPath() throws Exception {
    HttpResponse<String> response = get("/");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
    assertThat(response.body()).contains("fetch('swarm'").contains("fetch('stats/requests')").doesNotContain("fetch('/");
  }

  @Test
  void statsAreServedAtTheRewrittenPath() throws Exception {
    HttpResponse<String> response = get("/stats/requests");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"state\":\"ready\"").contains("\"user_count\":0");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }
  }
}
