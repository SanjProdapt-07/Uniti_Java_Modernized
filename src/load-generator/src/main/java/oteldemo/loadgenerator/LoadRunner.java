/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locust's runner: spawns simulated users (each one on its own virtual thread) at the requested spawn rate,
 * keeps them running, scales them up and down on a repeated swarm, and stops them. User classes are mixed in
 * proportion to their weights.
 */
public class LoadRunner {

  private static final Logger log = LoggerFactory.getLogger(LoadRunner.class);

  /** Locust runner states shown by the UI. */
  public enum State {
    READY,
    SPAWNING,
    RUNNING,
    STOPPING,
    STOPPED;

    public String locustName() {
      return name().toLowerCase();
    }
  }

  /** A kind of simulated user and its relative weight. */
  public record UserClass(String name, int weight, Supplier<SimulatedUser> factory) {}

  private record RunningUser(int classIndex, Thread thread) {}

  private final List<UserClass> classes;
  private final RequestStats stats;
  private final LoadGeneratorMetrics metrics;
  private final Runnable onRunTimeFinished;
  private final Object lock = new Object();
  private final List<RunningUser> users = new ArrayList<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("load-runner-timer").daemon(true).factory());

  private volatile State state = State.READY;
  private volatile String host;
  private long generation;
  private int targetUsers;
  private double spawnRate = 1;
  private ScheduledFuture<?> runTimeTask;

  public LoadRunner(
      List<UserClass> classes,
      RequestStats stats,
      LoadGeneratorMetrics metrics,
      String host,
      Runnable onRunTimeFinished) {
    this.classes = classes.stream().filter(c -> c.weight() > 0).toList();
    this.stats = stats;
    this.metrics = metrics;
    this.host = host == null ? "" : host;
    this.onRunTimeFinished = onRunTimeFinished;
  }

  public State state() {
    return state;
  }

  public String host() {
    return host;
  }

  public void setHost(String host) {
    this.host = host == null ? "" : host;
  }

  public int userCount() {
    synchronized (lock) {
      return users.size();
    }
  }

  public List<UserClass> userClasses() {
    return classes;
  }

  /** Starts (or rescales) the swarm. A finished/ready runner starts from clean statistics. */
  public void start(int userCount, double spawnRate, Duration runTime) {
    if (classes.isEmpty()) {
      throw new IllegalStateException("No user classes with a weight above 0");
    }
    long myGeneration;
    synchronized (lock) {
      boolean fresh = state == State.READY || state == State.STOPPED;
      if (fresh) {
        stats.reset();
      }
      generation++;
      myGeneration = generation;
      this.targetUsers = userCount;
      this.spawnRate = spawnRate <= 0 ? 1 : spawnRate;
      state = State.SPAWNING;
      if (runTimeTask != null) {
        runTimeTask.cancel(false);
        runTimeTask = null;
      }
      if (runTime != null) {
        runTimeTask = scheduler.schedule(this::runTimeElapsed, runTime.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
    log.info("Ramping to {} users at a rate of {} per second", userCount, this.spawnRate);
    Thread.ofVirtual().name("load-spawner").start(() -> spawnLoop(myGeneration));
  }

  private void runTimeElapsed() {
    log.info("Run time limit reached. Stopping Locust-compatible runner.");
    stop();
    onRunTimeFinished.run();
  }

  private void spawnLoop(long myGeneration) {
    try {
      while (true) {
        long sleepMillis;
        synchronized (lock) {
          if (generation != myGeneration) {
            return;
          }
          int[] targets = targets(targetUsers);
          int[] counts = counts();
          boolean changed = false;
          for (int i = 0; i < classes.size() && !changed; i++) {
            if (counts[i] < targets[i]) {
              spawn(i);
              changed = true;
            }
          }
          for (int i = 0; i < classes.size() && !changed; i++) {
            if (counts[i] > targets[i]) {
              stopOne(i);
              changed = true;
            }
          }
          if (!changed) {
            state = State.RUNNING;
            log.info("All users spawned: {} users", users.size());
            return;
          }
          sleepMillis = (long) (1000 / spawnRate);
        }
        Thread.sleep(sleepMillis);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Stops all users; returns once they terminated (or after a few seconds). */
  public void stop() {
    List<RunningUser> toStop;
    synchronized (lock) {
      if (state == State.READY || state == State.STOPPED) {
        return;
      }
      generation++;
      state = State.STOPPING;
      if (runTimeTask != null) {
        runTimeTask.cancel(false);
        runTimeTask = null;
      }
      toStop = new ArrayList<>(users);
      users.clear();
    }
    toStop.forEach(u -> u.thread().interrupt());
    for (RunningUser user : toStop) {
      try {
        user.thread().join(Duration.ofSeconds(5));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    synchronized (lock) {
      // a new start() may have been issued meanwhile; only finish if still stopping
      if (state == State.STOPPING) {
        state = State.STOPPED;
      }
      metrics.setUserCount(users.size());
    }
    log.info("Stopped all users");
  }

  public void shutdown() {
    stop();
    scheduler.shutdownNow();
  }

  private void spawn(int classIndex) {
    SimulatedUser user = classes.get(classIndex).factory().get();
    Thread thread =
        Thread.ofVirtual()
            .name("user-" + classes.get(classIndex).name())
            .unstarted(
                () -> {
                  try {
                    user.run();
                  } catch (Throwable t) {
                    log.error("User {} crashed", classes.get(classIndex).name(), t);
                  }
                });
    RunningUser running = new RunningUser(classIndex, thread);
    users.add(running);
    metrics.setUserCount(users.size());
    thread.start();
  }

  private void stopOne(int classIndex) {
    for (int i = users.size() - 1; i >= 0; i--) {
      if (users.get(i).classIndex() == classIndex) {
        RunningUser removed = users.remove(i);
        removed.thread().interrupt();
        metrics.setUserCount(users.size());
        return;
      }
    }
  }

  private int[] counts() {
    int[] counts = new int[classes.size()];
    users.forEach(u -> counts[u.classIndex()]++);
    return counts;
  }

  /**
   * How many users of each class make up {@code total}: users are added one at a time to the class that is
   * furthest below its weight share, so the mix follows the weights as closely as whole users allow.
   */
  int[] targets(int total) {
    int[] counts = new int[classes.size()];
    double weightSum = classes.stream().mapToInt(UserClass::weight).sum();
    for (int n = 1; n <= total; n++) {
      int best = 0;
      double bestDeficit = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < classes.size(); i++) {
        double deficit = classes.get(i).weight() / weightSum * n - counts[i];
        if (deficit > bestDeficit + 1e-9) {
          bestDeficit = deficit;
          best = i;
        }
      }
      counts[best]++;
    }
    return counts;
  }
}
