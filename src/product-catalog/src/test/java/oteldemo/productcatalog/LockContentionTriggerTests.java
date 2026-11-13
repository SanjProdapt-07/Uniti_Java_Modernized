/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** The productCatalogLockContention fault injection (triggerLockContentionLoop in main.go). */
class LockContentionTriggerTests {

  private DataSource dataSource;
  private Connection connection;
  private Statement statement;
  private Client flags;
  private LockContentionTrigger trigger;

  @BeforeEach
  void setUp() throws SQLException {
    dataSource = mock(DataSource.class);
    connection = mock(Connection.class);
    statement = mock(Statement.class);
    flags = mock(Client.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
  }

  @AfterEach
  void tearDown() {
    if (trigger != null) {
      trigger.stop();
    }
  }

  @Test
  void theFlagIsOffByDefaultAndNothingIsLocked() throws SQLException {
    when(flags.getBooleanValue("productCatalogLockContention", false)).thenReturn(false);
    trigger = newTrigger(Duration.ofMillis(50), Duration.ofSeconds(30));
    trigger.start();

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(flags, atLeast(3)).getBooleanValue("productCatalogLockContention", false));

    verify(dataSource, never()).getConnection();
  }

  @Test
  void whileTheFlagIsOnATransactionHoldsAnAccessExclusiveLock() throws Exception {
    when(flags.getBooleanValue("productCatalogLockContention", false)).thenReturn(true);
    trigger = newTrigger(Duration.ofMillis(50), Duration.ofSeconds(30));
    trigger.start();

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(statement).execute("LOCK TABLE catalog.products IN ACCESS EXCLUSIVE MODE"));

    verify(connection).setAutoCommit(false);
    // the lock is held (no rollback yet) and no second transaction is started meanwhile
    Thread.sleep(250);
    verify(connection, never()).rollback();
    verify(dataSource, times(1)).getConnection();
  }

  @Test
  void theLockIsReleasedByARollbackWhenTheHoldTimeIsOver() throws SQLException {
    when(flags.getBooleanValue("productCatalogLockContention", false)).thenReturn(true);
    trigger = newTrigger(Duration.ofHours(1), Duration.ofMillis(100));

    trigger.start();
    trigger.tick();

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(connection).rollback());
    InOrder order = inOrder(connection, statement);
    order.verify(connection).setAutoCommit(false);
    order.verify(statement).execute("LOCK TABLE catalog.products IN ACCESS EXCLUSIVE MODE");
    order.verify(connection).rollback();
    order.verify(connection).close();
  }

  @Test
  void anotherLockIsTakenAfterTheFirstOneIsReleased() throws SQLException {
    when(flags.getBooleanValue("productCatalogLockContention", false)).thenReturn(true);
    trigger = newTrigger(Duration.ofHours(1), Duration.ofMillis(50));
    trigger.start();

    trigger.tick();
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(connection, times(1)).rollback());
    trigger.tick();

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(dataSource, times(2)).getConnection());
  }

  @Test
  void stoppingTheServiceReleasesAHeldLock() throws SQLException {
    when(flags.getBooleanValue("productCatalogLockContention", false)).thenReturn(true);
    trigger = newTrigger(Duration.ofHours(1), Duration.ofSeconds(60));
    trigger.start();
    trigger.tick();
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(statement).execute("LOCK TABLE catalog.products IN ACCESS EXCLUSIVE MODE"));

    trigger.stop();

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(connection).rollback());
    assertThat(trigger.isRunning()).isFalse();
  }

  @Test
  void aFailureToGetAConnectionOrTheLockIsLoggedAndTheLoopGoesOn() throws SQLException {
    when(flags.getBooleanValue("productCatalogLockContention", false)).thenReturn(true);
    when(dataSource.getConnection()).thenThrow(new SQLException("no connection")).thenReturn(connection);
    when(statement.execute("LOCK TABLE catalog.products IN ACCESS EXCLUSIVE MODE")).thenThrow(new SQLException("denied"));
    trigger = newTrigger(Duration.ofHours(1), Duration.ofMillis(50));
    trigger.start();

    trigger.tick(); // no connection
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(dataSource, times(1)).getConnection());
    // the failed attempt must not leave the "locking" guard set
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              trigger.tick();
              verify(dataSource, atLeast(2)).getConnection();
            });
    // lock refused: the connection is closed without holding anything
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(connection, atLeastOnce()).close());
    verify(connection, never()).rollback();
  }

  private LockContentionTrigger newTrigger(Duration interval, Duration hold) {
    return new LockContentionTrigger(dataSource, flags, interval, hold);
  }
}
