/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.javahttpclient.JavaHttpClientTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.http.HttpClient;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** A standalone OpenTelemetry SDK with in-memory exporters and the demo's default propagators. */
final class TestTelemetry implements AutoCloseable {

  final InMemorySpanExporter spans = InMemorySpanExporter.create();
  final InMemoryMetricReader metrics = InMemoryMetricReader.create();
  final OpenTelemetrySdk sdk;
  final RequestStats stats = new RequestStats();
  final LoadGeneratorMetrics loadMetrics;
  final JsonMapper mapper = JsonMapper.builder().build();

  TestTelemetry() {
    sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .setPropagators(
                ContextPropagators.create(
                    TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
            .build();
    loadMetrics = new LoadGeneratorMetrics(sdk);
  }

  UserHttpSession session(String host) {
    HttpClient client = JavaHttpClientTelemetry.create(sdk).wrap(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
    return new HttpSessionFactory(client, () -> host, "python-requests/2.32.5", stats, loadMetrics, mapper).newSession();
  }

  List<SpanData> spanData() {
    return spans.getFinishedSpanItems();
  }

  SpanData span(String name) {
    return spanData().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow(
        () -> new AssertionError("no span " + name + " in " + spanData().stream().map(SpanData::getName).toList()));
  }

  @Override
  public void close() {
    sdk.close();
  }
}
