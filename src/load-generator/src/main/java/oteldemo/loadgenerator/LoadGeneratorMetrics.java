/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics describing the generated load. The original exported only Python runtime/system and OTel SDK
 * metrics; these are an addition that mirrors what Locust shows in its UI.
 */
public class LoadGeneratorMetrics {

  private static final AttributeKey<String> METHOD = AttributeKey.stringKey("http.request.method");
  private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

  private final AtomicInteger users = new AtomicInteger();
  private final LongCounter requests;
  private final DoubleHistogram duration;

  public LoadGeneratorMetrics(OpenTelemetry openTelemetry) {
    Meter meter = openTelemetry.getMeter("oteldemo.loadgenerator");
    meter
        .gaugeBuilder("load_generator.users")
        .ofLongs()
        .setDescription("Number of running simulated users")
        .setUnit("{user}")
        .buildWithCallback(m -> m.record(users.get()));
    requests =
        meter
            .counterBuilder("load_generator.requests")
            .setDescription("Requests issued by simulated users")
            .setUnit("{request}")
            .build();
    duration =
        meter
            .histogramBuilder("load_generator.request.duration")
            .setDescription("Response time of requests issued by simulated users")
            .setUnit("ms")
            .build();
  }

  void setUserCount(int count) {
    users.set(count);
  }

  void recordRequest(String method, boolean success, long millis) {
    Attributes attributes = Attributes.of(METHOD, method, OUTCOME, success ? "success" : "failure");
    requests.add(1, attributes);
    duration.record(millis, attributes);
  }
}
