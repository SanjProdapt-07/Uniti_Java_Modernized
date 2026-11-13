/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import dev.openfeature.sdk.Client;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import oteldemo.Demo.OrderResult;

/**
 * Publishes the order event to Kafka (the Go {@code sendToPostProcessor} and
 * {@code createProducerSpan}): protobuf {@code OrderResult} payload, a PRODUCER span with the
 * messaging attributes, trace context injected into the record headers, and the
 * {@code kafkaQueueProblems} feature flag that floods the queue with copies of the message.
 */
@Component
class OrderPublisher {

  private static final Logger logger = LoggerFactory.getLogger(OrderPublisher.class);

  private static final String QUEUE_PROBLEMS_FLAG = "kafkaQueueProblems";
  private static final String DEFAULT_TOPIC = "orders";

  private static final AttributeKey<String> PEER_SERVICE = AttributeKey.stringKey("peer.service");
  private static final AttributeKey<String> NETWORK_TRANSPORT =
      AttributeKey.stringKey("network.transport");
  private static final AttributeKey<String> MESSAGING_SYSTEM =
      AttributeKey.stringKey("messaging.system");
  private static final AttributeKey<String> MESSAGING_DESTINATION_NAME =
      AttributeKey.stringKey("messaging.destination.name");
  private static final AttributeKey<String> MESSAGING_OPERATION =
      AttributeKey.stringKey("messaging.operation");
  private static final AttributeKey<Long> MESSAGING_KAFKA_PARTITION =
      AttributeKey.longKey("messaging.kafka.destination.partition");
  private static final AttributeKey<Long> MESSAGING_KAFKA_OFFSET =
      AttributeKey.longKey("messaging.kafka.message.offset");
  private static final AttributeKey<Boolean> PRODUCER_SUCCESS =
      AttributeKey.booleanKey("messaging.kafka.producer.success");
  private static final AttributeKey<Long> PRODUCER_DURATION_MS =
      AttributeKey.longKey("messaging.kafka.producer.duration_ms");

  private static final TextMapSetter<Headers> HEADER_SETTER =
      (headers, key, value) -> {
        if (headers != null) {
          headers.remove(key);
          headers.add(key, value.getBytes(StandardCharsets.UTF_8));
        }
      };

  private final ObjectProvider<KafkaTemplate<String, byte[]>> kafkaTemplate;
  private final Client featureFlags;
  private final Tracer tracer;
  private final TextMapPropagator propagator;
  private final String topic;
  private final ExecutorService overloadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  OrderPublisher(
      ObjectProvider<KafkaTemplate<String, byte[]>> kafkaTemplate,
      Client featureFlags,
      OpenTelemetry openTelemetry,
      @Value("${checkout.kafka.topic:" + DEFAULT_TOPIC + "}") String topic) {
    this.kafkaTemplate = kafkaTemplate;
    this.featureFlags = featureFlags;
    this.tracer = openTelemetry.getTracer("checkout");
    this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
    this.topic = topic == null || topic.isEmpty() ? DEFAULT_TOPIC : topic;
  }

  String topic() {
    return topic;
  }

  @PreDestroy
  void shutdown() {
    overloadExecutor.shutdown();
  }

  void publish(OrderResult result) {
    byte[] message = result.toByteArray();
    KafkaTemplate<String, byte[]> template = kafkaTemplate.getObject();
    ProducerRecord<String, byte[]> record =
        new ProducerRecord<>(topic, null, null, null, message, new RecordHeaders());

    // Inject tracing info into message
    Span span = createProducerSpan(record);
    try {
      long startNanos = System.nanoTime();
      CompletableFuture<SendResult<String, byte[]>> sent;
      try {
        sent = template.send(record);
      } catch (RuntimeException e) {
        span.setAttribute(PRODUCER_SUCCESS, false);
        span.setAttribute(PRODUCER_DURATION_MS, elapsedMillis(startNanos));
        span.setStatus(StatusCode.ERROR, "Failed to send: " + e.getMessage());
        logger.error("Failed to send message to Kafka within context deadline: {}", e.toString());
        return;
      }

      // Send message and handle response
      io.grpc.Context requestContext = io.grpc.Context.current();
      try {
        SendResult<String, byte[]> success = awaitResult(sent, requestContext);
        span.setAttribute(PRODUCER_SUCCESS, true);
        span.setAttribute(PRODUCER_DURATION_MS, elapsedMillis(startNanos));
        span.setAttribute(MESSAGING_KAFKA_OFFSET, success.getRecordMetadata().offset());
        logger.info(
            "Successful to write message. offset: {}, duration: {}",
            success.getRecordMetadata().offset(),
            formatDuration(startNanos));
      } catch (ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        span.setAttribute(PRODUCER_SUCCESS, false);
        span.setAttribute(PRODUCER_DURATION_MS, elapsedMillis(startNanos));
        span.setStatus(StatusCode.ERROR, String.valueOf(cause.getMessage()));
        logger.error("Failed to write message: {}", cause.toString());
      } catch (RequestContextDone e) {
        span.setAttribute(PRODUCER_SUCCESS, false);
        span.setAttribute(PRODUCER_DURATION_MS, elapsedMillis(startNanos));
        span.setStatus(StatusCode.ERROR, "Context cancelled: " + e.reason());
        logger.warn("Context canceled before success message received: {}", e.reason());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        span.setAttribute(PRODUCER_SUCCESS, false);
        span.setAttribute(PRODUCER_DURATION_MS, elapsedMillis(startNanos));
        span.setStatus(StatusCode.ERROR, "Context cancelled: context canceled");
        logger.warn("Context canceled before success message received: context canceled");
      }

      overloadQueueIfRequested(template, record);
    } finally {
      span.end();
    }
  }

  private Span createProducerSpan(ProducerRecord<String, byte[]> record) {
    Span span =
        tracer
            .spanBuilder(topic + " publish")
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute(PEER_SERVICE, "kafka")
            .setAttribute(NETWORK_TRANSPORT, "tcp")
            .setAttribute(MESSAGING_SYSTEM, "kafka")
            .setAttribute(MESSAGING_DESTINATION_NAME, topic)
            .setAttribute(MESSAGING_OPERATION, "publish")
            // the Go service reads msg.Partition before the send, which is always 0
            .setAttribute(MESSAGING_KAFKA_PARTITION, 0L)
            .startSpan();
    propagator.inject(Context.current().with(span), record.headers(), HEADER_SETTER);
    return span;
  }

  /** The kafkaQueueProblems flag: that many extra copies of the message, sent concurrently. */
  private void overloadQueueIfRequested(
      KafkaTemplate<String, byte[]> template, ProducerRecord<String, byte[]> record) {
    Integer problems = featureFlags.getIntegerValue(QUEUE_PROBLEMS_FLAG, 0);
    long copies = problems == null ? 0 : problems;
    if (copies > 0) {
      logger.info("Warning: FeatureFlag 'kafkaQueueProblems' is activated, overloading queue now.");
      for (long i = 0; i < copies; i++) {
        ProducerRecord<String, byte[]> copy =
            new ProducerRecord<>(
                record.topic(),
                null,
                null,
                null,
                record.value(),
                new RecordHeaders(record.headers().toArray()));
        overloadExecutor.execute(
            () -> {
              try {
                template.send(copy).get();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (ExecutionException | RuntimeException e) {
                logger.debug("Overload simulation message failed: {}", e.toString());
              }
            });
      }
      logger.info("Done with #{} messages for overload simulation.", copies);
    }
  }

  /**
   * Waits for the broker result, or for the inbound gRPC request to be cancelled or to run into
   * its deadline (the {@code ctx.Done()} branches of the original select).
   */
  private static SendResult<String, byte[]> awaitResult(
      CompletableFuture<SendResult<String, byte[]>> sent, io.grpc.Context requestContext)
      throws ExecutionException, InterruptedException {
    CompletableFuture<SendResult<String, byte[]>> waiter = new CompletableFuture<>();
    sent.whenComplete(
        (result, error) -> {
          if (error != null) {
            waiter.completeExceptionally(error);
          } else {
            waiter.complete(result);
          }
        });
    io.grpc.Context.CancellationListener listener =
        context -> waiter.completeExceptionally(new RequestContextDone(context));
    requestContext.addListener(listener, Runnable::run);
    try {
      return waiter.get();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RequestContextDone done) {
        throw done;
      }
      throw e;
    } finally {
      requestContext.removeListener(listener);
    }
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private static String formatDuration(long startNanos) {
    return String.format(Locale.ROOT, "%.3fms", (System.nanoTime() - startNanos) / 1_000_000.0);
  }

  /** The inbound request was cancelled or its deadline passed while waiting for the broker. */
  private static final class RequestContextDone extends RuntimeException {
    private final String reason;

    RequestContextDone(io.grpc.Context context) {
      super(null, null, false, false);
      this.reason =
          context.getDeadline() != null && context.getDeadline().isExpired()
              ? "context deadline exceeded"
              : "context canceled";
    }

    String reason() {
      return reason;
    }
  }
}
