/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import dev.openfeature.sdk.Client;
import io.lettuce.core.resource.ClientResources;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.lettuce.v5_1.LettuceTelemetry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two cart stores of the original: the real one on {@code VALKEY_ADDR}, and one pointed at
 * the unreachable {@code badhost:1234} that {@code EmptyCart} uses when the {@code cartFailure}
 * feature flag fires. Switched off with {@code cart.valkey.enabled=false} (tests do that and
 * provide in-memory stores).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cart.valkey.enabled", havingValue = "true", matchIfMissing = true)
class ValkeyConfiguration {

  static final String BAD_HOST_ADDRESS = "badhost:1234";

  @Bean(destroyMethod = "shutdown")
  ClientResources valkeyClientResources(OpenTelemetry openTelemetry) {
    return LettuceValkeyLinkFactory.resourcesBuilder()
        // SetVerboseDatabaseStatements = true: full, unsanitized statements
        .tracing(
            LettuceTelemetry.builder(openTelemetry)
                .setQuerySanitizationEnabled(false)
                .build()
                .createTracing())
        .build();
  }

  @Bean(destroyMethod = "close")
  ValkeyCartStore cartStore(
      ClientResources resources,
      Client featureClient,
      OpenTelemetry openTelemetry,
      @Value("${cart.valkey.address:}") String address,
      @Value("${cart.valkey.password:}") String password,
      @Value("${cart.valkey.tls:false}") String tls) {
    if (address == null || address.isBlank()) {
      System.out.println("VALKEY_ADDR environment variable is required.");
      throw new IllegalStateException("VALKEY_ADDR environment variable is required.");
    }
    boolean useTls = Boolean.parseBoolean(tls.trim());
    ValkeyCartStore store =
        new ValkeyCartStore(
            new LettuceValkeyLinkFactory(resources, address, password, useTls),
            ValkeyAddress.parse(address).connectionString(useTls),
            featureClient,
            openTelemetry,
            ValkeyCartStore.REDIS_RETRY_NUMBER);
    store.initialize();
    return store;
  }

  /**
   * Never initialized: every use tries to connect to a host that doesn't exist and fails, which
   * is the point. A single attempt, so that the failure is quick.
   */
  @Bean(destroyMethod = "close")
  @Qualifier("badCartStore")
  ValkeyCartStore badCartStore(
      ClientResources resources, Client featureClient, OpenTelemetry openTelemetry) {
    return new ValkeyCartStore(
        new LettuceValkeyLinkFactory(resources, BAD_HOST_ADDRESS, null, false),
        ValkeyAddress.parse(BAD_HOST_ADDRESS).connectionString(false),
        featureClient,
        openTelemetry,
        1);
  }
}
