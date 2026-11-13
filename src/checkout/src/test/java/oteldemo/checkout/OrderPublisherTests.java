/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import io.grpc.Context;
import io.grpc.Deadline;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;

/** Producer behavior of the order event with a mocked KafkaTemplate (no broker needed). */
class OrderPublisherTests {

  private static final OrderResult ORDER =
      OrderResult.newBuilder()
          .setOrderId("order-1")
          .setShippingTrackingId("track-1")
          .addItems(
              OrderItem.newBuilder().setItem(CartItem.newBuilder().setProductId("P").setQuantity(1)))
          .build();

  private final InMemorySpanExporter spans = InMemorySpanExporter.create();
  private OpenTelemetrySdk openTelemetry;
  private KafkaTemplate<String, byte[]> template;
  private Client flags;
  private OrderPublisher publisher;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    openTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setPropagators(
                ContextPropagators.create(
                    TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
            .build();
    template = mock(KafkaTemplate.class);
    ObjectProvider<KafkaTemplate<String, byte[]>> provider = mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(template);
    flags = mock(Client.class);
    when(flags.getIntegerValue("kafkaQueueProblems", 0)).thenReturn(0);
    publisher = new OrderPublisher(provider, flags, openTelemetry, "orders");
  }

  @AfterEach
  void tearDown() {
    publisher.shutdown();
    openTelemetry.close();
  }

  @Test
  void publishesTheProtobufOrderAndRecordsTheProducerSpan() throws Exception {
    when(template.send(any(ProducerRecord.class))).thenReturn(completed(42L));

    Span parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();
    try (var ignored = parent.makeCurrent()) {
      publisher.publish(ORDER);
    } finally {
      parent.end();
    }

    ArgumentCaptor<ProducerRecord<String, byte[]>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
    verify(template).send(sent.capture());
    ProducerRecord<String, byte[]> record = sent.getValue();
    assertThat(record.topic()).isEqualTo("orders");
    assertThat(record.key()).isNull();
    assertThat(OrderResult.parseFrom(record.value())).isEqualTo(ORDER);

    SpanData producer = spanNamed("orders publish");
    assertThat(producer.getKind()).isEqualTo(SpanKind.PRODUCER);
    assertThat(producer.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
    assertThat(producer.getAttributes().get(AttributeKey.stringKey("peer.service"))).isEqualTo("kafka");
    assertThat(producer.getAttributes().get(AttributeKey.stringKey("network.transport"))).isEqualTo("tcp");
    assertThat(producer.getAttributes().get(AttributeKey.stringKey("messaging.system"))).isEqualTo("kafka");
    assertThat(producer.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
        .isEqualTo("orders");
    assertThat(producer.getAttributes().get(AttributeKey.stringKey("messaging.operation")))
        .isEqualTo("publish");
    assertThat(producer.getAttributes().get(AttributeKey.longKey("messaging.kafka.destination.partition")))
        .isEqualTo(0L);
    assertThat(producer.getAttributes().get(AttributeKey.booleanKey("messaging.kafka.producer.success")))
        .isTrue();
    assertThat(producer.getAttributes().get(AttributeKey.longKey("messaging.kafka.producer.duration_ms")))
        .isNotNull();
    assertThat(producer.getAttributes().get(AttributeKey.longKey("messaging.kafka.message.offset")))
        .isEqualTo(42L);
    assertThat(producer.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
  }

  @Test
  void injectsTheTraceContextAndBaggageIntoTheRecordHeaders() {
    when(template.send(any(ProducerRecord.class))).thenReturn(completed(0L));

    Span parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();
    Baggage baggage = Baggage.builder().put("synthetic_request", "true").build();
    try (var ignored = parent.makeCurrent();
        var ignoredBaggage = baggage.makeCurrent()) {
      publisher.publish(ORDER);
    } finally {
      parent.end();
    }

    ArgumentCaptor<ProducerRecord<String, byte[]>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
    verify(template).send(sent.capture());
    String traceparent = header(sent.getValue(), "traceparent");
    SpanData producer = spanNamed("orders publish");
    assertThat(traceparent)
        .startsWith("00-" + producer.getTraceId() + "-" + producer.getSpanId() + "-");
    assertThat(header(sent.getValue(), "baggage")).contains("synthetic_request=true");
  }

  @Test
  void marksTheSpanFailedWhenTheBrokerReportsAnError() {
    CompletableFuture<SendResult<String, byte[]>> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IllegalStateException("broker unavailable"));
    when(template.send(any(ProducerRecord.class))).thenReturn(failed);

    publisher.publish(ORDER);

    SpanData producer = spanNamed("orders publish");
    assertThat(producer.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(producer.getStatus().getDescription()).isEqualTo("broker unavailable");
    assertThat(producer.getAttributes().get(AttributeKey.booleanKey("messaging.kafka.producer.success")))
        .isFalse();
    assertThat(producer.getAttributes().get(AttributeKey.longKey("messaging.kafka.producer.duration_ms")))
        .isNotNull();
    assertThat(producer.getAttributes().get(AttributeKey.longKey("messaging.kafka.message.offset"))).isNull();
  }

  @Test
  void marksTheSpanFailedWhenTheSendCannotBeQueued() {
    when(template.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("queue full"));

    publisher.publish(ORDER);

    SpanData producer = spanNamed("orders publish");
    assertThat(producer.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(producer.getStatus().getDescription()).isEqualTo("Failed to send: queue full");
    assertThat(producer.getAttributes().get(AttributeKey.booleanKey("messaging.kafka.producer.success")))
        .isFalse();
    verify(flags, never()).getIntegerValue(any(), any());
  }

  @Test
  void stopsWaitingWhenTheRequestDeadlineExpires() throws Exception {
    when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      Context deadlineContext =
          Context.current().withDeadline(Deadline.after(200, TimeUnit.MILLISECONDS), scheduler);
      deadlineContext.run(() -> publisher.publish(ORDER));
    } finally {
      scheduler.shutdownNow();
    }

    SpanData producer = spanNamed("orders publish");
    assertThat(producer.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(producer.getStatus().getDescription())
        .isEqualTo("Context cancelled: context deadline exceeded");
    assertThat(producer.getAttributes().get(AttributeKey.booleanKey("messaging.kafka.producer.success")))
        .isFalse();
  }

  @Test
  void stopsWaitingWhenTheRequestIsCancelled() throws Exception {
    when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
    Context.CancellableContext cancellable = Context.current().withCancellation();
    Thread publishing = Thread.ofVirtual().start(() -> cancellable.run(() -> publisher.publish(ORDER)));
    await().atMost(5, TimeUnit.SECONDS).until(() -> mockingStarted());
    cancellable.cancel(new RuntimeException("client went away"));
    publishing.join(5_000);

    SpanData producer = spanNamed("orders publish");
    assertThat(producer.getStatus().getDescription()).isEqualTo("Context cancelled: context canceled");
  }

  @Test
  void queueProblemsFlagSendsExtraCopiesOfTheMessage() throws Exception {
    when(flags.getIntegerValue("kafkaQueueProblems", 0)).thenReturn(3);
    when(template.send(any(ProducerRecord.class))).thenReturn(completed(1L));

    publisher.publish(ORDER);

    ArgumentCaptor<ProducerRecord<String, byte[]>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> verify(template, org.mockito.Mockito.times(4)).send(sent.capture()));
    List<ProducerRecord<String, byte[]>> records = new ArrayList<>(sent.getAllValues());
    assertThat(records).hasSize(4);
    for (ProducerRecord<String, byte[]> record : records) {
      assertThat(record.topic()).isEqualTo("orders");
      assertThat(OrderResult.parseFrom(record.value())).isEqualTo(ORDER);
      // the copies carry the same trace headers as the original message
      assertThat(header(record, "traceparent")).isEqualTo(header(records.get(0), "traceparent"));
    }
  }

  @Test
  void noExtraCopiesWithoutTheFlag() {
    when(template.send(any(ProducerRecord.class))).thenReturn(completed(1L));

    publisher.publish(ORDER);

    verify(template, org.mockito.Mockito.times(1)).send(any(ProducerRecord.class));
  }

  @Test
  void topicDefaultsToOrdersWhenEmpty() {
    @SuppressWarnings("unchecked")
    ObjectProvider<KafkaTemplate<String, byte[]>> provider = mock(ObjectProvider.class);

    assertThat(new OrderPublisher(provider, flags, openTelemetry, "").topic()).isEqualTo("orders");
    assertThat(new OrderPublisher(provider, flags, openTelemetry, "custom").topic()).isEqualTo("custom");
  }

  private boolean mockingStarted() {
    return org.mockito.Mockito.mockingDetails(template).getInvocations().stream()
        .anyMatch(invocation -> invocation.getMethod().getName().equals("send"));
  }

  private SpanData spanNamed(String name) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () ->
                spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals(name))
                    .findFirst(),
            java.util.Optional::isPresent)
        .orElseThrow();
  }

  private static String header(ProducerRecord<String, byte[]> record, String key) {
    var header = record.headers().lastHeader(key);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private static CompletableFuture<SendResult<String, byte[]>> completed(long offset) {
    RecordMetadata metadata = new RecordMetadata(new TopicPartition("orders", 0), offset, 0, 0L, 0, 0);
    return CompletableFuture.completedFuture(
        new SendResult<>(new ProducerRecord<>("orders", null), metadata));
  }
}
