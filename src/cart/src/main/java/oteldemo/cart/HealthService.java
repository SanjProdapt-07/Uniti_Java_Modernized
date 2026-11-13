/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import io.grpc.Status;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The {@code grpc.health.v1.Health} service. The overall status (empty service name) and the
 * status of {@code oteldemo.CartService} are the readiness check; Watch sends the current status
 * once and completes, like the original.
 */
@Service
public class HealthService extends HealthGrpc.HealthImplBase {

  private static final Logger logger = LoggerFactory.getLogger(HealthService.class);

  private final ReadinessCheck readinessCheck;

  public HealthService(ReadinessCheck readinessCheck) {
    this.readinessCheck = readinessCheck;
  }

  @Override
  public void check(
      HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
    CartLog.healthCheckRequest(logger, request.getService());
    respond(request, responseObserver);
  }

  @Override
  public void watch(
      HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
    CartLog.healthWatchRequest(logger, request.getService());
    // Sends the current status once; the original doesn't keep the stream open either.
    respond(request, responseObserver);
  }

  private void respond(
      HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
    String service = request.getService();
    if (!service.isEmpty() && !service.equals(ReadinessCheck.NAME)) {
      // The original looks the name up in the registered checks and fails with a
      // KeyNotFoundException, which gRPC reports as UNKNOWN.
      responseObserver.onError(
          Status.UNKNOWN
              .withDescription(
                  "Exception was thrown by handler. KeyNotFoundException: The given key '"
                      + service
                      + "' was not present in the dictionary.")
              .asRuntimeException());
      return;
    }
    responseObserver.onNext(
        HealthCheckResponse.newBuilder()
            .setStatus(readinessCheck.isHealthy() ? ServingStatus.SERVING : ServingStatus.NOT_SERVING)
            .build());
    responseObserver.onCompleted();
  }
}
