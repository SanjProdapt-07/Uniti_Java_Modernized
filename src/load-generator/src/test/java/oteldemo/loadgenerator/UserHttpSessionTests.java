/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserHttpSessionTests {

  private FakeSite site;
  private TestTelemetry telemetry;
  private UserHttpSession session;

  @BeforeEach
  void setUp() throws IOException {
    site = new FakeSite();
    telemetry = new TestTelemetry();
    session = telemetry.session(site.url());
  }

  @AfterEach
  void tearDown() {
    site.close();
    telemetry.close();
  }

  @Test
  void getsRelativePathsAgainstHostWithPythonRequestsHeaders() {
    UserHttpSession.Response response = session.get("/api/cart");

    assertThat(response.ok()).isTrue();
    FakeSite.Recorded request = site.requests().get(0);
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.uri()).isEqualTo("/api/cart");
    assertThat(request.header("User-Agent")).isEqualTo("python-requests/2.32.5");
    assertThat(request.header("Accept")).isEqualTo("*/*");
    assertThat(request.header("Accept-Encoding")).isEqualTo("gzip, deflate");
  }

  @Test
  void encodesQueryParametersAndDropsNullValues() {
    session.get("/api/recommendations", Map.of("productIds", List.of("0PUK6V6EV0")));
    session.get("/api/data/", Map.of("contextKeys", Arrays.asList((String) null)));
    Map<String, List<String>> params = new LinkedHashMap<>();
    params.put("contextKeys", List.of("a b"));
    session.get("/api/data/", params);

    assertThat(site.requests()).extracting(FakeSite.Recorded::uri)
        .containsExactly("/api/recommendations?productIds=0PUK6V6EV0", "/api/data/", "/api/data/?contextKeys=a+b");
  }

  @Test
  void postsJsonBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("userId", "u1");
    body.put("n", 3);
    session.post("/api/cart", body);

    FakeSite.Recorded request = site.requests().get(0);
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.header("Content-Type")).isEqualTo("application/json");
    assertThat(request.body()).isEqualTo("{\"userId\":\"u1\",\"n\":3}");
  }

  @Test
  void absoluteUrlsBypassTheHost() throws IOException {
    try (FakeSite agent = new FakeSite()) {
      session.post(agent.url() + "/prompt", Map.of("message", "hi"));
      assertThat(agent.requests()).hasSize(1);
      assertThat(site.requests()).isEmpty();
    }
  }

  @Test
  void recordsStatsNamedByPathAndQueryOfTheFirstRequest() {
    site.reply("/missing", new FakeSite.Reply(404, "nope", Map.of()));
    session.get("/api/products/X");
    session.get("/missing");

    RequestStats stats = telemetry.stats;
    assertThat(stats.entries()).extracting(RequestStats.Entry::name).containsExactly("/api/products/X", "/missing");
    assertThat(stats.total().numRequests()).isEqualTo(2);
    assertThat(stats.total().numFailures()).isEqualTo(1);
    assertThat(stats.errors()).singleElement().satisfies(e -> assertThat(e.error()).startsWith("404 Not Found"));
  }

  @Test
  void connectionErrorsAreFailuresWithStatusZero() throws IOException {
    FakeSite dead = new FakeSite();
    String url = dead.url();
    dead.close();

    UserHttpSession.Response response = telemetry.session(url).get("/");

    assertThat(response.status()).isZero();
    assertThat(response.ok()).isFalse();
    assertThat(telemetry.stats.total().numFailures()).isEqualTo(1);
  }

  @Test
  void keepsCookiesPerSession() {
    site.reply("/login", new FakeSite.Reply(200, "ok", Map.of("Set-Cookie", "session-id=abc123; Path=/")));
    session.get("/login");
    session.get("/api/cart");
    telemetry.session(site.url()).get("/api/cart");

    List<FakeSite.Recorded> requests = site.requests();
    assertThat(requests.get(0).header("Cookie")).isNull();
    assertThat(requests.get(1).header("Cookie")).isEqualTo("session-id=abc123");
    assertThat(requests.get(2).header("Cookie")).isNull();
  }

  @Test
  void followsRedirectsAndNamesStatsByFirstRequest() {
    site.reply("/old", new FakeSite.Reply(302, "", Map.of("Location", "/new")));
    session.get("/old");

    assertThat(site.requests()).extracting(FakeSite.Recorded::path).containsExactly("/old", "/new");
    assertThat(telemetry.stats.entries()).extracting(RequestStats.Entry::name).containsExactly("/old");
    assertThat(telemetry.stats.total().numFailures()).isZero();
  }

  @Test
  void postRedirectedWith302BecomesGet() {
    site.reply("/submit", new FakeSite.Reply(302, "", Map.of("Location", "/done")));
    session.post("/submit", Map.of("a", 1));

    assertThat(site.requests()).extracting(FakeSite.Recorded::method).containsExactly("POST", "GET");
  }

  @Test
  void clientSpanCarriesTraceContextAndBaggageHeaders() {
    Baggage baggage = Baggage.builder().put("session.id", "s-1").put("synthetic_request", "true").build();
    try (Scope ignored = Context.current().with(baggage).makeCurrent()) {
      session.get("/");
    }

    FakeSite.Recorded request = site.requests().get(0);
    assertThat(request.header("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[0-3]");
    assertThat(request.header("baggage")).contains("session.id=s-1").contains("synthetic_request=true");
    List<SpanData> clientSpans = telemetry.spanData().stream().filter(s -> s.getKind() == SpanKind.CLIENT).toList();
    assertThat(clientSpans).hasSize(1);
  }

  @Test
  void requestMetricsAreRecorded() {
    session.get("/");
    assertThat(telemetry.metrics.collectAllMetrics())
        .extracting(m -> m.getName())
        .contains("load_generator.requests", "load_generator.request.duration");
  }
}
