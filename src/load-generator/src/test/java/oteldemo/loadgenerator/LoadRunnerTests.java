/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadRunnerTests {

  private final AtomicInteger started = new AtomicInteger();
  private final AtomicInteger stopped = new AtomicInteger();
  private final ConcurrentHashMap<String, AtomicInteger> byClass = new ConcurrentHashMap<>();
  private TestTelemetry telemetry;
  private LoadRunner runner;
  private CountDownLatch runTimeFinished;

  private SimulatedUser blockingUser(String name) {
    return () -> {
      started.incrementAndGet();
      byClass.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
      try {
        Thread.sleep(Duration.ofHours(1));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        stopped.incrementAndGet();
        byClass.get(name).decrementAndGet();
      }
    };
  }

  @BeforeEach
  void setUp() {
    telemetry = new TestTelemetry();
    runTimeFinished = new CountDownLatch(1);
    runner =
        new LoadRunner(
            List.of(
                new LoadRunner.UserClass("WebsiteUser", 9, () -> blockingUser("http")),
                new LoadRunner.UserClass("WebsiteBrowserUser", 1, () -> blockingUser("browser"))),
            telemetry.stats,
            telemetry.loadMetrics,
            "http://host",
            runTimeFinished::countDown);
  }

  @AfterEach
  void tearDown() {
    runner.shutdown();
    telemetry.close();
  }

  @Test
  void startsInReadyState() {
    assertThat(runner.state()).isEqualTo(LoadRunner.State.READY);
    assertThat(runner.userCount()).isZero();
    assertThat(runner.host()).isEqualTo("http://host");
  }

  @Test
  void mixFollowsWeights() {
    assertThat(runner.targets(10)).containsExactly(9, 1);
    assertThat(runner.targets(5)).containsExactly(5, 0);
    assertThat(runner.targets(20)).containsExactly(18, 2);
    assertThat(runner.targets(0)).containsExactly(0, 0);
  }

  @Test
  void weightZeroExcludesClass() {
    LoadRunner onlyHttp =
        new LoadRunner(
            List.of(
                new LoadRunner.UserClass("WebsiteUser", 9, () -> blockingUser("http")),
                new LoadRunner.UserClass("WebsiteBrowserUser", 0, () -> blockingUser("browser"))),
            telemetry.stats,
            telemetry.loadMetrics,
            "",
            () -> {});
    assertThat(onlyHttp.userClasses()).hasSize(1);
    assertThat(onlyHttp.targets(10)).containsExactly(10);
  }

  @Test
  void swarmSpawnsRequestedUsersAndReachesRunning() {
    runner.start(10, 1000, null);

    await().atMost(Duration.ofSeconds(10)).until(() -> runner.state() == LoadRunner.State.RUNNING);
    assertThat(runner.userCount()).isEqualTo(10);
    await().until(() -> started.get() == 10);
    assertThat(byClass.get("http").get()).isEqualTo(9);
    assertThat(byClass.get("browser").get()).isEqualTo(1);
  }

  @Test
  void spawnRateLimitsRamp() {
    runner.start(100, 20, null); // 20 users per second

    await().pollDelay(Duration.ofMillis(500)).until(() -> started.get() > 0);
    assertThat(runner.state()).isEqualTo(LoadRunner.State.SPAWNING);
    assertThat(runner.userCount()).isBetween(1, 30);
  }

  @Test
  void stopInterruptsAllUsers() {
    runner.start(5, 1000, null);
    await().until(() -> started.get() == 5);

    runner.stop();

    assertThat(runner.state()).isEqualTo(LoadRunner.State.STOPPED);
    assertThat(runner.userCount()).isZero();
    assertThat(stopped.get()).isEqualTo(5);
  }

  @Test
  void swarmingAgainScalesUpAndDown() {
    runner.start(5, 1000, null);
    await().until(() -> runner.state() == LoadRunner.State.RUNNING);

    runner.start(8, 1000, null);
    await().until(() -> runner.state() == LoadRunner.State.RUNNING && runner.userCount() == 8);

    runner.start(2, 1000, null);
    await().until(() -> runner.state() == LoadRunner.State.RUNNING && runner.userCount() == 2);
    await().until(() -> stopped.get() == 6 + 0); // 8 started... 6 stopped
  }

  @Test
  void restartAfterStopClearsStatistics() {
    telemetry.stats.log("GET", "/", 1, 1, null);
    runner.start(1, 1000, null);
    await().until(() -> runner.state() == LoadRunner.State.RUNNING);
    runner.stop();
    telemetry.stats.log("GET", "/", 1, 1, null);

    runner.start(1, 1000, null);

    assertThat(telemetry.stats.total().numRequests()).isZero();
  }

  @Test
  void runTimeStopsTheRunAndNotifies() throws Exception {
    runner.start(2, 1000, Duration.ofMillis(300));

    assertThat(runTimeFinished.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(runner.state()).isEqualTo(LoadRunner.State.STOPPED);
    assertThat(runner.userCount()).isZero();
  }

  @Test
  void stopOnReadyRunnerIsNoop() {
    runner.stop();
    assertThat(runner.state()).isEqualTo(LoadRunner.State.READY);
  }

  @Test
  void crashingUserDoesNotAffectOthers() {
    LoadRunner crashing =
        new LoadRunner(
            List.of(new LoadRunner.UserClass("WebsiteUser", 1, () -> () -> { throw new IllegalStateException("boom"); })),
            telemetry.stats,
            telemetry.loadMetrics,
            "",
            () -> {});
    crashing.start(3, 1000, null);
    await().until(() -> crashing.state() == LoadRunner.State.RUNNING);
    crashing.shutdown();
  }

  @Test
  void headlessRunTimeEndExitsWithFailureCode() {
    List<Integer> codes = new java.util.concurrent.CopyOnWriteArrayList<>();
    var headless = new LoadGeneratorProperties("", 1, 1, "10s", true, false, -1, "", 9, 1, "agent", "8010", "ua");
    RequestStats stats = new RequestStats();
    LoadRunnerLifecycle.onRunTimeFinished(headless, stats, codes::add);
    stats.log("GET", "/", 1, 1, "boom");
    LoadRunnerLifecycle.onRunTimeFinished(headless, stats, codes::add);

    assertThat(codes).containsExactly(0, 1);
  }

  @Test
  void uiModeDoesNotExitUnlessAutoquit() {
    List<Integer> codes = new java.util.concurrent.CopyOnWriteArrayList<>();
    var ui = new LoadGeneratorProperties("", 1, 1, "10s", false, true, -1, "", 9, 1, "agent", "8010", "ua");
    LoadRunnerLifecycle.onRunTimeFinished(ui, new RequestStats(), codes::add);
    assertThat(codes).isEmpty();

    var autoquit = new LoadGeneratorProperties("", 1, 1, "10s", false, true, 0, "", 9, 1, "agent", "8010", "ua");
    LoadRunnerLifecycle.onRunTimeFinished(autoquit, new RequestStats(), codes::add);
    assertThat(codes).containsExactly(0);
  }
}
