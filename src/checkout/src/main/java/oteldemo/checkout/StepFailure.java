/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

/**
 * A failed step of the order flow. Plays the role of the Go error values: the message is the
 * text the original service builds with fmt.Errorf.
 */
class StepFailure extends RuntimeException {

  StepFailure(String message) {
    super(message);
  }

  StepFailure(String message, Throwable cause) {
    super(message, cause);
  }
}
