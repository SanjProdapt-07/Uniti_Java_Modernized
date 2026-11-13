/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import dev.openfeature.sdk.Client;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Fault injection behind the {@code productCatalogLockContention} feature flag: every 10 seconds
 * the flag is evaluated and, while it is on and no lock is currently held, a transaction takes an
 * {@code ACCESS EXCLUSIVE} lock on {@code catalog.products} and holds it for 30 seconds (or until
 * shutdown), which stalls every other query on the table.
 */
@Component
class LockContentionTrigger implements SmartLifecycle {

  private static final Logger logger = LoggerFactory.getLogger(LockContentionTrigger.class);

  static final String LOCK_CONTENTION_FLAG = "productCatalogLockContention";
  static final String LOCK_SQL = "LOCK TABLE catalog.products IN ACCESS EXCLUSIVE MODE";

  private final DataSource dataSource;
  private final Client featureFlags;
  private final Duration interval;
  private final Duration hold;

  private final AtomicBoolean locking = new AtomicBoolean(false);
  private final CountDownLatch stopped = new CountDownLatch(1);
  private volatile ScheduledExecutorService scheduler;
  private volatile ScheduledExecutorService lockRunner;

  LockContentionTrigger(
      DataSource dataSource,
      Client featureFlags,
      @Value("${product-catalog.lock-contention.interval:10s}") Duration interval,
      @Value("${product-catalog.lock-contention.hold:30s}") Duration hold) {
    this.dataSource = dataSource;
    this.featureFlags = featureFlags;
    this.interval = interval;
    this.hold = hold;
  }

  @Override
  public synchronized void start() {
    if (scheduler != null) {
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> daemon(runnable, "lock-contention-ticker"));
    lockRunner =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> daemon(runnable, "lock-contention-holder"));
    scheduler.scheduleAtFixedRate(this::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    stopped.countDown();
    if (scheduler != null) {
      scheduler.shutdownNow();
      lockRunner.shutdownNow();
      scheduler = null;
    }
  }

  @Override
  public boolean isRunning() {
    return scheduler != null;
  }

  /** One evaluation of the flag; starts a lock-holding transaction when needed. */
  void tick() {
    try {
      boolean enabled = featureFlags.getBooleanValue(LOCK_CONTENTION_FLAG, false);
      if (enabled && locking.compareAndSet(false, true)) {
        lockRunner.execute(
            () -> {
              try {
                triggerLockContention();
              } finally {
                locking.set(false);
              }
            });
      }
    } catch (RuntimeException e) {
      logger.error("lock contention check failed", e);
    }
  }

  /** Takes the lock and holds it; blocks for the hold time. */
  void triggerLockContention() {
    Connection connection;
    try {
      connection = dataSource.getConnection();
      connection.setAutoCommit(false);
    } catch (SQLException e) {
      logger.atError().addKeyValue("error", e.getMessage()).setCause(e).log("failed to begin lock contention transaction");
      return;
    }

    try (connection) {
      try (Statement statement = connection.createStatement()) {
        statement.execute(LOCK_SQL);
      } catch (SQLException e) {
        logger
            .atError()
            .addKeyValue("error", e.getMessage())
            .setCause(e)
            .log("failed to acquire lock for lock contention scenario");
        return;
      }

      logger.info("lock contention scenario active: holding ACCESS EXCLUSIVE lock on catalog.products");
      try {
        stopped.await(hold.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      rollback(connection);
    } catch (SQLException e) {
      logger.warn("failed to close lock contention connection", e);
    }
  }

  private static void rollback(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException e) {
      logger.warn("failed to roll back lock contention transaction", e);
    }
  }

  private static Thread daemon(Runnable runnable, String name) {
    Thread thread = new Thread(runnable, name);
    thread.setDaemon(true);
    return thread;
  }
}
