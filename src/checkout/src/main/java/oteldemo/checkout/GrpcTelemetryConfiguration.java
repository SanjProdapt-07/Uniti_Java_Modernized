/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.server.GlobalServerInterceptor;

@Configuration(proxyBeanMethods = false)
class GrpcTelemetryConfiguration {

  static final String HEALTH_SERVICE_PREFIX = "grpc.health.v1.Health/";

  @Bean
  GrpcTelemetry grpcTelemetry(OpenTelemetry openTelemetry) {
    return GrpcTelemetry.create(openTelemetry);
  }

  /** Server spans for everything except health checks, like otelgrpc's filters.Not(HealthCheck()). */
  @Bean
  @GlobalServerInterceptor
  ServerInterceptor grpcTelemetryServerInterceptor(GrpcTelemetry grpcTelemetry) {
    return new HealthCheckFilteringInterceptor(grpcTelemetry.createServerInterceptor());
  }

  @Bean
  @GlobalClientInterceptor
  ClientInterceptor grpcTelemetryClientInterceptor(GrpcTelemetry grpcTelemetry) {
    return grpcTelemetry.createClientInterceptor();
  }

  static final class HealthCheckFilteringInterceptor implements ServerInterceptor {
    private final ServerInterceptor delegate;

    HealthCheckFilteringInterceptor(ServerInterceptor delegate) {
      this.delegate = delegate;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      if (call.getMethodDescriptor().getFullMethodName().startsWith(HEALTH_SERVICE_PREFIX)) {
        return next.startCall(call, headers);
      }
      return delegate.interceptCall(call, headers, next);
    }
  }
}
