/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Periodically activates randomly picked feature flags for a random amount of time, so that
 * failure scenarios appear and disappear on their own without external tooling.
 *
 * <p>Each interval activates up to {@code concurrency} distinct flags. Every activation gets its
 * own hold duration picked between the configured minimum and maximum, and its own offset, so that
 * the whole activation fits inside the interval. Providing a seed makes the sequence of picks
 * reproducible across runs. A flag is picked first and one of its selected variants second, so a
 * flag with many variants is no more likely to be chosen than a flag with one.
 */
@Service
public class Scheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(Scheduler.class);

  private static final int HISTORY_LIMIT = 10;

  /**
   * Scheduler configuration.
   *
   * @param flags {@code null} for all schedulable flags, otherwise a map of flag name to the list of
   *     variants that may be activated
   */
  public record Config(
      long intervalMs,
      long minDurationMs,
      long maxDurationMs,
      int concurrency,
      Long seed,
      Map<String, List<String>> flags) {

    public static final Config DEFAULT = new Config(15 * 60 * 1000L, 60 * 1000L, 3 * 60 * 1000L, 1, null, null);

    public Config withIntervalMs(long value) {
      return new Config(value, minDurationMs, maxDurationMs, concurrency, seed, flags);
    }

    public Config withMinDurationMs(long value) {
      return new Config(intervalMs, value, maxDurationMs, concurrency, seed, flags);
    }

    public Config withMaxDurationMs(long value) {
      return new Config(intervalMs, minDurationMs, value, concurrency, seed, flags);
    }

    public Config withConcurrency(int value) {
      return new Config(intervalMs, minDurationMs, maxDurationMs, value, seed, flags);
    }

    public Config withSeed(Long value) {
      return new Config(intervalMs, minDurationMs, maxDurationMs, concurrency, value, flags);
    }

    public Config withFlags(Map<String, List<String>> value) {
      return new Config(intervalMs, minDurationMs, maxDurationMs, concurrency, seed, value);
    }
  }

  /** A flag with the variants it may be switched to by the scheduler. */
  public record SchedulableFlag(String name, List<String> variants) {}

  /** A currently held activation. {@code until} is a monotonic-millisecond deadline. */
  public record Activation(
      String flag,
      String variant,
      String restingVariant,
      long durationMs,
      Instant startedAt,
      long until) {}

  /** An activation that is planned for later within the current interval. */
  public record Pending(String flag, String variant, long durationMs, long at) {}

  /** Snapshot of the scheduler suitable for rendering. */
  public record State(
      Config config,
      boolean running,
      Long seedUsed,
      long intervalCount,
      List<Pending> nextUp,
      List<Activation> active,
      List<Activation> history,
      Long nextTriggerAt) {}

  private record EligibleFlag(String name, String resting, List<String> activatable) {}

  private record Plan(
      String flag, String variant, String restingVariant, long durationMs, long offsetMs) {}

  private final Storage storage;
  private final ScheduledThreadPoolExecutor executor;
  private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();

  // guarded by "this"
  private Config config = Config.DEFAULT;
  private boolean running;
  private long epoch;
  private Random rand;
  private Long seedUsed;
  private long intervalCount;
  private List<Pending> nextUp = new ArrayList<>();
  private final Map<String, Activation> active = new HashMap<>();
  private List<Activation> history = new ArrayList<>();
  private final Map<String, ScheduledFuture<?>> timers = new HashMap<>();
  private List<EligibleFlag> eligible = new ArrayList<>();

  public Scheduler(Storage storage) {
    this.storage = storage;
    this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
      Thread thread = new Thread(runnable, "flag-scheduler");
      thread.setDaemon(true);
      return thread;
    });
    this.executor.setRemoveOnCancelPolicy(true);
  }

  /** Monotonic clock in milliseconds, comparable with {@link Activation#until()}. */
  public static long monotonicMillis() {
    return System.nanoTime() / 1_000_000L;
  }

  /** Registers a listener that receives the state after every change (the PubSub topic). */
  public void addListener(Consumer<State> listener) {
    listeners.add(listener);
  }

  public void removeListener(Consumer<State> listener) {
    listeners.remove(listener);
  }

  /** Returns the scheduler state suitable for rendering. */
  public synchronized State state() {
    return publicState();
  }

  /**
   * Starts scheduling with the given configuration, replacing any run in progress.
   *
   * @return empty when started, otherwise the reason the configuration is not usable
   */
  public synchronized Optional<String> startSchedule(Config newConfig) {
    Optional<String> error = validate(newConfig);
    if (error.isPresent()) {
      return error;
    }

    long seed = newConfig.seed() != null ? newConfig.seed() : uniquePositive();
    Random newRand = new Random(seed);
    cancelTimers();
    revertAll();

    // Frozen for the run: the flags' own resting variants are read back from storage as this run
    // writes over them, so re-deriving mid-run would mistake a flag's currently active variant for
    // its rest state.
    List<EligibleFlag> newEligible = eligibleFlagSpecs(storage.read());

    this.config = newConfig;
    this.eligible = newEligible;
    this.running = true;
    this.epoch++;
    this.rand = newRand;
    this.seedUsed = seed;
    this.intervalCount = 0;
    this.history = new ArrayList<>();
    beginInterval();

    LOGGER.info("Flag scheduler started with seed {}", seed);

    broadcast();
    return Optional.empty();
  }

  /** Stops scheduling and reverts any flag currently held active. */
  public synchronized void stopSchedule() {
    cancelTimers();
    revertAll();
    running = false;
    epoch++;
    nextUp = new ArrayList<>();

    LOGGER.info("Flag scheduler stopped");

    broadcast();
  }

  @PreDestroy
  void shutdown() {
    synchronized (this) {
      cancelTimers();
      revertAll();
    }
    executor.shutdownNow();
  }

  /**
   * Returns the flags eligible for scheduling, given a flagd configuration.
   *
   * <p>A flag is eligible when it has a resting variant, its configured default, to return to, at
   * least one other variant to switch to, and metadata is not configured to opt out of scheduling
   * ({@code metadata.schedulable: false}).
   */
  public static List<SchedulableFlag> schedulableFlags(Object flagdConfig) {
    return eligibleFlagSpecs(flagdConfig).stream()
        .map(spec -> new SchedulableFlag(spec.name(), spec.activatable()))
        .toList();
  }

  private static List<EligibleFlag> eligibleFlagSpecs(Object flagdConfig) {
    List<EligibleFlag> specs = new ArrayList<>();
    if (flagdConfig instanceof Map<?, ?> root && root.get("flags") instanceof Map<?, ?> flags) {
      for (Map.Entry<?, ?> entry : flags.entrySet()) {
        flagSpec(String.valueOf(entry.getKey()), entry.getValue()).ifPresent(specs::add);
      }
    }
    specs.sort(Comparator.comparing(EligibleFlag::name));
    return specs;
  }

  private static Optional<EligibleFlag> flagSpec(String name, Object data) {
    if (!(data instanceof Map<?, ?> dataMap)) {
      return Optional.empty();
    }
    List<String> variants = variantNames(dataMap);
    Object resting = dataMap.get("defaultVariant");
    List<String> activatable = new ArrayList<>(variants);
    activatable.remove(resting);
    activatable.sort(Comparator.naturalOrder());
    boolean schedulable = true;
    if (dataMap.get("metadata") instanceof Map<?, ?> metadata) {
      schedulable = !Boolean.FALSE.equals(metadata.get("schedulable"));
    }
    if (schedulable && resting instanceof String restingName && variants.contains(restingName)
        && !activatable.isEmpty()) {
      return Optional.of(new EligibleFlag(name, restingName, activatable));
    }
    return Optional.empty();
  }

  private static List<String> variantNames(Map<?, ?> data) {
    List<String> names = new ArrayList<>();
    if (data.get("variants") instanceof Map<?, ?> variants) {
      variants.keySet().forEach(key -> names.add(String.valueOf(key)));
    }
    return names;
  }

  // ---------------------------------------------------------------------------------------------
  // Timer handlers; each re-checks the epoch under the lock so stale timers are no-ops.

  private void onBeginInterval(long timerEpoch) {
    synchronized (this) {
      if (!running || timerEpoch != epoch) {
        return;
      }
      dropTimer("interval");
      beginInterval();
      broadcast();
    }
  }

  private void onTrigger(
      long timerEpoch, String flag, String variant, String restingVariant, long durationMs) {
    synchronized (this) {
      if (!running || timerEpoch != epoch) {
        return;
      }
      // The same flag can be picked again while a previous hold is still running, so retire that
      // activation before this one takes the flag over.
      dropTimer("trigger:" + flag);
      cancelTimer("revert:" + flag);
      finishActivation(flag);

      writeFlag(flag, variant);

      LOGGER.info("Flag scheduler set {} to {} for {}ms", flag, variant, durationMs);

      ScheduledFuture<?> revertTimer =
          executor.schedule(() -> onRevert(timerEpoch, flag), durationMs, TimeUnit.MILLISECONDS);

      Activation activation =
          new Activation(
              flag,
              variant,
              restingVariant,
              durationMs,
              Instant.now(),
              monotonicMillis() + durationMs);

      timers.put("revert:" + flag, revertTimer);
      active.put(flag, activation);
      nextUp = new ArrayList<>(nextUp.stream().filter(p -> !p.flag().equals(flag)).toList());

      broadcast();
    }
  }

  private void onRevert(long timerEpoch, String flag) {
    synchronized (this) {
      if (!running || timerEpoch != epoch) {
        return;
      }
      dropTimer("revert:" + flag);
      revertFlag(flag);
      finishActivation(flag);
      broadcast();
    }
  }

  // ---------------------------------------------------------------------------------------------

  private void beginInterval() {
    long intervalEpoch = epoch;
    ScheduledFuture<?> intervalTimer =
        executor.schedule(
            () -> onBeginInterval(intervalEpoch), config.intervalMs(), TimeUnit.MILLISECONDS);
    timers.put("interval", intervalTimer);
    intervalCount++;

    List<EligibleFlag> flags = eligibleFlags();
    int wanted = Math.min(config.concurrency(), flags.size());

    if (wanted == 0) {
      LOGGER.warn("Flag scheduler has no eligible flags selected, skipping interval");
      nextUp = new ArrayList<>();
      return;
    }
    if (config.concurrency() > wanted) {
      LOGGER.warn(
          "Flag scheduler was asked for {} concurrent flags but only {} are selected",
          config.concurrency(),
          wanted);
    }

    List<Plan> plans = planActivations(new ArrayList<>(flags), wanted);
    nextUp = new ArrayList<>();
    for (Plan plan : plans) {
      scheduleActivation(plan, intervalEpoch);
    }
  }

  private void scheduleActivation(Plan plan, long planEpoch) {
    ScheduledFuture<?> timer =
        executor.schedule(
            () ->
                onTrigger(
                    planEpoch,
                    plan.flag(),
                    plan.variant(),
                    plan.restingVariant(),
                    plan.durationMs()),
            plan.offsetMs(),
            TimeUnit.MILLISECONDS);
    Pending pending =
        new Pending(
            plan.flag(), plan.variant(), plan.durationMs(), monotonicMillis() + plan.offsetMs());
    timers.put("trigger:" + plan.flag(), timer);
    nextUp.add(pending);
  }

  /** Picks {@code wanted} distinct flags, consuming randomness in a fixed order for seeding. */
  private List<Plan> planActivations(List<EligibleFlag> flags, int wanted) {
    List<Plan> plans = new ArrayList<>();
    int remaining = wanted;
    while (remaining > 0 && !flags.isEmpty()) {
      EligibleFlag picked = flags.get(uniform(flags.size()));
      long durationMs = randomInRange(config.minDurationMs(), config.maxDurationMs());
      long offsetMs = randomInRange(0, config.intervalMs() - durationMs);
      String variant = picked.activatable().get(uniform(picked.activatable().size()));
      plans.add(new Plan(picked.name(), variant, picked.resting(), durationMs, offsetMs));
      flags.remove(picked);
      remaining--;
    }
    return plans;
  }

  private List<EligibleFlag> eligibleFlags() {
    List<EligibleFlag> flags = new ArrayList<>();
    for (EligibleFlag spec : eligible) {
      List<String> allowed;
      if (config.flags() == null) {
        allowed = spec.activatable();
      } else {
        List<String> chosen = config.flags().getOrDefault(spec.name(), List.of());
        allowed = spec.activatable().stream().filter(chosen::contains).toList();
      }
      if (!allowed.isEmpty()) {
        flags.add(new EligibleFlag(spec.name(), spec.resting(), allowed));
      }
    }
    return flags;
  }

  private void revertAll() {
    for (String flag : new ArrayList<>(active.keySet())) {
      revertFlag(flag);
      finishActivation(flag);
    }
  }

  private void revertFlag(String flag) {
    Activation activation = active.get(flag);
    if (activation == null) {
      return;
    }
    writeFlag(flag, activation.restingVariant());
    LOGGER.info("Flag scheduler reverted {} to {}", flag, activation.restingVariant());
  }

  private void writeFlag(String flag, String variant) {
    try {
      storage.write(flag, variant);
    } catch (RuntimeException e) {
      LOGGER.error("Flag scheduler could not write {} = {}", flag, variant, e);
    }
  }

  private void finishActivation(String flag) {
    Activation activation = active.remove(flag);
    if (activation != null) {
      List<Activation> updated = new ArrayList<>();
      updated.add(activation);
      updated.addAll(history);
      history = new ArrayList<>(updated.subList(0, Math.min(updated.size(), HISTORY_LIMIT)));
    }
  }

  private static Optional<String> validate(Config config) {
    if (config.intervalMs() < 1000) {
      return Optional.of("Interval must be at least 1 second");
    }
    if (config.minDurationMs() < 1000) {
      return Optional.of("Minimum duration must be at least 1 second");
    }
    if (config.maxDurationMs() < config.minDurationMs()) {
      return Optional.of(
          "Maximum duration must be greater than or equal to the minimum duration");
    }
    if (config.maxDurationMs() > config.intervalMs()) {
      return Optional.of("Maximum duration must fit within the interval");
    }
    if (config.concurrency() < 1) {
      return Optional.of("At least one flag has to be activated per interval");
    }
    if (config.flags() != null && selectedVariantCount(config.flags()) == 0) {
      return Optional.of("Select at least one flag variant to schedule");
    }
    return Optional.empty();
  }

  private static int selectedVariantCount(Map<String, List<String>> selection) {
    return selection.values().stream().mapToInt(List::size).sum();
  }

  private static long uniquePositive() {
    return ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);
  }

  private int uniform(int bound) {
    return (int) rand.nextLong(bound);
  }

  private long randomInRange(long min, long max) {
    if (max > min) {
      return min + rand.nextLong(max - min + 1);
    }
    return min;
  }

  private void dropTimer(String key) {
    timers.remove(key);
  }

  private void cancelTimers() {
    timers.values().forEach(timer -> timer.cancel(false));
    timers.clear();
  }

  private void cancelTimer(String key) {
    ScheduledFuture<?> timer = timers.remove(key);
    if (timer != null) {
      timer.cancel(false);
    }
  }

  private State publicState() {
    List<Activation> activeList =
        active.values().stream().sorted(Comparator.comparing(Activation::flag)).toList();
    Long nextTriggerAt = nextUp.stream().map(Pending::at).min(Long::compare).orElse(null);
    return new State(
        config,
        running,
        seedUsed,
        intervalCount,
        List.copyOf(nextUp),
        activeList,
        List.copyOf(history),
        nextTriggerAt);
  }

  private void broadcast() {
    State snapshot = publicState();
    for (Consumer<State> listener : listeners) {
      listener.accept(snapshot);
    }
  }
}
