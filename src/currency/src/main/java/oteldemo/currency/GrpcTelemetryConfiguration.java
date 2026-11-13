/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import io.grpc.ServerInterceptor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

/**
 * Creates the SERVER span for every call and extracts the parent from the incoming W3C trace
 * context headers, which is what the C++ {@code GrpcServerCarrier} + {@code HttpTraceContext}
 * pair did by hand.
 */
@Configuration(proxyBeanMethods = false)
class GrpcTelemetryConfiguration {

  @Bean
  GrpcTelemetry grpcTelemetry(OpenTelemetry openTelemetry) {
    // The original span carried only its own events, so the per-message events are switched off.
    return GrpcTelemetry.builder(openTelemetry).setEmitMessageEvents(false).build();
  }

  @Bean
  @GlobalServerInterceptor
  ServerInterceptor grpcTelemetryServerInterceptor(GrpcTelemetry grpcTelemetry) {
    return grpcTelemetry.createServerInterceptor();
  }
}
