/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import oteldemo.flagdui.Scheduler.Activation;
import oteldemo.flagdui.Scheduler.Config;
import oteldemo.flagdui.Scheduler.Pending;
import oteldemo.flagdui.Scheduler.State;

class SchedulerViewTest {

  @Test
  void formatsDurationsLikeTheLiveView() {
    assertEquals("0s", SchedulerView.formatMs(-5));
    assertEquals("0s", SchedulerView.formatMs(0));
    assertEquals("0s", SchedulerView.formatMs(999));
    assertEquals("1s", SchedulerView.formatMs(1000));
    assertEquals("59s", SchedulerView.formatMs(59_999));
    assertEquals("1m 0s", SchedulerView.formatMs(60_000));
    assertEquals("3m 5s", SchedulerView.formatMs(185_000));
    assertEquals("1h 0m", SchedulerView.formatMs(3_600_000));
    assertEquals("2h 5m", SchedulerView.formatMs(2 * 3_600_000 + 5 * 60_000 + 30_000));
  }

  @Test
  void stoppedStateRendersIdle() {
    State state = new State(Config.DEFAULT, false, null, 0, List.of(), List.of(), List.of(), null);

    SchedulerView view = SchedulerView.of(state, 0);

    assertEquals("Scheduler stopped", view.title());
    assertEquals("Idle", view.badge());
    assertEquals("", view.seed());
    assertEquals("unknown", view.nextLabel());
  }

  @Test
  void runningStateRendersActivationsAndHistory() {
    Activation active = new Activation("adFailure", "on", "off", 60_000, Instant.EPOCH, 90_000);
    Activation done =
        new Activation("cartFailure", "25%", "off", 20_000, Instant.parse("2026-01-02T03:04:05Z"), 0);
    State state =
        new State(
            Config.DEFAULT.withConcurrency(2),
            true,
            42L,
            3,
            List.of(new Pending("x", "on", 1000, 70_000)),
            List.of(active),
            List.of(done),
            70_000L);

    SchedulerView view = SchedulerView.of(state, 10_000);

    assertEquals("Scheduler running", view.title());
    assertEquals("Active", view.badge());
    assertEquals("42", view.seed());
    assertEquals(3, view.intervalCount());
    assertEquals(1, view.activeCount());
    assertEquals(2, view.concurrency());
    assertEquals("1m 20s", view.active().get(0).left());
    assertEquals("1m 0s", view.nextLabel());
    assertEquals("03:04:05 UTC", view.history().get(0).startedAt());
    assertEquals("20s", view.history().get(0).duration());
  }
}
