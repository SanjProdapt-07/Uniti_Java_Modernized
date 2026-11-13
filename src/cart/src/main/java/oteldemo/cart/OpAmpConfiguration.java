/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import oteldemo.cart.ResourceConfiguration.ResourceHolder;

@Configuration(proxyBeanMethods = false)
class OpAmpConfiguration {

  // The OpenTelemetry parameter makes sure the SDK, and with it the resource, is built first.
  @Bean
  OpAmpClientManager opAmpClientManager(
      OpenTelemetry openTelemetry,
      ResourceHolder resourceHolder,
      @Value("${cart.opamp.endpoint:}") String endpoint,
      @Value("${cart.opamp.tls-insecure-skip-verify:false}") boolean skipTlsVerification) {
    return new OpAmpClientManager(endpoint, resourceHolder.get(), skipTlsVerification);
  }
}
