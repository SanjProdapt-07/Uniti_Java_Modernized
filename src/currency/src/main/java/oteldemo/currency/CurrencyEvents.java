/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import org.springframework.stereotype.Component;

/**
 * Emits the OpenTelemetry log records the C++ service produced through {@code logger->Info(...)}
 * and {@code logger->Error(...)}: a named event, a body and optional attributes, under the
 * instrumentation scope {@code currency}.
 */
@Component
public class CurrencyEvents {

  private final Logger logger;

  public CurrencyEvents(OpenTelemetry openTelemetry) {
    this.logger = openTelemetry.getLogsBridge().loggerBuilder("currency").build();
  }

  public void info(String eventName, String message) {
    info(eventName, message, Attributes.empty());
  }

  public void info(String eventName, String message, Attributes attributes) {
    emit(Severity.INFO, eventName, message, attributes);
  }

  public void error(String eventName, String message) {
    emit(Severity.ERROR, eventName, message, Attributes.empty());
  }

  private void emit(Severity severity, String eventName, String message, Attributes attributes) {
    logger
        .logRecordBuilder()
        .setEventName(eventName)
        .setSeverity(severity)
        .setSeverityText(severity.name())
        .setBody(message)
        .setAllAttributes(attributes)
        .emit();
  }
}
