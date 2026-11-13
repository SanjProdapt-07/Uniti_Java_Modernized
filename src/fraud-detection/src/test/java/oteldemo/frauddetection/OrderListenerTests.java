/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import oteldemo.Demo.OrderResult;

@ExtendWith(MockitoExtension.class)
class OrderListenerTests {

  @Mock FeatureFlags featureFlags;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void captureLogs() {
    logger = (Logger) LoggerFactory.getLogger("fraud-detection");
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void stopCapturing() {
    logger.detachAppender(logs);
  }

  private static ConsumerRecord<String, byte[]> record(String orderId) {
    return new ConsumerRecord<>("orders", 0, 0L, null, OrderResult.newBuilder().setOrderId(orderId).build().toByteArray());
  }

  private List<String> messages() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Test
  void logsEveryRecordWithARunningTotal() throws Exception {
    when(featureFlags.getFeatureFlagValue("kafkaQueueProblems")).thenReturn(0);
    OrderListener listener = new OrderListener(featureFlags);

    listener.onRecord(record("order-a"));
    listener.onRecord(record("order-b"));
    listener.onRecord(record("order-c"));

    assertThat(messages())
        .containsExactly(
            "Consumed record with orderId: order-a, and updated total count to: 1",
            "Consumed record with orderId: order-b, and updated total count to: 2",
            "Consumed record with orderId: order-c, and updated total count to: 3");
    assertThat(listener.totalCount()).isEqualTo(3);
  }

  @Test
  void kafkaQueueProblemsSleepsForTheFlagValueBeforeEachRecord() throws Exception {
    when(featureFlags.getFeatureFlagValue("kafkaQueueProblems")).thenReturn(150);
    OrderListener listener = new OrderListener(featureFlags);

    long start = System.nanoTime();
    listener.onRecord(record("slow-1"));
    listener.onRecord(record("slow-2"));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isGreaterThanOrEqualTo(290);
    assertThat(messages())
        .containsExactly(
            "FeatureFlag 'kafkaQueueProblems' is enabled, sleeping 150ms",
            "Consumed record with orderId: slow-1, and updated total count to: 1",
            "FeatureFlag 'kafkaQueueProblems' is enabled, sleeping 150ms",
            "Consumed record with orderId: slow-2, and updated total count to: 2");
  }

  @Test
  void flagIsEvaluatedForEveryRecord() throws Exception {
    when(featureFlags.getFeatureFlagValue("kafkaQueueProblems")).thenReturn(0, 0, 20);
    OrderListener listener = new OrderListener(featureFlags);

    listener.onRecord(record("1"));
    listener.onRecord(record("2"));
    listener.onRecord(record("3"));

    assertThat(messages()).filteredOn(m -> m.contains("sleeping")).containsExactly("FeatureFlag 'kafkaQueueProblems' is enabled, sleeping 20ms");
  }

  @Test
  void negativeFlagValueDoesNotSleep() throws Exception {
    when(featureFlags.getFeatureFlagValue("kafkaQueueProblems")).thenReturn(-5);

    new OrderListener(featureFlags).onRecord(record("x"));

    assertThat(messages()).containsExactly("Consumed record with orderId: x, and updated total count to: 1");
  }

  @Test
  void unparseableRecordFails() {
    when(featureFlags.getFeatureFlagValue("kafkaQueueProblems")).thenReturn(0);
    OrderListener listener = new OrderListener(featureFlags);
    ConsumerRecord<String, byte[]> garbage = new ConsumerRecord<>("orders", 0, 0L, null, new byte[] {0x0A, 0x7F, 0x41});

    assertThatThrownBy(() -> listener.onRecord(garbage)).isInstanceOf(InvalidProtocolBufferException.class);
  }
}
