/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import oteldemo.Demo.OrderResult;

/**
 * Handles one order record. The listener container is started and stopped by {@link
 * ConsumerGroupMembership} (feature flag {@code kafkaConsumerDead}), which is why the container
 * does not start automatically.
 */
@Component
class OrderListener {

  static final String GROUP_ID = "fraud-detection";

  // Name of the log4j logger of the Kotlin service
  private static final Logger LOGGER = LoggerFactory.getLogger(GROUP_ID);

  private final FeatureFlags featureFlags;
  private final AtomicLong totalCount = new AtomicLong();

  OrderListener(FeatureFlags featureFlags) {
    this.featureFlags = featureFlags;
  }

  @KafkaListener(
      id = GROUP_ID,
      groupId = GROUP_ID,
      topics = "${fraud-detection.topic}",
      autoStartup = "false")
  void onRecord(ConsumerRecord<String, byte[]> record) throws Exception {
    long newCount = totalCount.incrementAndGet();

    // Flag value = per-record sleep in ms
    int sleepMs = featureFlags.getFeatureFlagValue(FeatureFlags.KAFKA_QUEUE_PROBLEMS);
    if (sleepMs > 0) {
      LOGGER.info("FeatureFlag 'kafkaQueueProblems' is enabled, sleeping {}ms", sleepMs);
      Thread.sleep((long) sleepMs);
    }

    OrderResult orders = OrderResult.parseFrom(record.value());
    LOGGER.info(
        "Consumed record with orderId: {}, and updated total count to: {}",
        orders.getOrderId(),
        newCount);
  }

  long totalCount() {
    return totalCount.get();
  }
}
