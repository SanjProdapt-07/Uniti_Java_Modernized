/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.GrpcChannelFactory;
import oteldemo.AdServiceGrpc;
import oteldemo.Demo.Ad;
import oteldemo.Demo.AdRequest;
import oteldemo.Demo.AdResponse;

/** End-to-end through the gRPC server with the real OpenTelemetry starter wiring and an in-memory flag provider. */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "ad.prometheus-port=0"
    })
@AutoConfigureTestGrpcTransport
class AdServiceIntegrationTests {

  private static final String AD_FAILURE = "adFailure";
  private static final String AD_HIGH_CPU = "adHighCpu";

  @Autowired private GrpcChannelFactory channels;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private InMemoryMetricReader metrics;
  @Autowired private InMemoryProvider featureFlags;
  @Autowired private HTTPServer prometheusServer;

  @BeforeEach
  void setUp() {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setFlags(false, false);
    spans.reset();
  }

  @AfterEach
  void tearDown() {
    setFlags(false, false);
  }

  @Test
  void targetedRequestReturnsTheAdsOfTheCategory() {
    AdResponse response = getAds("accessories");

    assertThat(response.getAdsList())
        .extracting(Ad::getRedirectUrl)
        .containsExactly("/product/0PUK6V6EV0", "/product/6E92ZMYYFZ", "/product/L9ECAV7KIM");
  }

  @Test
  void requestWithoutContextKeysReturnsTwoRandomAds() {
    AdResponse response = getAds();

    assertThat(response.getAdsList()).hasSize(2);
    assertThat(response.getAdsList()).allSatisfy(ad -> {
      assertThat(ad.getRedirectUrl()).startsWith("/product/");
      assertThat(ad.getText()).isNotBlank();
    });
  }

  @Test
  void categoryWithoutAdsFallsBackToTwoRandomAds() {
    AdResponse response = getAds("books");

    assertThat(response.getAdsList()).hasSize(2);
  }

  @Test
  void serverSpanCarriesTheAdAttributesAndTheChildSpan() {
    getAds("accessories", "travel");

    SpanData server = spanNamed("oteldemo.AdService/GetAds", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.context_keys")))
        .isEqualTo("[accessories, travel]");
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.ad.context_keys.count")))
        .isEqualTo(2L);
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.ad.count"))).isEqualTo(4L);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.request_type")))
        .isEqualTo("TARGETED");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.response_type")))
        .isEqualTo("TARGETED");

    SpanData category = spanNamed("getAdsByCategory", SpanKind.INTERNAL);
    assertThat(category.getTraceId()).isEqualTo(server.getTraceId());
    assertThat(category.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(category.getAttributes().get(AttributeKey.stringKey("demo.ad.category")))
        .isNotNull();
  }

  @Test
  void randomRequestRecordsTheRandomAdsSpan() {
    getAds();

    SpanData server = spanNamed("oteldemo.AdService/GetAds", SpanKind.SERVER);
    SpanData random = spanNamed("getRandomAds", SpanKind.INTERNAL);
    assertThat(random.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(random.getAttributes().get(AttributeKey.longKey("demo.ad.count"))).isEqualTo(2L);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.request_type")))
        .isEqualTo("NOT_TARGETED");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.response_type")))
        .isEqualTo("RANDOM");
  }

  @Test
  void baggageSessionAndEnduserBecomeServerSpanAttributes() {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("baggage", Metadata.ASCII_STRING_MARSHALLER),
        "session.id=session-42,enduser.id=user-7");

    AdServiceGrpc.newBlockingStub(channel())
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .getAds(AdRequest.newBuilder().addContextKeys("travel").build());

    SpanData server = spanNamed("oteldemo.AdService/GetAds", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("session.id")))
        .isEqualTo("session-42");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("enduser.id")))
        .isEqualTo("user-7");
  }

  @Test
  void requestsAreCountedByTypeInTheOtelCounter() {
    long before = requestsCounted("TARGETED", "TARGETED");

    getAds("travel");

    assertThat(requestsCounted("TARGETED", "TARGETED")).isEqualTo(before + 1);
  }

  @Test
  void servedAdsAreExposedOnThePrometheusEndpoint() throws Exception {
    getAds("accessories");
    getAds();

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + prometheusServer.getPort() + "/metrics"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("# HELP demo_ad_served_total Total number of ads served, labeled by category")
        .containsPattern("demo_ad_served_total\\{category=\"accessories\"\\} [0-9.]+")
        .containsPattern("demo_ad_served_total\\{category=\"random\"\\} [0-9.]+");
  }

  @Test
  void healthServiceReportsServing() {
    HealthCheckResponse response =
        HealthGrpc.newBlockingStub(channel())
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .check(HealthCheckRequest.getDefaultInstance());

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
  }

  @Test
  void failureFlagEventuallyFailsWithUnavailableAndMarksTheSpan() {
    setFlags(true, false);

    // one request in ten fails; the chance of 300 successes in a row is 0.9^300
    StatusRuntimeException failure = null;
    for (int i = 0; i < 300 && failure == null; i++) {
      try {
        getAds("travel");
      } catch (StatusRuntimeException e) {
        failure = e;
      }
    }

    assertThat(failure).isNotNull();
    assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () ->
                spans.getFinishedSpanItems().stream()
                    .anyMatch(
                        s ->
                            s.getKind() == SpanKind.SERVER
                                && s.getStatus().getStatusCode() == StatusCode.ERROR
                                && s.getEvents().stream().anyMatch(e -> e.getName().equals("Error"))));
  }

  @Test
  void highCpuFlagDoesNotBreakRequests() {
    setFlags(false, true);
    try {
      assertThat(getAds("travel").getAdsList()).hasSize(1);
    } finally {
      setFlags(false, false);
      // the next request with the flag off stops the load workers again
      assertThat(getAds("travel").getAdsList()).hasSize(1);
      assertThat(oteldemo.ad.problempattern.CPULoad.getInstance().runningWorkerCount()).isZero();
    }
  }

  @Test
  void requestsNeverFailWhileTheFailureFlagIsOff() {
    for (int i = 0; i < 100; i++) {
      assertThat(getAds("travel").getAdsList()).hasSize(1);
    }
  }

  private Channel channel() {
    return channels.createChannel("ad");
  }

  private AdResponse getAds(String... contextKeys) {
    return AdServiceGrpc.newBlockingStub(channel())
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .getAds(AdRequest.newBuilder().addAllContextKeys(List.of(contextKeys)).build());
  }

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

  private long requestsCounted(String requestType, String responseType) {
    return metrics.collectAllMetrics().stream()
        .filter(metric -> metric.getName().equals("demo.ad.requests"))
        .flatMap(metric -> metric.getLongSumData().getPoints().stream())
        .filter(
            point ->
                requestType.equals(point.getAttributes().get(AttributeKey.stringKey("demo.ad.request_type")))
                    && responseType.equals(
                        point.getAttributes().get(AttributeKey.stringKey("demo.ad.response_type"))))
        .mapToLong(LongPointData::getValue)
        .sum();
  }

  private void setFlags(boolean failure, boolean highCpu) {
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put(AD_FAILURE, booleanFlag(failure));
    flags.put(AD_HIGH_CPU, booleanFlag(highCpu));
    featureFlags.updateFlags(flags);
  }

  private static Flag<Boolean> booleanFlag(boolean enabled) {
    return Flag.<Boolean>builder()
        .variant("on", true)
        .variant("off", false)
        .defaultVariant(enabled ? "on" : "off")
        .build();
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
