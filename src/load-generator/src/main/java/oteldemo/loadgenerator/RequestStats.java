/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locust-like request statistics: one entry per (method, name) plus an aggregate, response-time histogram
 * with Locust's rounding, 10 second sliding window for current RPS, and an error table.
 */
public class RequestStats {

  /** Entry window for current_rps / current_fail_per_sec, like Locust's CURRENT_RESPONSE_TIME_PERCENTILE_WINDOW. */
  static final int RPS_WINDOW_SECONDS = 10;

  private final Clock clock;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final Map<String, ErrorEntry> errors = new ConcurrentHashMap<>();
  private volatile Entry total = new Entry(null, "Aggregated");
  private volatile long lastRequestTimestampMillis;

  public RequestStats() {
    this(Clock.systemUTC());
  }

  RequestStats(Clock clock) {
    this.clock = clock;
  }

  /** Records a completed request ({@code error == null}) or a failed one. */
  public void log(String method, String name, long responseTimeMillis, long contentLength, String error) {
    long now = clock.millis();
    lastRequestTimestampMillis = now;
    entries.computeIfAbsent(method + " " + name, k -> new Entry(method, name))
        .record(now, responseTimeMillis, contentLength, error != null);
    total.record(now, responseTimeMillis, contentLength, error != null);
    if (error != null) {
      errors
          .computeIfAbsent(method + " " + name + " " + error, k -> new ErrorEntry(method, name, error))
          .occurrences.incrementAndGet();
    }
  }

  public void reset() {
    entries.clear();
    errors.clear();
    total = new Entry(null, "Aggregated");
  }

  public List<Entry> entries() {
    List<Entry> list = new ArrayList<>(entries.values());
    list.sort(Comparator.comparing((Entry e) -> e.name).thenComparing(e -> e.method));
    return list;
  }

  public Entry total() {
    return total;
  }

  public List<ErrorEntry> errors() {
    return new ArrayList<>(errors.values());
  }

  public long lastRequestTimestampMillis() {
    return lastRequestTimestampMillis;
  }

  Clock clock() {
    return clock;
  }

  /** Locust's response time bucketing (keeps the histogram small). */
  static long roundResponseTime(long millis) {
    if (millis < 100) {
      return millis;
    }
    if (millis < 1000) {
      return (long) Math.rint(millis / 10.0) * 10;
    }
    if (millis < 10000) {
      return (long) Math.rint(millis / 100.0) * 100;
    }
    return (long) Math.rint(millis / 1000.0) * 1000;
  }

  /** One statistics row. */
  public static final class Entry {
    private final String method;
    private final String name;
    private long numRequests;
    private long numFailures;
    private long totalResponseTime;
    private long minResponseTime = -1;
    private long maxResponseTime;
    private long totalContentLength;
    private final TreeMap<Long, Long> histogram = new TreeMap<>();
    private final TreeMap<Long, long[]> perSecond = new TreeMap<>(); // second -> {requests, failures}

    Entry(String method, String name) {
      this.method = method;
      this.name = name;
    }

    synchronized void record(long nowMillis, long responseTime, long contentLength, boolean failed) {
      numRequests++;
      if (failed) {
        numFailures++;
      }
      totalResponseTime += responseTime;
      minResponseTime = minResponseTime < 0 ? responseTime : Math.min(minResponseTime, responseTime);
      maxResponseTime = Math.max(maxResponseTime, responseTime);
      totalContentLength += Math.max(0, contentLength);
      histogram.merge(roundResponseTime(responseTime), 1L, Long::sum);
      long second = nowMillis / 1000;
      long[] counters = perSecond.computeIfAbsent(second, s -> new long[2]);
      counters[0]++;
      if (failed) {
        counters[1]++;
      }
      // keep the window bounded
      perSecond.headMap(second - RPS_WINDOW_SECONDS * 3L, true).clear();
    }

    public String method() {
      return method;
    }

    public String name() {
      return name;
    }

    public synchronized long numRequests() {
      return numRequests;
    }

    public synchronized long numFailures() {
      return numFailures;
    }

    public synchronized long minResponseTime() {
      return Math.max(minResponseTime, 0);
    }

    public synchronized long maxResponseTime() {
      return maxResponseTime;
    }

    public synchronized double avgResponseTime() {
      return numRequests == 0 ? 0 : (double) totalResponseTime / numRequests;
    }

    public synchronized double avgContentLength() {
      return numRequests == 0 ? 0 : (double) totalContentLength / numRequests;
    }

    public synchronized long percentile(double fraction) {
      if (numRequests == 0) {
        return 0;
      }
      long threshold = (long) Math.ceil(numRequests * fraction);
      long seen = 0;
      for (Map.Entry<Long, Long> bucket : histogram.entrySet()) {
        seen += bucket.getValue();
        if (seen >= threshold) {
          return bucket.getKey();
        }
      }
      return maxResponseTime;
    }

    public synchronized long median() {
      return percentile(0.5);
    }

    /** Requests per second over the last (up to) 10 seconds, excluding the current, incomplete second. */
    public synchronized double currentRps(long nowMillis) {
      return windowSum(nowMillis, 0) / (double) RPS_WINDOW_SECONDS;
    }

    public synchronized double currentFailPerSec(long nowMillis) {
      return windowSum(nowMillis, 1) / (double) RPS_WINDOW_SECONDS;
    }

    private long windowSum(long nowMillis, int index) {
      long second = nowMillis / 1000;
      long sum = 0;
      for (Map.Entry<Long, long[]> e : perSecond.subMap(second - RPS_WINDOW_SECONDS, true, second, true).entrySet()) {
        sum += e.getValue()[index];
      }
      return sum;
    }
  }

  /** One row of the error table. */
  public static final class ErrorEntry {
    private final String method;
    private final String name;
    private final String error;
    private final java.util.concurrent.atomic.AtomicLong occurrences = new java.util.concurrent.atomic.AtomicLong();

    ErrorEntry(String method, String name, String error) {
      this.method = method;
      this.name = name;
      this.error = error;
    }

    public String method() {
      return method;
    }

    public String name() {
      return name;
    }

    public String error() {
      return error;
    }

    public long occurrences() {
      return occurrences.get();
    }
  }
}
