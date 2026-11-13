/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.payment;

/**
 * A failed charge. {@link #errorType()} carries what the Node.js service reported as
 * {@code err.name} ("Error" for the business failures, "TypeError" for malformed requests) and is
 * recorded as the {@code error.type} span attribute.
 */
class PaymentException extends RuntimeException {

  private final String errorType;

  PaymentException(String message) {
    this("Error", message);
  }

  PaymentException(String errorType, String message) {
    super(message);
    this.errorType = errorType;
  }

  String errorType() {
    return errorType;
  }
}
