/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

import io.grpc.ClientInterceptor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;

/** Client spans and trace-context/baggage propagation for every outgoing gRPC call. */
@Configuration(proxyBeanMethods = false)
class GrpcTelemetryConfiguration {

  @Bean
  GrpcTelemetry grpcTelemetry(OpenTelemetry openTelemetry) {
    return GrpcTelemetry.create(openTelemetry);
  }

  @Bean
  @GlobalClientInterceptor
  ClientInterceptor grpcTelemetryClientInterceptor(GrpcTelemetry grpcTelemetry) {
    return grpcTelemetry.createClientInterceptor();
  }
}
