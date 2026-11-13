/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

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
 * OpenFeature client backed by flagd ({@code FLAGD_HOST} / {@code FLAGD_PORT}, the variables the
 * frontend container receives in compose.yaml).
 *
 * <p>The flag the frontend uses ({@code imageSlowLoad}) is evaluated in the browser against flagd
 * through the Envoy {@code /flagservice} route, exactly like the original Next.js app, because its
 * targeting context (the session id) only exists in the browser. The server-side client is wired for parity with the other services but
 * no server code path reads a flag today.
 */
@Configuration(proxyBeanMethods = false)
class FeatureFlagConfiguration {

  @Bean
  FeatureProvider flagdProvider(OpenTelemetry openTelemetry) {
    return new FlagdProvider(FlagdOptions.builder().openTelemetry(openTelemetry).build());
  }

  @Bean
  Client featureFlagClient(FeatureProvider featureProvider) {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProvider(featureProvider);
    api.addHooks(new TracesHook());
    return api.getClient();
  }
}
