/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.OpenTelemetry;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import oteldemo.Demo.OrderResult;

@ExtendWith(MockitoExtension.class)
class OrderConsumerTests {

  @Mock OrderStore store;
  @Mock ObjectProvider<OrderStore> storeProvider;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void captureLogs() {
    logger = (Logger) LoggerFactory.getLogger(OrderConsumer.NAME);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void stopCapturing() {
    logger.detachAppender(logs);
  }

  private OrderConsumer consumer(OrderStore available) {
    lenient().when(storeProvider.getIfAvailable()).thenReturn(available);
    return new OrderConsumer(OpenTelemetry.noop(), storeProvider, "kafka:9092");
  }

  @Test
  void parsesLogsAndStoresTheOrder() {
    OrderResult order = TestOrders.order("order-1", "track-1").build();

    consumer(store).processMessage(order.toByteArray());

    verify(store).save(order);
    assertThat(logs.list).hasSize(1);
    ILoggingEvent received = logs.list.get(0);
    assertThat(received.getLevel()).isEqualTo(Level.INFO);
    assertThat(received.getFormattedMessage())
        .startsWith("Order details: { \"orderId\": \"order-1\", \"shippingTrackingId\": \"track-1\"")
        .endsWith(" }.");
    assertThat(eventName(received)).isEqualTo("accounting.order.received");
  }

  @Test
  void withoutDatabaseTheOrderIsOnlyLogged() {
    consumer(null).processMessage(TestOrders.order("order-1", "track-1").build().toByteArray());

    assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).hasSize(1);
    verify(store, never()).save(any());
  }

  @Test
  void duplicateOrderIsSkippedAtInfoLevel() {
    doThrow(new DuplicateKeyException("dup", new SQLException("unique", OrderStore.UNIQUE_VIOLATION)))
        .when(store)
        .save(any());

    consumer(store).processMessage(TestOrders.order("order-1", "track-1").build().toByteArray());

    ILoggingEvent skipped = logs.list.get(logs.list.size() - 1);
    assertThat(skipped.getLevel()).isEqualTo(Level.INFO);
    assertThat(skipped.getFormattedMessage()).isEqualTo("Duplicate order received, skipping.");
    assertThat(eventName(skipped)).isEqualTo("accounting.order.duplicate_skipped");
    assertThat(skipped.getThrowableProxy()).isNull();
  }

  @Test
  void otherDatabaseFailuresAreLoggedAsErrorsAndSwallowed() {
    doThrow(new IllegalStateException("db down")).when(store).save(any());

    consumer(store).processMessage(TestOrders.order("order-1", "track-1").build().toByteArray());

    ILoggingEvent failed = logs.list.get(logs.list.size() - 1);
    assertThat(failed.getLevel()).isEqualTo(Level.ERROR);
    assertThat(failed.getFormattedMessage()).isEqualTo("Order parsing failed:");
    assertThat(failed.getThrowableProxy().getMessage()).isEqualTo("db down");
    assertThat(eventName(failed)).isEqualTo("accounting.order.parsing_failed");
  }

  @Test
  void unparseableMessageIsLoggedAsParsingFailureAndNotStored() {
    // a truncated length-delimited field
    consumer(store).processMessage(new byte[] {0x0A, 0x7F, 0x41});

    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo("Order parsing failed:");
    verify(store, never()).save(any());
  }

  @Test
  void nullValueTombstoneIsLoggedAsParsingFailure() {
    consumer(store).processMessage(null);

    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo("Order parsing failed:");
  }

  @Test
  void logsTheKafkaServersAtStartupAndClosingAtShutdown() {
    OrderConsumer consumer = new OrderConsumer(OpenTelemetry.noop(), mock(ObjectProvider.class), "kafka:9092");

    consumer.logConnecting();
    consumer.logClosing();

    assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("Connecting to Kafka: kafka:9092", "Closing consumer");
    assertThat(logs.list).extracting(this::eventName)
        .containsExactly("accounting.kafka.connecting", "accounting.consumer.closing");
  }

  private String eventName(ILoggingEvent event) {
    return event.getKeyValuePairs().stream()
        .filter(pair -> pair.key.equals("event.name"))
        .map(pair -> String.valueOf(pair.value))
        .findFirst()
        .orElse(null);
  }
}
