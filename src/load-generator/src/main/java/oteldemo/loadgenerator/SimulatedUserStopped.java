/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

/** Thrown inside a simulated user when it is being stopped (Locust: {@code StopUser}). */
public class SimulatedUserStopped extends RuntimeException {

  public SimulatedUserStopped() {
    super("user stopped", null, false, false);
  }
}
