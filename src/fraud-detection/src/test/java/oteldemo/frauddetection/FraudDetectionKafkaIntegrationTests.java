/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
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
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import oteldemo.Demo.OrderResult;

/** The whole service against an in-JVM Kafka broker (no Docker) and an in-memory flag provider. */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "otel.instrumentation.kafka.experimental-span-attributes=true",
      "otel.instrumentation.messaging.experimental.receive-telemetry.enabled=true"
    })
@EmbeddedKafka(
    partitions = 1,
    topics = "orders",
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class FraudDetectionKafkaIntegrationTests {

  @Autowired EmbeddedKafkaBroker broker;
  @Autowired KafkaListenerEndpointRegistry registry;
  @Autowired ConsumerFactory<String, byte[]> consumerFactory;
  @Autowired InMemoryProvider featureFlags;
  @Autowired InMemorySpanExporter spans;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void setUp() throws Exception {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setFlags(false, 0);
    logger = (Logger) LoggerFactory.getLogger("fraud-detection");
    logs.start();
    logger.addAppender(logs);
    spans.reset();
    // the group membership loop (re)joins within a few hundred ms of the flag being off
    await().atMost(30, TimeUnit.SECONDS).until(() -> registry.getListenerContainer("fraud-detection").isRunning());
  }

  @AfterEach
  void tearDown() throws Exception {
    setFlags(false, 0);
    logger.detachAppender(logs);
    await().atMost(30, TimeUnit.SECONDS).until(() -> registry.getListenerContainer("fraud-detection").isRunning());
  }

  @Test
  void consumerIsConfiguredLikeTheKotlinService() {
    Map<String, Object> config = consumerFactory.getConfigurationProperties();

    assertThat(config).containsEntry("group.id", "fraud-detection");
    assertThat(config.get("auto.offset.reset")).isEqualTo("earliest");
    assertThat(String.valueOf(config.get("max.poll.records"))).isEqualTo("30");
    assertThat(String.valueOf(config.get("key.deserializer"))).contains("StringDeserializer");
    assertThat(String.valueOf(config.get("value.deserializer"))).contains("ByteArrayDeserializer");
  }

  @Test
  void consumesOrdersAndKeepsARunningTotal() {
    String prefix = "count-" + UUID.randomUUID() + "-";

    for (int i = 1; i <= 3; i++) {
      send(order(prefix + i), Map.of());
    }

    await().atMost(30, TimeUnit.SECONDS).until(() -> consumedWithPrefix(prefix).size() == 3);
    List<Long> totals = consumedWithPrefix(prefix).stream().map(FraudDetectionKafkaIntegrationTests::totalOf).toList();
    assertThat(totals.get(1)).isEqualTo(totals.get(0) + 1);
    assertThat(totals.get(2)).isEqualTo(totals.get(1) + 1);
  }

  @Test
  void kafkaQueueProblemsThrottlesEveryRecord() {
    String prefix = "slow-" + UUID.randomUUID() + "-";
    setFlags(false, 200);
    int before = (int) logs.list.stream().filter(e -> e.getFormattedMessage().contains("sleeping 200ms")).count();

    long start = System.nanoTime();
    for (int i = 1; i <= 3; i++) {
      send(order(prefix + i), Map.of());
    }
    await().atMost(30, TimeUnit.SECONDS).until(() -> consumedWithPrefix(prefix).size() == 3);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isGreaterThanOrEqualTo(600);
    assertThat(logs.list.stream().filter(e -> e.getFormattedMessage().equals("FeatureFlag 'kafkaQueueProblems' is enabled, sleeping 200ms")).count())
        .isGreaterThanOrEqualTo(before + 3);
  }

  @Test
  void kafkaConsumerDeadMakesTheGroupEmptyAndRecoversWithoutLosingOrders() throws Exception {
    String prefix = "dead-" + UUID.randomUUID() + "-";
    send(order(prefix + "before"), Map.of());
    await().atMost(30, TimeUnit.SECONDS).until(() -> consumedWithPrefix(prefix).size() == 1);
    assertThat(groupMembers()).isEqualTo(1);

    setFlags(true, 0);
    await().atMost(30, TimeUnit.SECONDS).until(() -> !registry.getListenerContainer("fraud-detection").isRunning());
    await().atMost(30, TimeUnit.SECONDS).until(() -> groupMembers() == 0);
    assertThat(messages())
        .contains(
            "FeatureFlag 'kafkaConsumerDead' is enabled, unsubscribing from 'orders' (group goes Empty, members -> 0)");

    // orders produced while the consumer is out of the group wait in the topic
    send(order(prefix + "during-1"), Map.of());
    send(order(prefix + "during-2"), Map.of());
    Thread.sleep(2500);
    assertThat(consumedWithPrefix(prefix)).hasSize(1);
    assertThat(groupMembers()).isZero();

    setFlags(false, 0);
    await().atMost(30, TimeUnit.SECONDS).until(() -> consumedWithPrefix(prefix).size() == 3);
    assertThat(messages())
        .contains("FeatureFlag 'kafkaConsumerDead' is disabled, re-subscribing to 'orders' (rejoining group)");
    assertThat(groupMembers()).isEqualTo(1);
  }

  @Test
  void aRecordThatIsNotAnOrderIsSkippedAndTheNextOneIsStillConsumed() {
    String prefix = "poison-" + UUID.randomUUID() + "-";
    sendRaw(new byte[] {0x0A, 0x7F, 0x41}, Map.of());
    send(order(prefix + "ok"), Map.of());

    await().atMost(30, TimeUnit.SECONDS).until(() -> consumedWithPrefix(prefix).size() == 1);
    assertThat(logs.list)
        .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR && e.getFormattedMessage().startsWith("Skipping record at orders-0@"));
  }

  @Test
  void continuesTheProducersTraceWithAConsumerSpan() {
    String orderId = "trace-" + UUID.randomUUID();
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String producerSpanId = "00f067aa0ba902b7";

    send(order(orderId), Map.of("traceparent", "00-" + traceId + "-" + producerSpanId + "-01"));

    await().atMost(30, TimeUnit.SECONDS).until(() -> !consumedWithPrefix(orderId).isEmpty());
    // With messaging receive telemetry enabled (OTEL_INSTRUMENTATION_MESSAGING_EXPERIMENTAL_RECEIVE_TELEMETRY_ENABLED,
    // set in compose) the process span belongs to the consumer's own trace and is linked to the producer.
    Optional<SpanData> process =
        await()
            .atMost(10, TimeUnit.SECONDS)
            .until(
                () ->
                    spans.getFinishedSpanItems().stream()
                        .filter(span -> span.getKind() == SpanKind.CONSUMER)
                        .filter(
                            span ->
                                span.getLinks().stream()
                                    .anyMatch(
                                        link ->
                                            link.getSpanContext().getTraceId().equals(traceId)
                                                && link.getSpanContext().getSpanId().equals(producerSpanId)))
                        .findFirst(),
                Optional::isPresent);
    SpanData processSpan = process.orElseThrow();
    assertThat(processSpan.getName()).isEqualTo("orders process");
    // experimental span attributes are switched on by OTEL_INSTRUMENTATION_KAFKA_EXPERIMENTAL_SPAN_ATTRIBUTES
    assertThat(processSpan.getAttributes().asMap().keySet().stream().map(Object::toString))
        .anyMatch(key -> key.startsWith("kafka."));
    // the producer's trace is only linked, not continued, once receive telemetry is enabled
    assertThat(processSpan.getTraceId()).isNotEqualTo(traceId);
  }

  private int groupMembers() throws Exception {
    try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", broker.getBrokersAsString()))) {
      var description = admin.describeConsumerGroups(List.of("fraud-detection")).describedGroups().get("fraud-detection").get(10, TimeUnit.SECONDS);
      return description.members().size();
    }
  }

  private static OrderResult order(String orderId) {
    return OrderResult.newBuilder().setOrderId(orderId).setShippingTrackingId("t-" + orderId).build();
  }

  private List<String> messages() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private List<String> consumedWithPrefix(String prefix) {
    return messages().stream().filter(m -> m.startsWith("Consumed record with orderId: " + prefix)).toList();
  }

  private static long totalOf(String message) {
    return Long.parseLong(message.substring(message.lastIndexOf(' ') + 1));
  }

  private void setFlags(boolean dead, int queueProblemsMs) {
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put(
        "kafkaConsumerDead",
        Flag.<Boolean>builder().variant("on", true).variant("off", false).defaultVariant(dead ? "on" : "off").build());
    flags.put(
        "kafkaQueueProblems",
        Flag.<Integer>builder()
            .variant("on", queueProblemsMs)
            .variant("off", 0)
            .defaultVariant(queueProblemsMs > 0 ? "on" : "off")
            .build());
    featureFlags.updateFlags(flags);
  }

  private void send(OrderResult order, Map<String, String> headers) {
    sendRaw(order.toByteArray(), headers);
  }

  private void sendRaw(byte[] value, Map<String, String> headers) {
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

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(InMemorySpanExporter spanExporter) {
      return customizer ->
          customizer.addTracerProviderCustomizer(
              (tracerProvider, config) -> tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)));
    }

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }
  }
}
