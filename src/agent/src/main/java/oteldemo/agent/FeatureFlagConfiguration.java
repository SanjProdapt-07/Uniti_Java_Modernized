/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import dev.openfeature.contrib.hooks.otel.TracesHook;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import dev.openfeature.contrib.providers.flagd.FlagdProvider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mirrors {@code feature_flags.py}: flagd provider (FLAGD_HOST/FLAGD_PORT) plus the OTel tracing hook. */
@Configuration(proxyBeanMethods = false)
class FeatureFlagConfiguration {

  @Bean
  FeatureProvider flagdProvider(
      OpenTelemetry openTelemetry,
      @Value("${flagd.host:flagd}") String host,
      @Value("${flagd.port:8013}") int port) {
    return new FlagdProvider(
        FlagdOptions.builder().host(host).port(port).openTelemetry(openTelemetry).build());
  }

  @Bean
  Client featureFlagClient(FeatureProvider featureProvider) {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProvider(featureProvider);
    api.addHooks(new TracesHook());
    return api.getClient();
  }

  @Bean
  FlagService flagService(Client featureFlagClient) {
    return new FlagService(featureFlagClient);
  }
}
