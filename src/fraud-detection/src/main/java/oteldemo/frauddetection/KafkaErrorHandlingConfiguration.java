/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * A record that cannot be handled (for example one that is not a valid {@code OrderResult}) is
 * logged and skipped, without retries.
 *
 * <p>The Kotlin service had no handler: the exception ended its main loop and the process, and
 * the container restarted it. This is the one intentional behavior difference, see
 * PORTING-NOTES.md.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderListener.GROUP_ID);

  @Bean
  CommonErrorHandler fraudDetectionKafkaErrorHandler() {
    return new DefaultErrorHandler(
        (record, exception) ->
            LOGGER.error(
                "Skipping record at {}-{}@{} that could not be processed",
                record.topic(),
                record.partition(),
                record.offset(),
                exception),
        new FixedBackOff(0L, 0L));
  }
}
