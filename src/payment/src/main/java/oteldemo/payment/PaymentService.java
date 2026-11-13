/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.payment;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import oteldemo.Demo.ChargeRequest;
import oteldemo.Demo.ChargeResponse;
import oteldemo.Demo.Money;
import oteldemo.PaymentServiceGrpc;

/** Port of {@code index.js}: the {@code oteldemo.PaymentService/Charge} gRPC handler. */
@Service
public class PaymentService extends PaymentServiceGrpc.PaymentServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

  private final ChargeService chargeService;

  public PaymentService(ChargeService chargeService) {
    this.chargeService = chargeService;
  }

  @Override
  public void charge(ChargeRequest request, StreamObserver<ChargeResponse> responseObserver) {
    Span span = Span.current();

    try {
      if (!request.hasAmount()) {
        throw new PaymentException(
            "TypeError", "Cannot read properties of undefined (reading 'units')");
      }
      span.setAttribute("demo.payment.amount", formatAmount(request.getAmount()));
      logger.info("Charge request received.");

      String transactionId = chargeService.charge(request);
      responseObserver.onNext(ChargeResponse.newBuilder().setTransactionId(transactionId).build());
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      logger.warn("Charge request failed: {}", e.getMessage(), e);

      span.setStatus(StatusCode.ERROR, e.getMessage());
      span.setAttribute("error.type", ChargeService.errorType(e));
      // Node.js handed the error to the gRPC callback, which answers UNKNOWN with the message
      responseObserver.onError(
          Status.UNKNOWN.withDescription(e.getMessage()).asRuntimeException());
    }
  }

  /** {@code (units + nanos / 1e9).toFixed(2)}, rounding the exact binary value half up like JS. */
  static String formatAmount(Money amount) {
    double value = (double) amount.getUnits() + amount.getNanos() / 1_000_000_000.0;
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
