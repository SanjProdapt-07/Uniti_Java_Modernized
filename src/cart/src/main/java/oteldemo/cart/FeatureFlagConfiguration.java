/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import dev.openfeature.contrib.hooks.otel.MetricsHook;
import dev.openfeature.contrib.hooks.otel.TracesHook;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import dev.openfeature.contrib.providers.flagd.FlagdProvider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * flagd through OpenFeature, with the metrics hook and the hook that adds the evaluations to the
 * current span (the {@code MetricsHook} and {@code TraceEnricherHook} of the original).
 */
@Configuration(proxyBeanMethods = false)
class FeatureFlagConfiguration {

  @Bean
  FeatureProvider flagdProvider(OpenTelemetry openTelemetry) {
    return new FlagdProvider(FlagdOptions.builder().openTelemetry(openTelemetry).build());
  }

  @Bean
  Client featureFlagClient(FeatureProvider featureProvider, OpenTelemetry openTelemetry) {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProvider(featureProvider);
    api.addHooks(new MetricsHook(openTelemetry), new TracesHook());
    return api.getClient();
  }
}
