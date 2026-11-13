/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import oteldemo.Demo.OrderResult;

/**
 * Consumes {@code OrderResult} messages from the orders topic and stores them. Port of the .NET
 * {@code Consumer} background service: group {@code accounting}, offsets reset to the earliest
 * and auto committed (see {@code application.yaml}).
 */
@Component
class OrderConsumer {

  // The .NET service logged with the category "Accounting.Consumer" and traced with an
  // ActivitySource of the same name.
  static final String NAME = "Accounting.Consumer";

  private static final Logger LOGGER = LoggerFactory.getLogger(NAME);

  private final Tracer tracer;
  private final ObjectProvider<OrderStore> orderStore;
  private final String servers;

  OrderConsumer(
      OpenTelemetry openTelemetry,
      ObjectProvider<OrderStore> orderStore,
      @Value("${spring.kafka.bootstrap-servers}") String servers) {
    this.tracer = openTelemetry.getTracer(NAME);
    this.orderStore = orderStore;
    this.servers = servers;
  }

  @PostConstruct
  void logConnecting() {
    LOGGER
        .atInfo()
        .addKeyValue("event.name", "accounting.kafka.connecting")
        .log("Connecting to Kafka: {}", servers);
  }

  @EventListener(ContextClosedEvent.class)
  void logClosing() {
    LOGGER.atInfo().addKeyValue("event.name", "accounting.consumer.closing").log("Closing consumer");
  }

  @KafkaListener(id = "accounting", groupId = "accounting", topics = "${accounting.topic}")
  void onMessage(ConsumerRecord<String, byte[]> record) {
    // Child of the Kafka "process" span (context extracted from the record headers by the
    // OpenTelemetry Kafka instrumentation).
    Span span = tracer.spanBuilder("order-consumed").setSpanKind(SpanKind.INTERNAL).startSpan();
    try (Scope ignored = span.makeCurrent()) {
      processMessage(record.value());
    } finally {
      span.end();
    }
  }

  void processMessage(byte[] value) {
    try {
      OrderResult order = OrderResult.parseFrom(value);
      LOGGER
          .atInfo()
          .addKeyValue("event.name", "accounting.order.received")
          .log("Order details: {}.", ProtoDiagnosticFormat.toDiagnosticString(order));

      OrderStore store = orderStore.getIfAvailable();
      if (store == null) {
        // DB_CONNECTION_STRING is not set
        return;
      }
      store.save(order);
    } catch (Exception ex) {
      if (OrderStore.isUniqueViolation(ex)) {
        LOGGER
            .atInfo()
            .addKeyValue("event.name", "accounting.order.duplicate_skipped")
            .log("Duplicate order received, skipping.");
      } else {
        LOGGER
            .atError()
            .addKeyValue("event.name", "accounting.order.parsing_failed")
            .setCause(ex)
            .log("Order parsing failed:");
      }
    }
  }
}
