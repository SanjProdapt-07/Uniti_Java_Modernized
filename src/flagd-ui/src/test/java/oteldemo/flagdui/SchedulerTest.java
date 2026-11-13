/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import oteldemo.flagdui.Scheduler.Activation;
import oteldemo.flagdui.Scheduler.Config;
import oteldemo.flagdui.Scheduler.SchedulableFlag;
import oteldemo.flagdui.Scheduler.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchedulerTest {

  @TempDir Path dir;

  Storage storage;
  private final List<Scheduler> started = new ArrayList<>();

  @BeforeEach
  void createStorage() {
    storage = new Storage(TestFixtures.copyTo(dir));
  }

  @AfterEach
  void shutdownSchedulers() {
    started.forEach(Scheduler::shutdown);
  }

  // Every activation is held for the whole interval, so the offset within the interval is always
  // zero. That leaves the flag and variant picks as the only consumers of randomness, which keeps
  // seeded runs comparable.
  private static Config immediateConfig() {
    return Config.DEFAULT
        .withIntervalMs(1000)
        .withMinDurationMs(1000)
        .withMaxDurationMs(1000)
        .withSeed(42L)
        .withFlags(null);
  }

  private Scheduler startScheduler() {
    Scheduler scheduler = new Scheduler(storage);
    started.add(scheduler);
    return scheduler;
  }

  /**
   * A flag with a 3-element targeting.if (condition, on-value, off-value) is toggled through that
   * rule rather than its defaultVariant, e.g. productCatalogFailure.
   */
  @SuppressWarnings("unchecked")
  private String variantOf(String flag) {
    Map<String, Object> flags = (Map<String, Object>) ((Map<String, Object>) storage.read()).get("flags");
    Map<String, Object> data = (Map<String, Object>) flags.get(flag);
    List<Object> rule = Storage.targetingIf(data);
    if (rule != null && rule.size() == 3) {
      return (String) rule.get(1);
    }
    return (String) data.get("defaultVariant");
  }

  private static boolean eventually(BooleanSupplier condition) {
    return eventually(condition, 3000);
  }

  private static boolean eventually(BooleanSupplier condition, long timeoutMs) {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (true) {
      if (condition.getAsBoolean()) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  /** The equivalent of assert_receive: the first broadcast state matching, within the timeout. */
  private static State assertReceive(BlockingQueue<State> inbox, Predicate<State> match, long timeoutMs)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    while (true) {
      long remaining = deadline - System.nanoTime();
      State state = inbox.poll(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
      if (state == null) {
        throw new AssertionError("No matching scheduler state received within " + timeoutMs + "ms");
      }
      if (match.test(state)) {
        return state;
      }
    }
  }

  private static Predicate<State> singleActive() {
    return state -> state.active().size() == 1;
  }

  private List<String> schedulableNames() {
    return Scheduler.schedulableFlags(storage.read()).stream().map(SchedulableFlag::name).toList();
  }

  @Nested
  class SchedulableFlags {
    @Test
    void offersEveryFailureFlagWithItsNonOffVariants() {
      List<SchedulableFlag> flags = Scheduler.schedulableFlags(storage.read());

      assertTrue(flags.contains(new SchedulableFlag("adFailure", List.of("on"))));
      assertTrue(flags.contains(new SchedulableFlag("imageSlowLoad", List.of("10sec", "5sec"))));
      assertTrue(
          flags.contains(
              new SchedulableFlag(
                  "cartFailure", List.of("10%", "100%", "25%", "50%", "75%", "90%"))));
    }

    @Test
    void excludesEmitRawPiiWhichIsMarkedNonSchedulableInMetadata() {
      assertFalse(schedulableNames().contains("emitRawPii"));
    }

    @Test
    void toleratesAConfigurationWithoutFlags() throws Exception {
      assertEquals(List.of(), Scheduler.schedulableFlags(Map.of()));
      assertEquals(List.of(), Scheduler.schedulableFlags(FlagJson.decode("{\"flags\":null}")));
      assertEquals(List.of(), Scheduler.schedulableFlags(null));
    }

    @Test
    void skipsFlagsWithoutARestingVariantOrAnotherVariantToSwitchTo() throws Exception {
      Object config =
          FlagJson.decode(
              "{\"flags\":{"
                  + "\"noDefault\":{\"variants\":{\"a\":1,\"b\":2}},"
                  + "\"unknownDefault\":{\"defaultVariant\":\"zzz\",\"variants\":{\"a\":1,\"b\":2}},"
                  + "\"single\":{\"defaultVariant\":\"a\",\"variants\":{\"a\":1}},"
                  + "\"ok\":{\"defaultVariant\":\"b\",\"variants\":{\"a\":1,\"b\":2,\"c\":3}}}}");

      assertEquals(
          List.of(new SchedulableFlag("ok", List.of("a", "c"))), Scheduler.schedulableFlags(config));
    }
  }

  @Nested
  class StartScheduleValidation {
    Scheduler scheduler;

    @BeforeEach
    void create() {
      scheduler = startScheduler();
    }

    @Test
    void rejectsAnIntervalBelowOneSecond() {
      Optional<String> error = scheduler.startSchedule(immediateConfig().withIntervalMs(500));

      assertTrue(error.orElseThrow().contains("Interval must be at least 1 second"));
    }

    @Test
    void rejectsAMaximumDurationBelowTheMinimum() {
      Optional<String> error =
          scheduler.startSchedule(immediateConfig().withMinDurationMs(5000).withMaxDurationMs(2000));

      assertTrue(error.orElseThrow().contains("greater than or equal to the minimum"));
    }

    @Test
    void rejectsADurationThatCannotFitInsideTheInterval() {
      Optional<String> error =
          scheduler.startSchedule(immediateConfig().withIntervalMs(2000).withMaxDurationMs(5000));

      assertTrue(error.orElseThrow().contains("must fit within the interval"));
    }

    @Test
    void rejectsAnEmptyFlagSelection() {
      Optional<String> error = scheduler.startSchedule(immediateConfig().withFlags(Map.of()));

      assertTrue(error.orElseThrow().contains("at least one flag variant"));
    }

    @Test
    void rejectsASelectionWhereEveryFlagHasNoVariants() {
      Optional<String> error =
          scheduler.startSchedule(
              immediateConfig().withFlags(Map.of("adFailure", List.of(), "cartFailure", List.of())));

      assertTrue(error.orElseThrow().contains("at least one flag variant"));
    }

    @Test
    void rejectsAMinimumDurationBelowOneSecond() {
      Optional<String> error = scheduler.startSchedule(immediateConfig().withMinDurationMs(999));

      assertTrue(error.orElseThrow().contains("Minimum duration must be at least 1 second"));
    }

    @Test
    void leavesTheSchedulerStoppedAfterARejectedConfiguration() {
      scheduler.startSchedule(immediateConfig().withIntervalMs(500));

      assertFalse(scheduler.state().running());
    }
  }

  @Nested
  class RunningTheSchedule {
    final BlockingQueue<State> inbox = new LinkedBlockingQueue<>();

    Scheduler newScheduler() {
      Scheduler scheduler = startScheduler();
      scheduler.addListener(inbox::add);
      return scheduler;
    }

    @Test
    void activatesAFlagAndReportsItAsActive() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(scheduler.startSchedule(immediateConfig()).isEmpty());

      State state = assertReceive(inbox, singleActive(), 2000);
      Activation activation = state.active().get(0);

      assertTrue(schedulableNames().contains(activation.flag()));
      assertTrue(!"off".equals(activation.variant()));
      assertTrue(eventually(() -> variantOf(activation.flag()).equals(activation.variant())));
    }

    @Test
    void turnsTheFlagBackOffWhenTheHoldExpires() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(
          scheduler
              .startSchedule(immediateConfig().withIntervalMs(5000).withMaxDurationMs(1000))
              .isEmpty());

      String flag = assertReceive(inbox, singleActive(), 5000).active().get(0).flag();

      assertTrue(eventually(() -> variantOf(flag).equals("off"), 2000));
      assertTrue(scheduler.state().history().stream().anyMatch(a -> a.flag().equals(flag)));
    }

    @Test
    void revertsEveryFlagItActivatesWhenHoldsFillTheWholeInterval() throws Exception {
      Scheduler scheduler = newScheduler();

      // A hold as long as the interval makes each revert land exactly on the next interval
      // boundary, which previously left the earlier flag switched on.
      assertTrue(scheduler.startSchedule(immediateConfig().withSeed(99L)).isEmpty());

      assertReceive(inbox, singleActive(), 2000);

      Thread.sleep(3500);

      scheduler.stopSchedule();

      State state = scheduler.state();
      List<String> touched =
          state.history().stream().map(Activation::flag).distinct().toList();

      // More than one activation means an interval boundary was crossed, which is where the
      // stranded flag used to appear.
      assertTrue(touched.size() > 1, "touched: " + touched);
      assertTrue(state.active().isEmpty());

      for (String flag : touched) {
        assertTrue(eventually(() -> variantOf(flag).equals("off")), flag + " was left active");
      }
    }

    @Test
    void stoppingRevertsAFlagThatIsStillActive() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(
          scheduler
              .startSchedule(
                  immediateConfig()
                      .withIntervalMs(60_000)
                      .withMinDurationMs(60_000)
                      .withMaxDurationMs(60_000))
              .isEmpty());

      String flag = assertReceive(inbox, singleActive(), 2000).active().get(0).flag();

      scheduler.stopSchedule();

      State state = scheduler.state();

      assertFalse(state.running());
      assertTrue(state.active().isEmpty());
      assertTrue(eventually(() -> variantOf(flag).equals("off")));
    }

    @Test
    void restrictsActivationsToTheSelectedFlags() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(
          scheduler
              .startSchedule(immediateConfig().withFlags(Map.of("adHighCpu", List.of("on"))))
              .isEmpty());

      State state =
          assertReceive(
              inbox,
              s ->
                  s.active().size() == 1
                      && s.active().get(0).flag().equals("adHighCpu")
                      && s.active().get(0).variant().equals("on"),
              2000);
      assertNotNull(state);
    }

    @Test
    void restrictsActivationsToTheSelectedVariantsOfAFlag() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(
          scheduler
              .startSchedule(
                  immediateConfig().withFlags(Map.of("cartFailure", List.of("10%", "25%"))))
              .isEmpty());

      for (int i = 0; i < 4; i++) {
        Activation activation =
            assertReceive(
                    inbox,
                    s -> s.active().size() == 1 && s.active().get(0).flag().equals("cartFailure"),
                    2000)
                .active()
                .get(0);

        assertTrue(List.of("10%", "25%").contains(activation.variant()), activation.variant());
      }
    }

    @Test
    void ignoresSelectedVariantsAFlagNoLongerOffers() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(
          scheduler
              .startSchedule(
                  immediateConfig()
                      .withFlags(
                          Map.of("adFailure", List.of("on"), "cartFailure", List.of("200%"))))
              .isEmpty());

      assertReceive(
          inbox,
          s ->
              s.active().size() == 1
                  && s.active().get(0).flag().equals("adFailure")
                  && s.active().get(0).variant().equals("on"),
          2000);
    }

    @Test
    void theSameSeedPicksTheSameFlagAndVariant() throws Exception {
      Scheduler first = newScheduler();

      assertTrue(first.startSchedule(immediateConfig().withSeed(1234L)).isEmpty());
      Activation firstPick = assertReceive(inbox, singleActive(), 2000).active().get(0);
      first.stopSchedule();
      inbox.clear();

      Scheduler second = newScheduler();

      assertTrue(second.startSchedule(immediateConfig().withSeed(1234L)).isEmpty());
      Activation secondPick = assertReceive(inbox, singleActive(), 2000).active().get(0);
      second.stopSchedule();

      assertEquals(firstPick.flag(), secondPick.flag());
      assertEquals(firstPick.variant(), secondPick.variant());
    }

    // A hold as long as the interval keeps every activation in place for the whole test, so
    // concurrent holds can be observed without racing a revert.
    private Config heldConfig() {
      return immediateConfig()
          .withIntervalMs(60_000)
          .withMinDurationMs(60_000)
          .withMaxDurationMs(60_000);
    }

    @Test
    void holdsSeveralDistinctFlagsActiveAtOnce() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(scheduler.startSchedule(heldConfig().withConcurrency(3)).isEmpty());

      State state = assertReceive(inbox, s -> s.active().size() == 3, 2000);

      List<String> flags = state.active().stream().map(Activation::flag).toList();

      assertEquals(3, new HashSet<>(flags).size());

      for (String flag : flags) {
        assertTrue(eventually(() -> !variantOf(flag).equals("off")));
      }
    }

    @Test
    void neverActivatesTheSameFlagTwiceWithinAnInterval() {
      Scheduler scheduler = newScheduler();

      assertTrue(scheduler.startSchedule(heldConfig().withConcurrency(5)).isEmpty());

      State state = scheduler.state();
      List<String> flags = new ArrayList<>();
      state.nextUp().forEach(p -> flags.add(p.flag()));
      state.active().forEach(a -> flags.add(a.flag()));

      assertEquals(5, flags.size());
      assertEquals(5, new HashSet<>(flags).size());
    }

    @Test
    void asksForMoreConcurrencyThanThereAreFlagsAndSettlesForWhatExists() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(
          scheduler
              .startSchedule(
                  heldConfig().withConcurrency(99).withFlags(Map.of("adFailure", List.of("on"))))
              .isEmpty());

      assertReceive(
          inbox,
          s -> s.active().size() == 1 && s.active().get(0).flag().equals("adFailure"),
          2000);
    }

    @Test
    void revertsEveryConcurrentlyHeldFlagWhenStopped() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(scheduler.startSchedule(heldConfig().withConcurrency(4)).isEmpty());

      State state = assertReceive(inbox, s -> s.active().size() == 4, 2000);
      List<String> flags = state.active().stream().map(Activation::flag).toList();

      scheduler.stopSchedule();

      assertTrue(scheduler.state().active().isEmpty());

      for (String flag : flags) {
        assertTrue(eventually(() -> variantOf(flag).equals("off")), flag + " was left active");
      }
    }

    @Test
    void rejectsAConcurrencyBelowOne() {
      Scheduler scheduler = newScheduler();

      Optional<String> error = scheduler.startSchedule(immediateConfig().withConcurrency(0));

      assertTrue(error.orElseThrow().contains("At least one flag has to be activated"));
    }

    @Test
    void reportsTheSeedItUsedWhenNoneWasGiven() {
      Scheduler scheduler = newScheduler();

      assertTrue(scheduler.startSchedule(immediateConfig().withSeed(null)).isEmpty());

      assertNotNull(scheduler.state().seedUsed());
    }

    @Test
    void reportsTheGivenSeed() {
      Scheduler scheduler = newScheduler();

      assertTrue(scheduler.startSchedule(heldConfig().withSeed(7L)).isEmpty());

      State state = scheduler.state();
      assertTrue(state.running());
      assertEquals(7L, state.seedUsed());
      assertEquals(1, state.intervalCount());
    }

    @Test
    void shuttingDownRevertsFlagsThatAreStillHeld() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(scheduler.startSchedule(heldConfig()).isEmpty());
      String flag = assertReceive(inbox, singleActive(), 2000).active().get(0).flag();
      assertTrue(eventually(() -> !variantOf(flag).equals("off")));

      scheduler.shutdown();

      assertEquals("off", variantOf(flag));
    }

    @Test
    void restartingReplacesTheRunInProgressAndRevertsItsFlags() throws Exception {
      Scheduler scheduler = newScheduler();

      assertTrue(scheduler.startSchedule(heldConfig().withConcurrency(2).withSeed(5L)).isEmpty());
      State first = assertReceive(inbox, s -> s.active().size() == 2, 2000);
      List<String> firstFlags = first.active().stream().map(Activation::flag).toList();

      assertTrue(
          scheduler
              .startSchedule(
                  heldConfig().withFlags(Map.of("adHighCpu", List.of("on"))).withSeed(6L))
              .isEmpty());

      for (String flag : firstFlags) {
        if (!flag.equals("adHighCpu")) {
          assertEquals("off", variantOf(flag));
        }
      }
      assertEquals(6L, scheduler.state().seedUsed());
      assertEquals(1, scheduler.state().intervalCount());
    }
  }
}
