/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.ad;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.MutableContext;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.prometheus.metrics.core.metrics.Counter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import oteldemo.AdServiceGrpc;
import oteldemo.Demo.Ad;
import oteldemo.Demo.AdRequest;
import oteldemo.Demo.AdResponse;
import oteldemo.ad.problempattern.CPULoad;
import oteldemo.ad.problempattern.GarbageCollectionTrigger;

@Service
public class AdService extends AdServiceGrpc.AdServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(AdService.class);

  private static final int MAX_ADS_TO_SERVE = 2;

  private static final String AD_FAILURE = "adFailure";
  private static final String AD_MANUAL_GC_FEATURE_FLAG = "adManualGc";
  private static final String AD_HIGH_CPU_FEATURE_FLAG = "adHighCpu";

  private static final AttributeKey<String> adRequestTypeKey =
      AttributeKey.stringKey("demo.ad.request_type");
  private static final AttributeKey<String> adResponseTypeKey =
      AttributeKey.stringKey("demo.ad.response_type");

  private enum AdRequestType {
    TARGETED,
    NOT_TARGETED
  }

  private enum AdResponseType {
    TARGETED,
    RANDOM
  }

  private static final Map<String, List<Ad>> adsMap = createAdsMap();
  private static final List<Ad> allAdsList =
      adsMap.values().stream().flatMap(List::stream).toList();

  private final Client featureFlags;
  private final Tracer tracer;
  private final LongCounter adRequestsCounter;
  private final Counter adsServedCounter;
  private final CPULoad cpuLoad;
  private final Supplier<GarbageCollectionTrigger> gcTriggerFactory;
  private final Random random;

  @Autowired
  public AdService(Client featureFlags, OpenTelemetry openTelemetry, Counter adsServedCounter) {
    this(
        featureFlags,
        openTelemetry,
        adsServedCounter,
        CPULoad.getInstance(),
        GarbageCollectionTrigger::new,
        new Random());
  }

  AdService(
      Client featureFlags,
      OpenTelemetry openTelemetry,
      Counter adsServedCounter,
      CPULoad cpuLoad,
      Supplier<GarbageCollectionTrigger> gcTriggerFactory,
      Random random) {
    this.featureFlags = featureFlags;
    this.tracer = openTelemetry.getTracer("ad");
    this.adRequestsCounter =
        openTelemetry
            .getMeter("ad")
            .counterBuilder("demo.ad.requests")
            .setDescription("Counts ad requests by request and response type")
            .build();
    this.adsServedCounter = adsServedCounter;
    this.cpuLoad = cpuLoad;
    this.gcTriggerFactory = gcTriggerFactory;
    this.random = random;
  }

  /**
   * Retrieves ads based on context provided in the request {@code AdRequest}.
   *
   * @param req the request containing context.
   * @param responseObserver the stream observer which gets notified with the value of {@code
   *     AdResponse}
   */
  @Override
  public void getAds(AdRequest req, StreamObserver<AdResponse> responseObserver) {
    // get the current span in context
    Span span = Span.current();
    try {
      List<Ad> allAds = new ArrayList<>();
      AdRequestType adRequestType;
      AdResponseType adResponseType;

      Baggage baggage = Baggage.fromContextOrNull(Context.current());
      MutableContext evaluationContext = new MutableContext();
      if (baggage != null) {
        final String sessionId = baggage.getEntryValue("session.id");
        span.setAttribute("session.id", sessionId);
        evaluationContext.setTargetingKey(sessionId);
        evaluationContext.add("session", sessionId);
        final String enduserId = baggage.getEntryValue("enduser.id");
        if (enduserId != null) {
          span.setAttribute("enduser.id", enduserId);
        }
      } else {
        logger.info("no baggage found in context");
      }

      cpuLoad.execute(featureFlags.getBooleanValue(AD_HIGH_CPU_FEATURE_FLAG, false, evaluationContext));

      span.setAttribute("demo.ad.context_keys", req.getContextKeysList().toString());
      span.setAttribute("demo.ad.context_keys.count", req.getContextKeysCount());
      if (req.getContextKeysCount() > 0) {
        logger.info("Targeted ad request received for {}", req.getContextKeysList());
        for (int i = 0; i < req.getContextKeysCount(); i++) {
          Collection<Ad> ads = getAdsByCategory(req.getContextKeys(i));
          allAds.addAll(ads);
        }
        adRequestType = AdRequestType.TARGETED;
        adResponseType = AdResponseType.TARGETED;
      } else {
        logger.info("Non-targeted ad request received, preparing random response.");
        allAds = getRandomAds();
        adRequestType = AdRequestType.NOT_TARGETED;
        adResponseType = AdResponseType.RANDOM;
      }
      if (allAds.isEmpty()) {
        // Serve random ads.
        allAds = getRandomAds();
        adResponseType = AdResponseType.RANDOM;
      }
      span.setAttribute("demo.ad.count", allAds.size());
      span.setAttribute("demo.ad.request_type", adRequestType.name());
      span.setAttribute("demo.ad.response_type", adResponseType.name());

      adRequestsCounter.add(
          1,
          Attributes.of(
              adRequestTypeKey, adRequestType.name(), adResponseTypeKey, adResponseType.name()));

      // Throw 1/10 of the time to simulate a failure when the feature flag is enabled
      if (featureFlags.getBooleanValue(AD_FAILURE, false, evaluationContext)
          && random.nextInt(10) == 0) {
        throw new StatusRuntimeException(Status.UNAVAILABLE);
      }

      if (featureFlags.getBooleanValue(AD_MANUAL_GC_FEATURE_FLAG, false, evaluationContext)) {
        logger.warn("Feature Flag " + AD_MANUAL_GC_FEATURE_FLAG + " enabled, performing a manual gc now");
        GarbageCollectionTrigger gct = gcTriggerFactory.get();
        gct.doExecute();
      }

      AdResponse reply = AdResponse.newBuilder().addAllAds(allAds).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      span.addEvent(
          "Error", Attributes.of(AttributeKey.stringKey("exception.message"), e.getMessage()));
      span.setStatus(StatusCode.ERROR);
      logger.warn("GetAds Failed with status {}", e.getStatus());
      responseObserver.onError(e);
    }
  }

  // Replaces the @WithSpan("getAdsByCategory") / @SpanAttribute("demo.ad.category") annotations of
  // the original: the Spring Boot starter only weaves @WithSpan into proxied beans, and this
  // method is called from within the same bean, so the span is created explicitly.
  private Collection<Ad> getAdsByCategory(String category) {
    Span span =
        tracer
            .spanBuilder("getAdsByCategory")
            .setAttribute("code.namespace", AdService.class.getName())
            .setAttribute("code.function", "getAdsByCategory")
            .setAttribute("demo.ad.category", category)
            .startSpan();
    try (Scope ignored = span.makeCurrent()) {
      Collection<Ad> ads = adsMap.getOrDefault(category, List.of());
      Span.current().setAttribute("demo.ad.count", ads.size());
      adsServedCounter.labelValues(category).inc(ads.size());
      return ads;
    } finally {
      span.end();
    }
  }

  private List<Ad> getRandomAds() {

    List<Ad> ads = new ArrayList<>(MAX_ADS_TO_SERVE);

    // create and start a new span manually
    Span span = tracer.spanBuilder("getRandomAds").startSpan();

    // put the span into context, so if any child span is started the parent will be set properly
    try (Scope ignored = span.makeCurrent()) {

      for (int i = 0; i < MAX_ADS_TO_SERVE; i++) {
        ads.add(allAdsList.get(random.nextInt(allAdsList.size())));
      }
      span.setAttribute("demo.ad.count", ads.size());
      adsServedCounter.labelValues("random").inc(ads.size());

    } finally {
      span.end();
    }

    return ads;
  }

  private static Map<String, List<Ad>> createAdsMap() {
    Ad binoculars =
        Ad.newBuilder()
            .setRedirectUrl("/product/2ZYFJ3GM2N")
            .setText("Roof Binoculars for sale. 50% off.")
            .build();
    Ad explorerTelescope =
        Ad.newBuilder()
            .setRedirectUrl("/product/66VCHSJNUP")
            .setText("Starsense Explorer Refractor Telescope for sale. 20% off.")
            .build();
    Ad colorImager =
        Ad.newBuilder()
            .setRedirectUrl("/product/0PUK6V6EV0")
            .setText("Solar System Color Imager for sale. 30% off.")
            .build();
    Ad opticalTube =
        Ad.newBuilder()
            .setRedirectUrl("/product/9SIQT8TOJO")
            .setText("Optical Tube Assembly for sale. 10% off.")
            .build();
    Ad travelTelescope =
        Ad.newBuilder()
            .setRedirectUrl("/product/1YMWWN1N4O")
            .setText(
                "Eclipsmart Travel Refractor Telescope for sale. Buy one, get second kit for free")
            .build();
    Ad solarFilter =
        Ad.newBuilder()
            .setRedirectUrl("/product/6E92ZMYYFZ")
            .setText("Solar Filter for sale. Buy two, get third one for free")
            .build();
    Ad cleaningKit =
        Ad.newBuilder()
            .setRedirectUrl("/product/L9ECAV7KIM")
            .setText("Lens Cleaning Kit for sale. Buy one, get second one for free")
            .build();
    Map<String, List<Ad>> ads = new LinkedHashMap<>();
    ads.put("binoculars", List.of(binoculars));
    ads.put("telescopes", List.of(explorerTelescope));
    ads.put("accessories", List.of(colorImager, solarFilter, cleaningKit));
    ads.put("assembly", List.of(opticalTube));
    ads.put("travel", List.of(travelTelescope));
    // Keep the books category free of ads to ensure the random code branch is tested
    return Collections.unmodifiableMap(ads);
  }
}
