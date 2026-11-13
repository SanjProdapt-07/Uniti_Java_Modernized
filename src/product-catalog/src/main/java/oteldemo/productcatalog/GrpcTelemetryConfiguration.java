/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

@Configuration(proxyBeanMethods = false)
class GrpcTelemetryConfiguration {

  private static final String HEALTH_SERVICE = "grpc.health.v1.Health";

  @Bean
  GrpcTelemetry grpcTelemetry(OpenTelemetry openTelemetry) {
    return GrpcTelemetry.create(openTelemetry);
  }

  /**
   * Server spans and metrics for every call except the health checks, which the Go service
   * excluded with {@code otelgrpc.WithFilter(filters.Not(filters.HealthCheck()))}.
   */
  @Bean
  @GlobalServerInterceptor
  ServerInterceptor grpcTelemetryServerInterceptor(GrpcTelemetry grpcTelemetry) {
    ServerInterceptor telemetry = grpcTelemetry.createServerInterceptor();
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (HEALTH_SERVICE.equals(call.getMethodDescriptor().getServiceName())) {
          return next.startCall(call, headers);
        }
        return telemetry.interceptCall(call, headers, next);
      }
    };
  }
}
