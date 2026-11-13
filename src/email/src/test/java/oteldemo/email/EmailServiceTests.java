/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EmailServiceTests {

  private static final String ORDER =
      """
      {"order_id": "ord-1", "shipping_tracking_id": "t",
       "shipping_cost": {"currency_code": "USD", "units": 1, "nanos": 2},
       "shipping_address": {"city": "c", "country": "k", "zip_code": "z"},
       "items": [{"item": {"product_id": "P", "quantity": 1}, "cost": {"units": 2, "nanos": 3}}]}
      """;

  private final InMemorySpanExporter spans = InMemorySpanExporter.create();
  private final InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
  private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
  private final Client flags = mock(Client.class);
  private final MailOutbox outbox = new MailOutbox();
  private final ConfirmationTemplate template = new ConfirmationTemplate();
  private EmailService service;
  private JsonNode order;

  @BeforeEach
  void setUp() {
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans))
                    .build())
            .setLoggerProvider(
                SdkLoggerProvider.builder()
                    .addLogRecordProcessor(SimpleLogRecordProcessor.create(logs))
                    .build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build();
    when(flags.getDoubleValue(eq("emailMemoryLeak"), anyDouble())).thenReturn(0.0);
    service = new EmailService(flags, template, outbox, sdk);
    order = JsonMapper.builder().build().readTree(ORDER);
  }

  @Test
  void deliversTheRenderedConfirmationWithTheFixedSenderAndSubject() {
    String rendered = template.render(order);
    // keep the delivery inspectable: with multiplier 1 nothing is cleared
    when(flags.getDoubleValue(eq("emailMemoryLeak"), anyDouble())).thenReturn(1.0);

    service.sendEmail("someone@example.com", order, "ord-1");

    assertThat(outbox.deliveries())
        .singleElement()
        .satisfies(
            mail -> {
              assertThat(mail.to()).isEqualTo("someone@example.com");
              assertThat(mail.from()).isEqualTo("noreply@example.com");
              assertThat(mail.subject()).isEqualTo("Your confirmation email");
              assertThat(mail.body()).isEqualTo(rendered);
            });
  }

  @Test
  void clearsTheOutboxWhenTheMemoryLeakFlagIsOff() {
    service.sendEmail("a@example.com", order, "ord-1");
    service.sendEmail("b@example.com", order, "ord-1");

    assertThat(outbox.size()).isZero();
  }

  @Test
  void aMultiplierOfOneKeepsEveryEmailWithoutPadding() {
    when(flags.getDoubleValue(eq("emailMemoryLeak"), anyDouble())).thenReturn(1.0);
    int length = template.render(order).length();

    service.sendEmail("a@example.com", order, "ord-1");
    service.sendEmail("b@example.com", order, "ord-1");

    assertThat(outbox.deliveries()).hasSize(2).allSatisfy(mail -> assertThat(mail.body()).hasSize(length));
  }

  @Test
  void padsTheBodyAndKeepsEveryEmailWhenTheMemoryLeakFlagIsOn() {
    when(flags.getDoubleValue(eq("emailMemoryLeak"), anyDouble())).thenReturn(100.0);
    String rendered = template.render(order);

    service.sendEmail("a@example.com", order, "ord-1");
    service.sendEmail("b@example.com", order, "ord-1");

    assertThat(outbox.deliveries())
        .hasSize(2)
        .allSatisfy(
            mail -> {
              assertThat(mail.body()).hasSize(rendered.length() * 100);
              assertThat(mail.body()).startsWith(rendered);
              assertThat(mail.body().substring(rendered.length())).isBlank();
            });
  }

  @Test
  void recordsTheSendEmailSpanWithTheOrderId() {
    service.sendEmail("a@example.com", order, "ord-1");

    SpanData span = spans.getFinishedSpanItems().getFirst();
    assertThat(span.getName()).isEqualTo("send_email");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("demo.order.id"))).isEqualTo("ord-1");
    assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
  }

  @Test
  void emitsTheConfirmationSentLogEvent() {
    service.sendEmail("a@example.com", order, "ord-1");

    LogRecordData record = logs.getFinishedLogRecordItems().getFirst();
    assertThat(record.getBodyValue().asString()).isEqualTo("Order confirmation email sent");
    assertThat(record.getSeverity()).isEqualTo(Severity.INFO);
    assertThat(record.getSeverityText()).isEqualTo("INFO");
    assertThat(record.getEventName()).isEqualTo("email.confirmation_sent");
    assertThat(record.getAttributes().get(AttributeKey.stringKey("demo.order.id")))
        .isEqualTo("ord-1");
    // emitted inside the send_email span, so it is correlated with it
    assertThat(record.getSpanContext().getSpanId())
        .isEqualTo(spans.getFinishedSpanItems().getFirst().getSpanId());
  }

  @Test
  void countsConfirmations() {
    service.countConfirmation();
    service.countConfirmation();

    var metric =
        metrics.collectAllMetrics().stream()
            .filter(m -> m.getName().equals("demo.notification.confirmations"))
            .findFirst()
            .orElseThrow();
    assertThat(metric.getUnit()).isEqualTo("1");
    assertThat(metric.getDescription())
        .isEqualTo("Counts the number of order confirmation emails sent");
    assertThat(metric.getLongSumData().getPoints()).singleElement().satisfies(p -> assertThat(p.getValue()).isEqualTo(2));
  }

  @Test
  void aBlankRecipientFailsTheSendEmailSpan() {
    assertThatThrownBy(() -> service.sendEmail(null, order, "ord-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("To address may not be blank");

    SpanData span = spans.getFinishedSpanItems().getFirst();
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    assertThat(logs.getFinishedLogRecordItems()).isEmpty();
  }

  @Test
  void aTemplateFailureFailsTheSendEmailSpanAndDeliversNothing() {
    JsonNode broken = JsonMapper.builder().build().readTree("{\"order_id\": \"x\"}");

    assertThatThrownBy(() -> service.sendEmail("a@example.com", broken, "x"))
        .isInstanceOf(ConfirmationTemplate.TemplateRenderException.class);

    assertThat(spans.getFinishedSpanItems().getFirst().getStatus().getStatusCode())
        .isEqualTo(StatusCode.ERROR);
    assertThat(outbox.size()).isZero();
  }
}
