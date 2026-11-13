/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

/** Terminates the process (headless runs end with the run time; {@code LOCUST_AUTOQUIT}). */
@FunctionalInterface
public interface ExitHandler {

  void exit(int code);
}
