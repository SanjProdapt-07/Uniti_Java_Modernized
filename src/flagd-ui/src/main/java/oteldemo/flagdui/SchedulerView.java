/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import oteldemo.flagdui.Scheduler.Activation;

/** Presentation values of the scheduler page derived from a {@link Scheduler.State}. */
public record SchedulerView(
    boolean running,
    String title,
    String badge,
    long intervalCount,
    String seed,
    int activeCount,
    int concurrency,
    List<ActiveEntry> active,
    String nextLabel,
    List<HistoryEntry> history) {

  public record ActiveEntry(String flag, String variant, String left) {}

  public record HistoryEntry(String flag, String variant, String startedAt, String duration) {}

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  public static SchedulerView of(Scheduler.State state, long nowMs) {
    List<ActiveEntry> active =
        state.active().stream()
            .map(a -> new ActiveEntry(a.flag(), a.variant(), formatMs(a.until() - nowMs)))
            .toList();
    List<HistoryEntry> history =
        state.history().stream()
            .map(
                (Activation a) ->
                    new HistoryEntry(
                        a.flag(),
                        a.variant(),
                        TIME.format(a.startedAt()),
                        formatMs(a.durationMs())))
            .toList();
    String next =
        state.nextTriggerAt() == null ? "unknown" : formatMs(state.nextTriggerAt() - nowMs);
    return new SchedulerView(
        state.running(),
        state.running() ? "Scheduler running" : "Scheduler stopped",
        state.running() ? "Active" : "Idle",
        state.intervalCount(),
        state.seedUsed() == null ? "" : String.valueOf(state.seedUsed()),
        state.active().size(),
        state.config().concurrency(),
        active,
        next,
        history);
  }

  /** Port of the LiveView {@code format_ms/1}. */
  public static String formatMs(long ms) {
    if (ms <= 0) {
      return "0s";
    }
    long seconds = ms / 1000;
    long minutes = seconds / 60;
    long hours = minutes / 60;
    if (hours > 0) {
      return hours + "h " + (minutes % 60) + "m";
    }
    if (minutes > 0) {
      return minutes + "m " + (seconds % 60) + "s";
    }
    return seconds + "s";
  }
}
