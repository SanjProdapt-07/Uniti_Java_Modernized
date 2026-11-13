/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

/** A simulated user: runs on its own (virtual) thread until it is interrupted. */
public interface SimulatedUser {

  /** Blocks, issuing traffic, until the thread is interrupted or {@link SimulatedUserStopped} is thrown. */
  void run();
}
