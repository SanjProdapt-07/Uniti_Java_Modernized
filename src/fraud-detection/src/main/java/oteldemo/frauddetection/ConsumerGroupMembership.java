/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Drives the membership of the {@code fraud-detection} consumer group from the {@code
 * kafkaConsumerDead} feature flag, replacing the control flow of the Kotlin main loop:
 *
 * <ul>
 *   <li>flag on: the consumer leaves the group and stops polling (the group goes Empty, members
 *       drop to 0); the flag is re-checked once a second;
 *   <li>flag off again: the consumer re-subscribes (rejoins the group) and continues from the
 *       committed offsets;
 *   <li>flag off: the flag is re-checked every 100 ms, the poll timeout of the Kotlin loop.
 * </ul>
 *
 * Stopping a Spring Kafka listener container closes its Kafka consumer, which makes it leave the
 * group, and starting it again creates a new consumer that rejoins. As in the Kotlin service the
 * consumer only joins the group once the flag has been evaluated as off.
 */
@Component
class ConsumerGroupMembership implements SmartLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderListener.GROUP_ID);

  static final long ALIVE_CHECK_INTERVAL_MS = 100;
  static final long DEAD_CHECK_INTERVAL_MS = 1000;

  private final FeatureFlags featureFlags;
  private final KafkaListenerEndpointRegistry registry;
  private final String topic;

  /** True while the consumer is out of the group because of the flag. */
  private boolean leftBecauseOfFlag = false;

  private volatile Thread thread;

  ConsumerGroupMembership(
      FeatureFlags featureFlags,
      KafkaListenerEndpointRegistry registry,
      @org.springframework.beans.factory.annotation.Value("${fraud-detection.topic}") String topic) {
    this.featureFlags = featureFlags;
    this.registry = registry;
    this.topic = topic;
  }

  /**
   * One iteration of the loop.
   *
   * @return how long to wait before the next iteration
   */
  long tick() {
    MessageListenerContainer container = registry.getListenerContainer(OrderListener.GROUP_ID);
    if (container == null) {
      throw new IllegalStateException("Listener container '" + OrderListener.GROUP_ID + "' not found");
    }
    if (featureFlags.getBooleanFeatureFlagValue(FeatureFlags.KAFKA_CONSUMER_DEAD)) {
      // kafkaConsumerDead: leave the group and stop polling; re-subscribe when off
      if (container.isRunning()) {
        LOGGER.info(
            "FeatureFlag 'kafkaConsumerDead' is enabled, unsubscribing from '{}' (group goes Empty, members -> 0)",
            topic);
        container.stop();
        leftBecauseOfFlag = true;
      }
      // Do not poll while dead; sleep to avoid a busy loop.
      return DEAD_CHECK_INTERVAL_MS;
    }
    if (!container.isRunning()) {
      if (leftBecauseOfFlag) {
        LOGGER.info(
            "FeatureFlag 'kafkaConsumerDead' is disabled, re-subscribing to '{}' (rejoining group)", topic);
        leftBecauseOfFlag = false;
      }
      container.start();
    }
    return ALIVE_CHECK_INTERVAL_MS;
  }

  @Override
  public void start() {
    Thread loop =
        new Thread(
            () -> {
              while (!Thread.currentThread().isInterrupted()) {
                long wait;
                try {
                  wait = tick();
                } catch (RuntimeException e) {
                  LOGGER.error("Consumer group membership check failed", e);
                  wait = DEAD_CHECK_INTERVAL_MS;
                }
                try {
                  Thread.sleep(wait);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            "fraud-detection-membership");
    // not a daemon thread: it keeps the JVM alive while the consumer is out of the group
    loop.setDaemon(false);
    thread = loop;
    loop.start();
  }

  @Override
  public void stop() {
    Thread loop = thread;
    if (loop != null) {
      loop.interrupt();
      try {
        loop.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      thread = null;
    }
  }

  @Override
  public boolean isRunning() {
    return thread != null;
  }

  @Override
  public int getPhase() {
    // stop before the listener containers (the registry uses DEFAULT_PHASE) are stopped
    return Integer.MAX_VALUE;
  }
}
