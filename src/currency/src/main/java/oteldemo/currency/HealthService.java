/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

/**
 * Port of the C++ {@code HealthServer}: {@code grpc.health.v1.Health/Check} answers SERVING for
 * every request, whatever service name is asked for. {@code Watch} is not implemented, as in the
 * original (the base class answers UNIMPLEMENTED). Spring's own health auto-configuration is
 * turned off in {@code application.yaml} so there is a single health service.
 */
@Service
public class HealthService extends HealthGrpc.HealthImplBase {

  @Override
  public void check(
      HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
    responseObserver.onNext(
        HealthCheckResponse.newBuilder().setStatus(HealthCheckResponse.ServingStatus.SERVING).build());
    responseObserver.onCompleted();
  }
}
