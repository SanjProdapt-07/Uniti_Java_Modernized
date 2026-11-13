/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import oteldemo.Demo.Ad;
import oteldemo.Demo.AdRequest;
import oteldemo.Demo.AdResponse;
import oteldemo.ad.problempattern.CPULoad;
import oteldemo.ad.problempattern.GarbageCollectionTrigger;

/** Behavior of {@link AdService#getAds} with the flag client, random source and problem patterns mocked. */
class AdServiceUnitTests {

  private static final String AD_FAILURE = "adFailure";
  private static final String AD_MANUAL_GC = "adManualGc";
  private static final String AD_HIGH_CPU = "adHighCpu";

  private final Client featureFlags = mock(Client.class);
  private final CPULoad cpuLoad = mock(CPULoad.class);
  private final GarbageCollectionTrigger gcTrigger = mock(GarbageCollectionTrigger.class);
  @SuppressWarnings("unchecked")
  private final Supplier<GarbageCollectionTrigger> gcFactory = mock(Supplier.class);
  private final Random random = mock(Random.class);
  private final InMemorySpanExporter spans = InMemorySpanExporter.create();
  private final PrometheusRegistry registry = new PrometheusRegistry();
  private final Counter adsServed =
      Counter.builder()
          .name("demo_ad_served_total")
          .help("Total number of ads served, labeled by category")
          .labelNames("category")
          .register(registry);

  private final InMemoryMetricReader metrics = InMemoryMetricReader.create();

  private AdService service;
  private Tracer tracer;

  @BeforeEach
  void setUp() {
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans))
                    .build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build();
    tracer = sdk.getTracer("test");
    when(gcFactory.get()).thenReturn(gcTrigger);
    service = new AdService(featureFlags, sdk, adsServed, cpuLoad, gcFactory, random);
  }

  @Test
  void targetedRequestReturnsAdsOfEveryRequestedCategoryInOrder() {
    Result result = call(request("travel", "assembly"));

    assertThat(result.ads()).extracting(Ad::getRedirectUrl)
        .containsExactly("/product/1YMWWN1N4O", "/product/9SIQT8TOJO");
    assertThat(result.completed()).isTrue();
  }

  @Test
  void accessoriesCategoryHasThreeAds() {
    Result result = call(request("accessories"));

    assertThat(result.ads()).extracting(Ad::getText)
        .containsExactly(
            "Solar System Color Imager for sale. 30% off.",
            "Solar Filter for sale. Buy two, get third one for free",
            "Lens Cleaning Kit for sale. Buy one, get second one for free");
  }

  @Test
  void everyCategoryMapsToTheOriginalAd() {
    assertThat(call(request("binoculars")).ads())
        .singleElement()
        .satisfies(ad -> {
          assertThat(ad.getRedirectUrl()).isEqualTo("/product/2ZYFJ3GM2N");
          assertThat(ad.getText()).isEqualTo("Roof Binoculars for sale. 50% off.");
        });
    assertThat(call(request("telescopes")).ads())
        .singleElement()
        .satisfies(ad -> {
          assertThat(ad.getRedirectUrl()).isEqualTo("/product/66VCHSJNUP");
          assertThat(ad.getText())
              .isEqualTo("Starsense Explorer Refractor Telescope for sale. 20% off.");
        });
    assertThat(call(request("assembly")).ads())
        .singleElement()
        .satisfies(ad -> {
          assertThat(ad.getRedirectUrl()).isEqualTo("/product/9SIQT8TOJO");
          assertThat(ad.getText()).isEqualTo("Optical Tube Assembly for sale. 10% off.");
        });
    assertThat(call(request("travel")).ads())
        .singleElement()
        .satisfies(ad -> {
          assertThat(ad.getRedirectUrl()).isEqualTo("/product/1YMWWN1N4O");
          assertThat(ad.getText())
              .isEqualTo(
                  "Eclipsmart Travel Refractor Telescope for sale. Buy one, get second kit for free");
        });
  }

  @Test
  void requestWithoutContextKeysGetsTwoRandomAds() {
    when(random.nextInt(7)).thenReturn(0, 6);

    Result result = call(request());

    // allAds order: binoculars, telescope, 3 accessories, assembly, travel
    assertThat(result.ads()).extracting(Ad::getRedirectUrl)
        .containsExactly("/product/2ZYFJ3GM2N", "/product/1YMWWN1N4O");
    SpanData server = span("test-server");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.request_type")))
        .isEqualTo("NOT_TARGETED");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.response_type")))
        .isEqualTo("RANDOM");
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.ad.count"))).isEqualTo(2L);
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.ad.context_keys.count")))
        .isZero();
  }

  @Test
  void categoryWithoutAdsFallsBackToRandomAds() {
    when(random.nextInt(7)).thenReturn(2, 3);

    Result result = call(request("books"));

    assertThat(result.ads()).extracting(Ad::getRedirectUrl)
        .containsExactly("/product/0PUK6V6EV0", "/product/6E92ZMYYFZ");
    SpanData server = span("test-server");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.request_type")))
        .isEqualTo("TARGETED");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.response_type")))
        .isEqualTo("RANDOM");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.context_keys")))
        .isEqualTo("[books]");
  }

  @Test
  void targetedRequestRecordsServerSpanAttributesAndChildSpans() {
    call(request("accessories", "travel"));

    SpanData server = span("test-server");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.context_keys")))
        .isEqualTo("[accessories, travel]");
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.ad.context_keys.count")))
        .isEqualTo(2L);
    assertThat(server.getAttributes().get(AttributeKey.longKey("demo.ad.count"))).isEqualTo(4L);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.request_type")))
        .isEqualTo("TARGETED");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.ad.response_type")))
        .isEqualTo("TARGETED");

    List<SpanData> categorySpans =
        spans.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("getAdsByCategory"))
            .toList();
    assertThat(categorySpans).hasSize(2);
    assertThat(categorySpans.get(0).getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(categorySpans.get(0).getAttributes().get(AttributeKey.stringKey("demo.ad.category")))
        .isEqualTo("accessories");
    assertThat(categorySpans.get(0).getAttributes().get(AttributeKey.longKey("demo.ad.count")))
        .isEqualTo(3L);
    assertThat(categorySpans.get(1).getAttributes().get(AttributeKey.stringKey("demo.ad.category")))
        .isEqualTo("travel");
    assertThat(categorySpans.get(1).getAttributes().get(AttributeKey.longKey("demo.ad.count")))
        .isEqualTo(1L);
  }

  @Test
  void randomAdsAreServedInsideTheirOwnSpan() {
    when(random.nextInt(7)).thenReturn(0, 0);

    call(request());

    SpanData server = span("test-server");
    SpanData randomSpan = span("getRandomAds");
    assertThat(randomSpan.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(randomSpan.getAttributes().get(AttributeKey.longKey("demo.ad.count"))).isEqualTo(2L);
  }

  @Test
  void countsServedAdsPerCategoryInThePrometheusCounter() {
    when(random.nextInt(7)).thenReturn(0, 0);

    call(request("accessories"));
    call(request("travel"));
    call(request());

    assertThat(adsServed.labelValues("accessories").get()).isEqualTo(3.0);
    assertThat(adsServed.labelValues("travel").get()).isEqualTo(1.0);
    assertThat(adsServed.labelValues("random").get()).isEqualTo(2.0);
  }

  @Test
  void baggageSessionAndEnduserAreAttachedToTheSpanAndFlagContext() {
    Baggage baggage =
        Baggage.builder().put("session.id", "session-1").put("enduser.id", "user-1").build();

    call(request("travel"), baggage);

    SpanData server = span("test-server");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("session.id")))
        .isEqualTo("session-1");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("enduser.id")))
        .isEqualTo("user-1");
    ArgumentCaptor<EvaluationContext> context = ArgumentCaptor.forClass(EvaluationContext.class);
    verify(featureFlags).getBooleanValue(eq(AD_HIGH_CPU), eq(false), context.capture());
    assertThat(context.getValue().getTargetingKey()).isEqualTo("session-1");
    assertThat(context.getValue().getValue("session").asString()).isEqualTo("session-1");
  }

  @Test
  void requestWithoutBaggageUsesAnEmptyFlagContext() {
    call(request("travel"));

    ArgumentCaptor<EvaluationContext> context = ArgumentCaptor.forClass(EvaluationContext.class);
    verify(featureFlags).getBooleanValue(eq(AD_HIGH_CPU), eq(false), context.capture());
    assertThat(context.getValue().getTargetingKey()).isNull();
    SpanData server = span("test-server");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("session.id"))).isNull();
  }

  @Test
  void highCpuFlagStartsAndStopsTheCpuLoad() {
    when(featureFlags.getBooleanValue(eq(AD_HIGH_CPU), eq(false), any(EvaluationContext.class)))
        .thenReturn(true);
    call(request("travel"));
    verify(cpuLoad).execute(true);

    when(featureFlags.getBooleanValue(eq(AD_HIGH_CPU), eq(false), any(EvaluationContext.class)))
        .thenReturn(false);
    call(request("travel"));
    verify(cpuLoad).execute(false);
  }

  @Test
  void failureFlagFailsWithUnavailableWhenTheDiceRollsZero() {
    flagOn(AD_FAILURE);
    when(random.nextInt(10)).thenReturn(0);

    Result result = call(request("travel"));

    assertThat(result.error()).isNotNull();
    assertThat(result.error().getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.ads()).isEmpty();
    assertThat(result.completed()).isFalse();
    SpanData server = span("test-server");
    assertThat(server.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(server.getEvents()).singleElement().satisfies(event -> {
      assertThat(event.getName()).isEqualTo("Error");
      assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.message")))
          .isEqualTo("UNAVAILABLE");
    });
  }

  @Test
  void failureFlagLetsNineOutOfTenRequestsThrough() {
    flagOn(AD_FAILURE);
    when(random.nextInt(10)).thenReturn(1);

    Result result = call(request("travel"));

    assertThat(result.error()).isNull();
    assertThat(result.ads()).hasSize(1);
  }

  @Test
  void failureDiceIsNotRolledWhenTheFlagIsOff() {
    call(request("travel"));

    verify(random, never()).nextInt(10);
  }

  @Test
  void manualGcFlagTriggersAGarbageCollectionCycle() {
    flagOn(AD_MANUAL_GC);

    Result result = call(request("travel"));

    verify(gcTrigger).doExecute();
    assertThat(result.ads()).hasSize(1);
  }

  @Test
  void noGarbageCollectionWhenTheManualGcFlagIsOff() {
    call(request("travel"));

    Mockito.verifyNoInteractions(gcFactory);
  }

  @Test
  void aNewGarbageCollectionTriggerIsCreatedForEveryRequest() {
    flagOn(AD_MANUAL_GC);

    call(request("travel"));
    call(request("travel"));

    verify(gcFactory, Mockito.times(2)).get();
  }

  @Test
  void countsRequestsByRequestAndResponseTypeInTheOtelCounter() {
    when(random.nextInt(7)).thenReturn(0);

    call(request("travel"));
    call(request("travel"));
    call(request());
    call(request("books"));

    assertThat(requestCount("TARGETED", "TARGETED")).isEqualTo(2L);
    assertThat(requestCount("NOT_TARGETED", "RANDOM")).isEqualTo(1L);
    assertThat(requestCount("TARGETED", "RANDOM")).isEqualTo(1L);
    MetricData metric =
        metrics.collectAllMetrics().stream()
            .filter(m -> m.getName().equals("demo.ad.requests"))
            .findFirst()
            .orElseThrow();
    assertThat(metric.getDescription())
        .isEqualTo("Counts ad requests by request and response type");
  }

  private long requestCount(String requestType, String responseType) {
    return metrics.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("demo.ad.requests"))
        .flatMap(m -> m.getLongSumData().getPoints().stream())
        .filter(
            p ->
                requestType.equals(p.getAttributes().get(AttributeKey.stringKey("demo.ad.request_type")))
                    && responseType.equals(
                        p.getAttributes().get(AttributeKey.stringKey("demo.ad.response_type"))))
        .mapToLong(LongPointData::getValue)
        .sum();
  }

  private void flagOn(String flag) {
    when(featureFlags.getBooleanValue(eq(flag), eq(false), any(EvaluationContext.class)))
        .thenReturn(true);
  }

  private static AdRequest request(String... contextKeys) {
    return AdRequest.newBuilder().addAllContextKeys(List.of(contextKeys)).build();
  }

  private SpanData span(String name) {
    return spans.getFinishedSpanItems().stream()
        .filter(s -> s.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no span named " + name));
  }

  private Result call(AdRequest request) {
    return call(request, null);
  }

  /** Runs getAds inside a "test-server" span, as the gRPC server interceptor would. */
  private Result call(AdRequest request, Baggage baggage) {
    Span serverSpan = tracer.spanBuilder("test-server").startSpan();
    RecordingObserver observer = new RecordingObserver();
    try (Scope ignored = serverSpan.makeCurrent();
        Scope baggageScope = baggage == null ? null : baggage.makeCurrent()) {
      service.getAds(request, observer);
    } finally {
      serverSpan.end();
    }
    return new Result(observer.ads, observer.error, observer.completed);
  }

  private record Result(List<Ad> ads, StatusRuntimeException error, boolean completed) {}

  private static final class RecordingObserver implements StreamObserver<AdResponse> {
    private List<Ad> ads = List.of();
    private StatusRuntimeException error;
    private boolean completed;

    @Override
    public void onNext(AdResponse value) {
      ads = value.getAdsList();
    }

    @Override
    public void onError(Throwable t) {
      error = (StatusRuntimeException) t;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
