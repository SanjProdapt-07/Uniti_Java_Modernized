/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

@ExtendWith(MockitoExtension.class)
class ConsumerGroupMembershipTests {

  @Mock FeatureFlags featureFlags;
  @Mock KafkaListenerEndpointRegistry registry;
  @Mock MessageListenerContainer container;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger logger;
  private ConsumerGroupMembership membership;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger("fraud-detection");
    logs.start();
    logger.addAppender(logs);

    org.mockito.Mockito.lenient().when(registry.getListenerContainer("fraud-detection")).thenReturn(container);
    org.mockito.Mockito.lenient().when(container.isRunning()).thenAnswer(i -> running.get());
    org.mockito.Mockito.lenient().doAnswer(i -> { running.set(true); return null; }).when(container).start();
    org.mockito.Mockito.lenient().doAnswer(i -> { running.set(false); return null; }).when(container).stop();
    membership = new ConsumerGroupMembership(featureFlags, registry, "orders");
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
  }

  private void dead(boolean value) {
    when(featureFlags.getBooleanFeatureFlagValue("kafkaConsumerDead")).thenReturn(value);
  }

  @Test
  void joinsTheGroupWithoutLoggingWhenTheFlagIsOff() {
    dead(false);

    long wait = membership.tick();

    assertThat(running).isTrue();
    assertThat(wait).isEqualTo(100);
    assertThat(logs.list).isEmpty();
  }

  @Test
  void leavesTheGroupWhenTheFlagTurnsOn() {
    dead(false);
    membership.tick();
    dead(true);

    long wait = membership.tick();

    assertThat(running).isFalse();
    assertThat(wait).isEqualTo(1000);
    assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "FeatureFlag 'kafkaConsumerDead' is enabled, unsubscribing from 'orders' (group goes Empty, members -> 0)");
  }

  @Test
  void unsubscribesOnlyOnceWhileTheFlagStaysOn() {
    dead(false);
    membership.tick();
    dead(true);

    membership.tick();
    membership.tick();
    membership.tick();

    verify(container, org.mockito.Mockito.times(1)).stop();
    assertThat(logs.list).hasSize(1);
  }

  @Test
  void rejoinsWhenTheFlagTurnsOffAgain() {
    dead(false);
    membership.tick();
    dead(true);
    membership.tick();
    dead(false);

    long wait = membership.tick();

    assertThat(running).isTrue();
    assertThat(wait).isEqualTo(100);
    assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
        .endsWith("FeatureFlag 'kafkaConsumerDead' is disabled, re-subscribing to 'orders' (rejoining group)");
    verify(container, org.mockito.Mockito.times(2)).start();
  }

  @Test
  void neverJoinsWhileTheFlagIsOnFromTheStart() {
    dead(true);

    assertThat(membership.tick()).isEqualTo(1000);
    assertThat(membership.tick()).isEqualTo(1000);

    verify(container, never()).start();
    verify(container, never()).stop();
    assertThat(logs.list).isEmpty();
  }

  @Test
  void aRunningConsumerIsNotStartedAgain() {
    dead(false);
    membership.tick();
    membership.tick();

    verify(container, org.mockito.Mockito.times(1)).start();
  }

  @Test
  void missingContainerIsAnError() {
    when(registry.getListenerContainer("fraud-detection")).thenReturn(null);

    assertThatThrownBy(membership::tick).isInstanceOf(IllegalStateException.class);
  }
}
