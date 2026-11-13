/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import oteldemo.Demo.OrderResult;

/**
 * Runs the whole service against an in-JVM Kafka broker (no Docker) and an in-memory H2 database in
 * PostgreSQL mode.
 */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "DB_CONNECTION_STRING=jdbc:h2:mem:accounting-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
          + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:accounting-schema.sql'",
      "spring.kafka.consumer.properties.metadata.max.age.ms=1000"
    })
@EmbeddedKafka(
    partitions = 1,
    topics = "orders",
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class AccountingKafkaIntegrationTests {

  @Autowired EmbeddedKafkaBroker broker;
  @Autowired DataSource dataSource;
  @Autowired InMemorySpanExporter spans;
  @Autowired InMemoryLogRecordExporter logRecords;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private JdbcTemplate jdbc;
  private Logger logger;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    logger = (Logger) LoggerFactory.getLogger(OrderConsumer.NAME);
    logs.start();
    logger.addAppender(logs);
    spans.reset();
    logRecords.reset();
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
  }

  @Test
  void consumesAnOrderFromTheTopicAndStoresIt() {
    String orderId = "order-" + UUID.randomUUID();

    send(TestOrders.order(orderId, "track-" + orderId).build().toByteArray(), Map.of());

    await().atMost(30, TimeUnit.SECONDS).until(() -> orderRows(orderId) == 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounting.orderitem WHERE order_id = ?", Integer.class, orderId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT shipping_tracking_id FROM accounting.shipping WHERE order_id = ?", String.class, orderId))
        .isEqualTo("track-" + orderId);
    assertThat(messages()).anyMatch(m -> m.startsWith("Order details: { \"orderId\": \"" + orderId + "\""));
  }

  @Test
  void duplicateOrderIsSkippedAndTheConsumerKeepsRunning() {
    String orderId = "order-" + UUID.randomUUID();
    byte[] order = TestOrders.order(orderId, "track-" + orderId).build().toByteArray();

    send(order, Map.of());
    send(order, Map.of());

    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(() -> logs.list.stream().anyMatch(e -> e.getFormattedMessage().equals("Duplicate order received, skipping.")));
    assertThat(orderRows(orderId)).isEqualTo(1);

    String next = "order-" + UUID.randomUUID();
    send(TestOrders.order(next, "track-" + next).build().toByteArray(), Map.of());
    await().atMost(30, TimeUnit.SECONDS).until(() -> orderRows(next) == 1);
  }

  @Test
  void garbageMessageIsLoggedAndTheNextOrderIsStillProcessed() {
    send(new byte[] {0x0A, 0x7F, 0x41}, Map.of());
    String orderId = "order-" + UUID.randomUUID();
    send(TestOrders.order(orderId, "track-" + orderId).build().toByteArray(), Map.of());

    await().atMost(30, TimeUnit.SECONDS).until(() -> orderRows(orderId) == 1);
    assertThat(logs.list)
        .anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().equals("Order parsing failed:"));
  }

  @Test
  void continuesTheTraceOfTheProducerAndCreatesTheOrderConsumedSpan() {
    String orderId = "order-" + UUID.randomUUID();
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String producerSpanId = "00f067aa0ba902b7";

    send(
        TestOrders.order(orderId, "track-" + orderId).build().toByteArray(),
        Map.of("traceparent", "00-" + traceId + "-" + producerSpanId + "-01"));

    await().atMost(30, TimeUnit.SECONDS).until(() -> orderRows(orderId) == 1);

    SpanData consumed = awaitSpan(span -> span.getName().equals("order-consumed"));
    assertThat(consumed.getKind()).isEqualTo(SpanKind.INTERNAL);
    assertThat(consumed.getInstrumentationScopeInfo().getName()).isEqualTo("Accounting.Consumer");
    // propagated through the Kafka record headers
    assertThat(consumed.getTraceId()).isEqualTo(traceId);

    SpanData process =
        awaitSpan(span -> span.getSpanId().equals(consumed.getParentSpanId()));
    assertThat(process.getKind()).isEqualTo(SpanKind.CONSUMER);
    assertThat(process.getName()).contains("orders");
    assertThat(process.getParentSpanId()).isEqualTo(producerSpanId);
  }

  @Test
  void databaseCallsAreTracedInsideTheOrderConsumedSpan() {
    String orderId = "order-" + UUID.randomUUID();

    send(TestOrders.order(orderId, "track-" + orderId).build().toByteArray(), Map.of());
    await().atMost(30, TimeUnit.SECONDS).until(() -> orderRows(orderId) == 1);

    SpanData consumed = awaitSpan(span -> span.getName().equals("order-consumed"));
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(spans.getFinishedSpanItems())
                    .anyMatch(
                        span ->
                            span.getKind() == SpanKind.CLIENT
                                && span.getTraceId().equals(consumed.getTraceId())
                                && span.getName().toUpperCase().contains("INSERT")));
  }

  private int orderRows(String orderId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM accounting.\"order\" WHERE order_id = ?", Integer.class, orderId);
  }

  private List<String> messages() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private SpanData awaitSpan(java.util.function.Predicate<SpanData> predicate) {
    return await()
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> spans.getFinishedSpanItems().stream().filter(predicate).findFirst(), Optional::isPresent)
        .orElseThrow();
  }

  private void send(byte[] value, Map<String, String> headers) {
    Map<String, Object> props = KafkaTestUtils.producerProps(broker);
    props.put("key.serializer", StringSerializer.class);
    props.put("value.serializer", ByteArraySerializer.class);
    try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>("orders", null, null, value);
      headers.forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
      producer.send(record);
      producer.flush();
    }
  }

  @Test
  void exportsTheOrderLogsWithTheirEventNames() {
    String orderId = "order-" + UUID.randomUUID();

    send(TestOrders.order(orderId, "track-" + orderId).build().toByteArray(), Map.of());
    await().atMost(30, TimeUnit.SECONDS).until(() -> orderRows(orderId) == 1);

    LogRecordData record =
        await()
            .atMost(10, TimeUnit.SECONDS)
            .until(
                () ->
                    logRecords.getFinishedLogRecordItems().stream()
                        .filter(r -> r.getBodyValue() != null && r.getBodyValue().asString().contains(orderId))
                        .findFirst(),
                Optional::isPresent)
            .orElseThrow();
    assertThat(record.getInstrumentationScopeInfo().getName()).isEqualTo("Accounting.Consumer");
    assertThat(record.getBodyValue().asString()).startsWith("Order details: { \"orderId\": \"" + orderId + "\"");
    assertThat(record.getAttributes().asMap().toString() + record.getEventName())
        .contains("accounting.order.received");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    InMemoryLogRecordExporter inMemoryLogRecordExporter() {
      return InMemoryLogRecordExporter.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(
        InMemorySpanExporter spanExporter, InMemoryLogRecordExporter logExporter) {
      return customizer ->
          customizer
              .addTracerProviderCustomizer(
                  (tracerProvider, config) -> tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addLoggerProviderCustomizer(
                  (loggerProvider, config) ->
                      loggerProvider.addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter)));
    }
  }
}
