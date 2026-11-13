/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"otel.traces.exporter=none", "otel.metrics.exporter=none", "otel.logs.exporter=none"})
class QuoteControllerTests {

  private static final AttributeKey<Long> ITEMS_COUNT = AttributeKey.longKey("demo.shipping.quote.items_count");
  private static final AttributeKey<Double> COST_TOTAL = AttributeKey.doubleKey("demo.shipping.quote.cost.total");

  @Value("${local.server.port}")
  int port;

  @Autowired InMemorySpanExporter spans;
  @Autowired InMemoryMetricReader metrics;
  @Autowired InMemoryLogRecordExporter logRecords;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void resetSpans() {
    spans.reset();
    logRecords.reset();
  }

  @Test
  void quotesThreeItems() throws Exception {
    HttpResponse<String> response = post("application/json", "{\"numberOfItems\":3}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(response.body()).isEqualTo("26.97");
  }

  @Test
  void quotesOneItemAndManyItems() throws Exception {
    assertThat(post("application/json", "{\"numberOfItems\":1}").body()).isEqualTo("8.99");
    assertThat(post("application/json", "{\"numberOfItems\":100}").body()).isEqualTo("899");
    assertThat(post("application/json", "{\"numberOfItems\":-2}").body()).isEqualTo("-17.98");
  }

  @Test
  void zeroItemsGiveZeroWithoutDecimalPoint() throws Exception {
    assertThat(post("application/json", "{\"numberOfItems\":0}").body()).isEqualTo("0");
  }

  @Test
  void convertsTheItemCountLikePhpIntval() throws Exception {
    assertThat(post("application/json", "{\"numberOfItems\":\"3\"}").body()).isEqualTo("26.97");
    assertThat(post("application/json", "{\"numberOfItems\":3.9}").body()).isEqualTo("26.97");
    assertThat(post("application/json", "{\"numberOfItems\":\"abc\"}").body()).isEqualTo("0");
    assertThat(post("application/json", "{\"numberOfItems\":null}").body()).isEqualTo("0");
    assertThat(post("application/json", "{\"numberOfItems\":true}").body()).isEqualTo("8.99");
  }

  @Test
  void acceptsFormEncodedBodies() throws Exception {
    assertThat(post("application/x-www-form-urlencoded", "numberOfItems=2").body()).isEqualTo("17.98");
  }

  @Test
  void missingItemCountIsRecordedOnTheChildSpanAndGivesZero() throws Exception {
    HttpResponse<String> response = post("application/json", "{\"other\":1}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("0");

    SpanData child = spanNamed("calculate-quote");
    assertThat(child.getEvents()).extracting(EventData::getName).contains("Calculating quote", "exception");
    assertThat(child.getEvents())
        .filteredOn(event -> event.getName().equals("exception"))
        .first()
        .satisfies(
            event ->
                assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.message")))
                    .isEqualTo("numberOfItems not provided"));
    assertThat(child.getAttributes().get(ITEMS_COUNT)).isNull();
  }

  @Test
  void unparseableBodiesGiveZeroWithoutRecordingAnException() throws Exception {
    assertThat(post("application/json", "not json").body()).isEqualTo("0");
    assertThat(post("application/json", "{\"numberOfItems\":3} trailing").body()).isEqualTo("0");
    assertThat(post("application/json", "5").body()).isEqualTo("0");
    assertThat(post("text/plain", "{\"numberOfItems\":3}").body()).isEqualTo("0");
    assertThat(post("application/xml", "<a><numberOfItems>3</numberOfItems></a>").body()).isEqualTo("0");
    HttpResponse<String> noContentType = rawPost(null, "{\"numberOfItems\":3}");
    assertThat(noContentType.statusCode()).isEqualTo(200);
    assertThat(noContentType.body()).isEqualTo("0");

    await().atMost(5, TimeUnit.SECONDS).until(() -> childSpans().size() >= 6);
    // The PHP service swallows the TypeError, so nothing is recorded for these requests.
    assertThat(childSpans())
        .allSatisfy(
            span -> {
              assertThat(span.getEvents()).extracting(EventData::getName).containsExactly("Calculating quote");
              assertThat(span.getAttributes().isEmpty()).isTrue();
            });
  }

  @Test
  void emitsServerSpanEventsChildSpanAttributesAndCounter() throws Exception {
    long before = quotesCounted(3);

    post("application/json", "{\"numberOfItems\":3}");

    SpanData server = awaitSpan(span -> span.getKind() == SpanKind.SERVER);
    assertThat(server.getName()).isEqualTo("POST /getquote");
    assertThat(server.getEvents())
        .extracting(EventData::getName)
        .contains("Received get quote request, processing it", "Quote processed, response sent back");
    EventData processed =
        server.getEvents().stream()
            .filter(event -> event.getName().equals("Quote processed, response sent back"))
            .findFirst()
            .orElseThrow();
    assertThat(processed.getAttributes().get(COST_TOTAL)).isEqualTo(26.97);

    SpanData child = spanNamed("calculate-quote");
    assertThat(child.getKind()).isEqualTo(SpanKind.INTERNAL);
    assertThat(child.getInstrumentationScopeInfo().getName()).isEqualTo("manual-instrumentation");
    assertThat(child.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(child.getEvents())
        .extracting(EventData::getName)
        .containsExactly("Calculating quote", "Quote calculated, returning its value");
    assertThat(child.getAttributes().get(ITEMS_COUNT)).isEqualTo(3L);
    assertThat(child.getAttributes().get(COST_TOTAL)).isEqualTo(26.97);

    assertThat(quotesCounted(3)).isEqualTo(before + 1);
  }

  @Test
  void exportsTheCalculatedQuoteLogRecord() throws Exception {
    post("application/json", "{\"numberOfItems\":3}");

    LogRecordData record =
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(
                () -> logRecords.getFinishedLogRecordItems().stream().filter(r -> "Calculated quote".equals(r.getBodyValue() == null ? null : r.getBodyValue().asString())).findFirst(),
                Optional::isPresent)
            .orElseThrow();
    assertThat(record.getInstrumentationScopeInfo().getName()).isEqualTo("slim-app");
    assertThat(record.getSeverityText()).isEqualTo("INFO");
    assertThat(record.getAttributes().get(AttributeKey.doubleKey("total"))).isEqualTo(26.97);
  }

  @Test
  void unknownRouteAndWrongMethodKeepSlimStatusCodes() throws Exception {
    HttpResponse<String> notFound =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/nothing")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(notFound.statusCode()).isEqualTo(404);

    HttpResponse<String> wrongMethod =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/getquote")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(wrongMethod.statusCode()).isEqualTo(405);
  }

  private HttpResponse<String> post(String contentType, String body) throws IOException, InterruptedException {
    return rawPost(contentType, body);
  }

  private HttpResponse<String> rawPost(String contentType, String body) throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/getquote"))
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private List<SpanData> childSpans() {
    return spans.getFinishedSpanItems().stream().filter(span -> span.getName().equals("calculate-quote")).toList();
  }

  private SpanData spanNamed(String name) {
    return awaitSpan(span -> span.getName().equals(name));
  }

  private SpanData awaitSpan(java.util.function.Predicate<SpanData> predicate) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> spans.getFinishedSpanItems().stream().filter(predicate).findFirst(), Optional::isPresent)
        .orElseThrow();
  }

  private long quotesCounted(long items) {
    return metrics.collectAllMetrics().stream()
        .filter(metric -> metric.getName().equals("quotes"))
        .peek(
            metric -> {
              assertThat(metric.getUnit()).isEqualTo("quotes");
              assertThat(metric.getDescription()).isEqualTo("number of quotes calculated");
              assertThat(metric.getInstrumentationScopeInfo().getName()).isEqualTo("quotes");
            })
        .flatMap(metric -> metric.getLongSumData().getPoints().stream())
        .filter(point -> Long.valueOf(items).equals(point.getAttributes().get(AttributeKey.longKey("number_of_items"))))
        .mapToLong(LongPointData::getValue)
        .sum();
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
    InMemoryLogRecordExporter inMemoryLogRecordExporter() {
      return InMemoryLogRecordExporter.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(
        InMemorySpanExporter spanExporter,
        InMemoryMetricReader metricReader,
        InMemoryLogRecordExporter logExporter) {
      return customizer ->
          customizer
              .addTracerProviderCustomizer(
                  (tracerProvider, config) -> tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addMeterProviderCustomizer((meterProvider, config) -> meterProvider.registerMetricReader(metricReader))
              .addLoggerProviderCustomizer(
                  (loggerProvider, config) ->
                      loggerProvider.addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter)));
    }
  }
}
