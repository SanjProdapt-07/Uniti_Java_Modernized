/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PropertiesAndStatsTests {

  private static LoadGeneratorProperties props(String browserFlag) {
    return new LoadGeneratorProperties("", 1, 1, "", false, false, -1, browserFlag, 9, 1, "agent", "8010", "ua");
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", "True", "yes", "YES", "on", "On"})
  void browserTrafficEnabledAcceptsOriginalTruthyValues(String value) {
    assertThat(props(value).isBrowserTrafficEnabled()).isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"false", "no", "off", "1", "0", "enabled"})
  void browserTrafficDisabledOtherwise(String value) {
    assertThat(props(value).isBrowserTrafficEnabled()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"300,300", "20s,20", "3m,180", "2h,7200", "1h30m10s,5410", "10M,600"})
  void parsesLocustTimeSpans(String text, long seconds) {
    assertThat(LoadGeneratorProperties.parseDuration(text)).isEqualTo(Duration.ofSeconds(seconds));
  }

  @Test
  void emptyRunTimeMeansUnlimited() {
    assertThat(LoadGeneratorProperties.parseDuration("")).isNull();
    assertThat(LoadGeneratorProperties.parseDuration(null)).isNull();
  }

  @Test
  void rejectsGarbageRunTime() {
    assertThatThrownBy(() -> LoadGeneratorProperties.parseDuration("soon")).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"5,5", "99,99", "100,100", "104,100", "105,100", "999,1000", "1049,1000", "1050,1000", "10499,10000", "12600,13000"})
  void roundsResponseTimesLikeLocust(long actual, long rounded) {
    assertThat(RequestStats.roundResponseTime(actual)).isEqualTo(rounded);
  }

  @Test
  void aggregatesRequestsAndFailures() {
    RequestStats stats = new RequestStats();
    stats.log("GET", "/", 10, 100, null);
    stats.log("GET", "/", 30, 300, null);
    stats.log("GET", "/api/cart", 20, 0, "500 Internal Server Error for url: http://x/api/cart");

    assertThat(stats.entries()).extracting(RequestStats.Entry::name).containsExactly("/", "/api/cart");
    RequestStats.Entry home = stats.entries().get(0);
    assertThat(home.numRequests()).isEqualTo(2);
    assertThat(home.numFailures()).isZero();
    assertThat(home.avgResponseTime()).isEqualTo(20.0);
    assertThat(home.minResponseTime()).isEqualTo(10);
    assertThat(home.maxResponseTime()).isEqualTo(30);
    assertThat(home.avgContentLength()).isEqualTo(200.0);
    assertThat(stats.total().numRequests()).isEqualTo(3);
    assertThat(stats.total().numFailures()).isEqualTo(1);
    assertThat(stats.errors()).singleElement().satisfies(e -> {
      assertThat(e.method()).isEqualTo("GET");
      assertThat(e.name()).isEqualTo("/api/cart");
      assertThat(e.occurrences()).isEqualTo(1);
    });
  }

  @Test
  void percentilesUseTheHistogram() {
    RequestStats stats = new RequestStats();
    for (int i = 1; i <= 100; i++) {
      stats.log("GET", "/p", i, 1, null);
    }
    RequestStats.Entry entry = stats.entries().get(0);
    assertThat(entry.median()).isEqualTo(50);
    assertThat(entry.percentile(0.95)).isEqualTo(95);
  }

  @Test
  void currentRpsUsesTenSecondWindow() {
    var now = new java.util.concurrent.atomic.AtomicLong(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    Clock clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return Instant.ofEpochMilli(now.get());
          }
        };
    RequestStats stats = new RequestStats(clock);
    for (int i = 0; i < 20; i++) {
      stats.log("GET", "/", 5, 1, i % 2 == 0 ? null : "boom");
      now.addAndGet(100);
    }
    assertThat(stats.total().currentRps(now.get())).isEqualTo(2.0);
    assertThat(stats.total().currentFailPerSec(now.get())).isEqualTo(1.0);
    now.addAndGet(30_000);
    assertThat(stats.total().currentRps(now.get())).isZero();
  }

  @Test
  void resetClearsEverything() {
    RequestStats stats = new RequestStats();
    stats.log("GET", "/", 5, 1, "boom");
    stats.reset();
    assertThat(stats.entries()).isEmpty();
    assertThat(stats.errors()).isEmpty();
    assertThat(stats.total().numRequests()).isZero();
  }
}
