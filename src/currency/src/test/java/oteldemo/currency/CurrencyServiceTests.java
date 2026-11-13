/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.client.GrpcChannelFactory;
import oteldemo.CurrencyServiceGrpc;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.Empty;
import oteldemo.Demo.GetSupportedCurrenciesResponse;
import oteldemo.Demo.Money;

@SpringBootTest(
    properties = {"otel.traces.exporter=none", "otel.metrics.exporter=none", "otel.logs.exporter=none"})
@AutoConfigureTestGrpcTransport
class CurrencyServiceTests {

  private static final AttributeKey<String> EXCHANGE_FROM = AttributeKey.stringKey("demo.exchange.from");
  private static final AttributeKey<String> EXCHANGE_TO = AttributeKey.stringKey("demo.exchange.to");

  @Autowired private GrpcChannelFactory channels;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private InMemoryMetricReader metrics;
  @Autowired private InMemoryLogRecordExporter logs;

  @BeforeEach
  void setUp() {
    spans.reset();
    logs.reset();
  }

  @Test
  void getSupportedCurrenciesReturnsEveryCodeOfTheRateTable() {
    GetSupportedCurrenciesResponse response = stub().getSupportedCurrencies(Empty.getDefaultInstance());

    assertThat(response.getCurrencyCodesList())
        .hasSize(33)
        .doesNotHaveDuplicates()
        .contains("EUR", "USD", "JPY", "GBP", "ZAR");
  }

  @Test
  void getSupportedCurrenciesSpanHasTheOriginalNameAttributesEventsAndStatus() {
    stub().getSupportedCurrencies(Empty.getDefaultInstance());

    SpanData span = spanNamed("Currency/GetSupportedCurrencies");
    assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
    assertThat(span.getAttributes().get(AttributeKey.stringKey("rpc.system.name"))).isEqualTo("grpc");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("rpc.method")))
        .isEqualTo("oteldemo.CurrencyService/GetSupportedCurrencies");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("rpc.response.status.code")))
        .isEqualTo("OK");
    assertThat(span.getEvents())
        .extracting(EventData::getName)
        .containsExactly(
            "Processing supported currencies request", "Currencies fetched, response sent back");
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
  }

  @Test
  void getSupportedCurrenciesEmitsTheLogEvent() {
    stub().getSupportedCurrencies(Empty.getDefaultInstance());

    LogRecordData record = logNamed("currency.get_supported_currencies");
    assertThat(record.getBodyValue().asString()).isEqualTo("GetSupportedCurrencies successful");
    assertThat(record.getSeverityText()).isEqualTo("INFO");
    assertThat(record.getInstrumentationScopeInfo().getName()).isEqualTo("currency");
  }

  @Test
  void convertReturnsTheOriginalArithmeticOverGrpc() {
    Money result = stub().convert(request("USD", 100, 0, "JPY"));

    assertThat(result.getCurrencyCode()).isEqualTo("JPY");
    assertThat(result.getUnits()).isEqualTo(11180L);
    assertThat(result.getNanos()).isEqualTo(893409995);
  }

  @Test
  void convertSpanHasTheOriginalNameAttributesEventsAndStatus() {
    stub().convert(request("USD", 5, 0, "EUR"));

    SpanData span = spanNamed("Currency/Convert");
    assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
    assertThat(span.getAttributes().get(AttributeKey.stringKey("rpc.system.name"))).isEqualTo("grpc");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("rpc.method")))
        .isEqualTo("oteldemo.CurrencyService/Convert");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("rpc.response.status.code")))
        .isEqualTo("OK");
    assertThat(span.getAttributes().get(EXCHANGE_FROM)).isEqualTo("USD");
    assertThat(span.getAttributes().get(EXCHANGE_TO)).isEqualTo("EUR");
    assertThat(span.getEvents())
        .extracting(EventData::getName)
        .containsExactly(
            "Processing currency conversion request", "Conversion successful, response sent back");
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
  }

  @Test
  void convertContinuesTheTraceOfTheCaller() {
    // The original extracted the parent from the W3C traceparent metadata. In the test the client
    // side of the gRPC instrumentation is not installed, so the parent context is injected by hand.
    SpanContext parent =
        SpanContext.create(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    io.grpc.Metadata headers = new io.grpc.Metadata();
    headers.put(
        io.grpc.Metadata.Key.of("traceparent", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
        "00-" + parent.getTraceId() + "-" + parent.getSpanId() + "-01");

    stub()
        .withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers))
        .convert(request("EUR", 1, 0, "USD"));

    SpanData span = spanNamed("Currency/Convert");
    assertThat(span.getTraceId()).isEqualTo(parent.getTraceId());
    assertThat(span.getParentSpanId()).isEqualTo(parent.getSpanId());
  }

  @Test
  void convertIncrementsTheConversionCounterPerTargetCurrency() {
    long eurBefore = conversionsTo("EUR");
    long jpyBefore = conversionsTo("JPY");

    stub().convert(request("USD", 1, 0, "EUR"));
    stub().convert(request("USD", 1, 0, "EUR"));
    stub().convert(request("EUR", 1, 0, "JPY"));

    assertThat(conversionsTo("EUR")).isEqualTo(eurBefore + 2);
    assertThat(conversionsTo("JPY")).isEqualTo(jpyBefore + 1);
  }

  @Test
  void convertEmitsTheConversionLogEventWithFromAndToAttributes() {
    stub().convert(request("GBP", 3, 0, "CHF"));

    LogRecordData record = logNamed("currency.conversion");
    assertThat(record.getBodyValue().asString()).isEqualTo("conversion successful");
    assertThat(record.getSeverityText()).isEqualTo("INFO");
    assertThat(record.getAttributes().get(AttributeKey.stringKey("currency.from"))).isEqualTo("GBP");
    assertThat(record.getAttributes().get(AttributeKey.stringKey("currency.to"))).isEqualTo("CHF");
  }

  @Test
  void unsupportedCurrencyIsNotAnErrorInTheOriginalEither() {
    // C++ operator[] reads an unknown code as rate 0.0, the call still returns OK.
    Money result = stub().convert(request("EUR", 1, 0, "XXX"));

    assertThat(result.getCurrencyCode()).isEqualTo("XXX");
    assertThat(result.getUnits()).isZero();
    assertThat(result.getNanos()).isZero();
  }

  @Test
  void aStatusRuntimeExceptionIsNotRaisedForUnknownSourceCurrency() {
    Money result;
    try {
      result = stub().convert(request("XXX", 1, 0, "EUR"));
    } catch (StatusRuntimeException e) {
      throw new AssertionError("original returns OK for this request", e);
    }
    assertThat(result.getUnits()).isEqualTo(Long.MIN_VALUE);
  }

  @Test
  void healthServiceReportsServing() {
    HealthCheckResponse response =
        HealthGrpc.newBlockingStub(channels.createChannel("currency"))
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .check(HealthCheckRequest.getDefaultInstance());

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
  }

  @Test
  void healthServiceReportsServingForTheCurrencyService() {
    HealthCheckResponse response =
        HealthGrpc.newBlockingStub(channels.createChannel("currency"))
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .check(HealthCheckRequest.newBuilder().setService("oteldemo.CurrencyService").build());

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
  }

  private CurrencyServiceGrpc.CurrencyServiceBlockingStub stub() {
    return CurrencyServiceGrpc.newBlockingStub(channels.createChannel("currency"))
        .withDeadlineAfter(5, TimeUnit.SECONDS);
  }

  private static CurrencyConversionRequest request(String from, long units, int nanos, String to) {
    return CurrencyConversionRequest.newBuilder()
        .setFrom(Money.newBuilder().setCurrencyCode(from).setUnits(units).setNanos(nanos))
        .setToCode(to)
        .build();
  }

  // The server span ends after the client already has its response, so give it a moment.
  private SpanData spanNamed(String name) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> findSpan(span -> span.getName().equals(name)), Optional::isPresent)
        .orElseThrow();
  }

  private Optional<SpanData> findSpan(Predicate<SpanData> predicate) {
    return spans.getFinishedSpanItems().stream().filter(predicate).findFirst();
  }

  private LogRecordData logNamed(String eventName) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () ->
                logs.getFinishedLogRecordItems().stream()
                    .filter(record -> eventName.equals(record.getEventName()))
                    .findFirst(),
            Optional::isPresent)
        .orElseThrow();
  }

  private long conversionsTo(String code) {
    return metrics.collectAllMetrics().stream()
        .filter(metric -> metric.getName().equals(CurrencyService.COUNTER_NAME))
        .flatMap(metric -> metric.getLongSumData().getPoints().stream())
        .filter(point -> code.equals(point.getAttributes().get(EXCHANGE_TO)))
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
                  (tracerProvider, config) ->
                      tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addMeterProviderCustomizer(
                  (meterProvider, config) -> meterProvider.registerMetricReader(metricReader))
              .addLoggerProviderCustomizer(
                  (loggerProvider, config) ->
                      loggerProvider.addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter)));
    }
  }
}
