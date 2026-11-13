/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"otel.traces.exporter=none", "otel.metrics.exporter=none", "otel.logs.exporter=none"})
class EmailControllerTests {

  private static final String ORDER =
      """
      {"order_id": "ord-77", "shipping_tracking_id": "TRK",
       "shipping_cost": {"currency_code": "USD", "units": 5, "nanos": 990000000},
       "shipping_address": {"street_address": "1 Main St", "city": "Mountain View",
         "state": "CA", "country": "United States", "zip_code": "94043"},
       "items": [{"item": {"product_id": "OLJCESPC7Z", "quantity": 2},
                  "cost": {"currency_code": "USD", "units": 67, "nanos": 980000000}}]}
      """;

  @LocalServerPort private int port;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private InMemoryMetricReader metrics;
  @Autowired private InMemoryProvider featureFlags;
  @Autowired private MailOutbox outbox;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setMemoryLeak(0);
    outbox.clear();
    spans.reset();
  }

  @Test
  void answersAnEmptyOkAndRecordsTheServerSpanAndCounter() throws Exception {
    long before = confirmations();

    HttpResponse<String> response = post("{\"email\": \"someone@example.com\", \"order\": " + ORDER + "}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue("Content-Type")).hasValue("text/html;charset=utf-8");

    SpanData server = spanNamed("POST /send_order_confirmation", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.order.id")))
        .isEqualTo("ord-77");
    SpanData sendEmail = spanNamed("send_email", SpanKind.INTERNAL);
    assertThat(sendEmail.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(sendEmail.getAttributes().get(AttributeKey.stringKey("demo.order.id")))
        .isEqualTo("ord-77");
    assertThat(confirmations()).isEqualTo(before + 1);
    assertThat(outbox.size()).isZero();
  }

  @Test
  void keepsTheEmailsWhileTheMemoryLeakFlagIsOn() throws Exception {
    setMemoryLeak(10);

    post("{\"email\": \"a@example.com\", \"order\": " + ORDER + "}");
    post("{\"email\": \"b@example.com\", \"order\": " + ORDER + "}");

    assertThat(outbox.deliveries()).hasSize(2);
    assertThat(outbox.deliveries().getFirst().to()).isEqualTo("a@example.com");
    assertThat(outbox.deliveries().getFirst().body().length())
        .isEqualTo(outbox.deliveries().getLast().body().length());
  }

  @Test
  void answers500AndRecordsTheExceptionForMalformedJson() throws Exception {
    long before = confirmations();

    HttpResponse<String> response = post("{not json");

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).isEmpty();
    SpanData server = spanNamed("POST /send_order_confirmation", SpanKind.SERVER);
    assertThat(server.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    assertThat(confirmations()).isEqualTo(before);
  }

  @Test
  void answers500ForAnEmptyBodyAndATrailingGarbageBody() throws Exception {
    assertThat(post("").statusCode()).isEqualTo(500);
    assertThat(post("{\"email\": \"a@b.c\", \"order\": " + ORDER + "} extra").statusCode())
        .isEqualTo(500);
  }

  @Test
  void answers500WithoutAnOrderAndDoesNotCount() throws Exception {
    long before = confirmations();

    assertThat(post("{\"email\": \"someone@example.com\"}").statusCode()).isEqualTo(500);
    assertThat(post("{\"email\": \"someone@example.com\", \"order\": null}").statusCode())
        .isEqualTo(500);
    assertThat(post("[]").statusCode()).isEqualTo(500);

    assertThat(confirmations()).isEqualTo(before);
  }

  @Test
  void answers500WhenTheTemplateCannotBeRendered() throws Exception {
    HttpResponse<String> response =
        post("{\"email\": \"a@b.c\", \"order\": {\"order_id\": \"o\"}}");

    assertThat(response.statusCode()).isEqualTo(500);
    SpanData sendEmail = spanNamed("send_email", SpanKind.INTERNAL);
    assertThat(sendEmail.getEvents()).anyMatch(event -> event.getName().equals("exception"));
  }

  @Test
  void answers500WithoutARecipient() throws Exception {
    assertThat(post("{\"order\": " + ORDER + "}").statusCode()).isEqualTo(500);
    assertThat(outbox.size()).isZero();
  }

  @Test
  void countsTheConfirmationEvenWhenTheSendFails() throws Exception {
    long before = confirmations();

    assertThat(post("{\"order\": " + ORDER + "}").statusCode()).isEqualTo(500);

    assertThat(confirmations()).isEqualTo(before + 1);
  }

  @Test
  void answersTheSinatraNotFoundPageForOtherRoutes() throws Exception {
    HttpResponse<String> unknown = send(HttpRequest.newBuilder(uri("/")).GET());
    HttpResponse<String> wrongVerb =
        send(HttpRequest.newBuilder(uri("/send_order_confirmation")).GET());

    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(unknown.body()).isEqualTo("<h1>Not Found</h1>");
    assertThat(wrongVerb.statusCode()).isEqualTo(404);
    assertThat(wrongVerb.body()).isEqualTo("<h1>Not Found</h1>");
  }

  private HttpResponse<String> post(String body) throws IOException, InterruptedException {
    return send(
        HttpRequest.newBuilder(uri("/send_order_confirmation"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  // The server span ends after the client already has its response, so give it a moment.
  private SpanData spanNamed(String name, SpanKind kind) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> findSpan(name, kind), Optional::isPresent)
        .orElseThrow();
  }

  private Optional<SpanData> findSpan(String name, SpanKind kind) {
    return spans.getFinishedSpanItems().stream()
        .filter(span -> span.getName().equals(name) && span.getKind() == kind)
        .findFirst();
  }

  private long confirmations() {
    return metrics.collectAllMetrics().stream()
        .filter(metric -> metric.getName().equals("demo.notification.confirmations"))
        .flatMap(metric -> metric.getLongSumData().getPoints().stream())
        .mapToLong(LongPointData::getValue)
        .sum();
  }

  private void setMemoryLeak(double multiplier) {
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put(
        "emailMemoryLeak",
        Flag.<Double>builder().variant("v", multiplier).defaultVariant("v").build());
    featureFlags.updateFlags(flags);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    InMemoryMetricReader inMemoryMetricReader() {
      return InMemoryMetricReader.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(
        InMemorySpanExporter spanExporter, InMemoryMetricReader metricReader) {
      return customizer ->
          customizer
              .addTracerProviderCustomizer(
                  (tracerProvider, config) ->
                      tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addMeterProviderCustomizer(
                  (meterProvider, config) -> meterProvider.registerMetricReader(metricReader));
    }

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }
  }
}
