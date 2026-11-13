/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consume failures (the .NET {@code ConsumeException}) are logged as {@code Consume error:
 * <reason>} and consumption continues with the next message, without retrying the failed one.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandlingConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderConsumer.NAME);

  @Bean
  CommonErrorHandler accountingKafkaErrorHandler() {
    return new DefaultErrorHandler(
        (record, exception) ->
            LOGGER
                .atError()
                .addKeyValue("event.name", "accounting.kafka.consume_failed")
                .setCause(exception)
                .log("Consume error: {}", exception.getMessage()),
        new FixedBackOff(0L, 0L));
  }
}
