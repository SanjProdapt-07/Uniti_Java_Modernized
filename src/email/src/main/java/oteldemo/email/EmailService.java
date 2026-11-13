/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.email;

import dev.openfeature.sdk.Client;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Port of {@code send_email} of {@code email_server.rb}. */
@Service
public class EmailService {

  private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

  static final String FROM = "noreply@example.com";
  static final String SUBJECT = "Your confirmation email";
  private static final AttributeKey<String> ORDER_ID = AttributeKey.stringKey("demo.order.id");

  private final Client featureFlags;
  private final ConfirmationTemplate template;
  private final MailOutbox outbox;
  private final Tracer tracer;
  private final io.opentelemetry.api.logs.Logger otelLogger;
  private final LongCounter confirmations;

  public EmailService(
      Client featureFlags,
      ConfirmationTemplate template,
      MailOutbox outbox,
      OpenTelemetry openTelemetry) {
    this.featureFlags = featureFlags;
    this.template = template;
    this.outbox = outbox;
    this.tracer = openTelemetry.getTracer("email");
    this.otelLogger = openTelemetry.getLogsBridge().loggerBuilder("email").build();
    this.confirmations =
        openTelemetry
            .getMeter("email")
            .counterBuilder("demo.notification.confirmations")
            .setUnit("1")
            .setDescription("Counts the number of order confirmation emails sent")
            .build();
  }

  /** Counts a confirmation, as the route handler of the original did before sending. */
  public void countConfirmation() {
    confirmations.add(1);
  }

  /**
   * Renders the confirmation and "sends" it to {@code to}.
   *
   * @param to the customer's address ({@code email} of the request), may be null
   * @param order the {@code OrderResult} object of the request
   * @param orderId its {@code order_id} text, null if absent
   */
  public void sendEmail(String to, JsonNode order, String orderId) {
    Span span = tracer.spanBuilder("send_email").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      // Check if memory leak flag is enabled
      double memoryLeakMultiplier = featureFlags.getDoubleValue("emailMemoryLeak", 0.0);

      // To speed up the memory leak we create a long email body
      String confirmationContent = template.render(order);
      double whitespaceLength = Math.max(0, confirmationContent.length() * (memoryLeakMultiplier - 1));

      outbox.deliver(
          new MailOutbox.Mail(
              to,
              FROM,
              SUBJECT,
              confirmationContent + " ".repeat((int) Math.min(whitespaceLength, Integer.MAX_VALUE))));

      // If not clearing the deliveries, the emails will accumulate in the outbox.
      // We use this to create a memory leak.
      if (memoryLeakMultiplier < 1) {
        outbox.clear();
      }

      if (orderId != null) {
        span.setAttribute(ORDER_ID, orderId);
      }
      LogRecordBuilder log =
          otelLogger
              .logRecordBuilder()
              .setTimestamp(Instant.now())
              .setSeverityText("INFO")
              .setSeverity(Severity.INFO)
              .setBody("Order confirmation email sent")
              .setEventName("email.confirmation_sent");
      if (orderId != null) {
        log.setAttribute(ORDER_ID, orderId);
      }
      log.emit();

      logger.info("Order confirmation email sent for order {}", orderId == null ? "" : orderId);
    } catch (RuntimeException e) {
      // Ruby's in_span records the exception and marks the span failed
      span.recordException(e);
      span.setStatus(StatusCode.ERROR, "Unhandled exception of type: " + e.getClass().getSimpleName());
      throw e;
    } finally {
      span.end();
    }
  }
}
