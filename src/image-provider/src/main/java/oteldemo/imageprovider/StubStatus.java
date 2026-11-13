/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import java.util.concurrent.atomic.AtomicLong;

/** Counters rendered by {@code /status} in the format of the nginx {@code stub_status} page. */
public class StubStatus {

  private final AtomicLong active = new AtomicLong();
  private final AtomicLong total = new AtomicLong();

  void requestStarted() {
    active.incrementAndGet();
    total.incrementAndGet();
  }

  void requestFinished() {
    active.decrementAndGet();
  }

  /** Same layout as nginx stub_status (trailing spaces included). */
  public String render() {
    long handled = total.get();
    long activeNow = active.get();
    return "Active connections: " + activeNow + " \n"
        + "server accepts handled requests\n"
        + " " + handled + " " + handled + " " + handled + " \n"
        + "Reading: 0 Writing: " + activeNow + " Waiting: 0 \n";
  }
}
