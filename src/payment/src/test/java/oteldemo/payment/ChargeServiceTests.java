/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import oteldemo.Demo.ChargeRequest;
import oteldemo.Demo.CreditCardInfo;
import oteldemo.Demo.Money;

class ChargeServiceTests {

  private static final String VISA = "4111111111111111";
  private static final String MASTERCARD = "5555555555554444";
  private static final Pattern UUID_V4 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

  private final InMemorySpanExporter spans = InMemorySpanExporter.create();
  private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
  private final Client flags = mock(Client.class);
  private final RandomGenerator random = mock(RandomGenerator.class);
  private ChargeService service;

  @BeforeEach
  void setUp() {
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans))
                    .build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build();
    // 2026-11-13 is "now" for the expiry rule
    Clock clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC);
    when(flags.getDoubleValue(eq("paymentFailure"), anyDouble())).thenReturn(0.0);
    when(flags.getBooleanValue(eq("emitRawPii"), anyBoolean())).thenReturn(false);
    when(random.nextDouble()).thenReturn(0.99);
    when(random.nextInt(4)).thenReturn(2); // silver
    service = new ChargeService(flags, sdk, clock, random);
  }

  @Test
  void chargesAValidVisaAndReturnsAUuidTransactionId() {
    String transactionId = service.charge(request(VISA, 123, 2030, 1));

    assertThat(transactionId).matches(UUID_V4);
    SpanData span = onlySpan();
    assertThat(span.getName()).isEqualTo("charge");
    assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    assertThat(string(span, "demo.payment.card_type")).isEqualTo("visa");
    assertThat(bool(span, "demo.payment.card_valid")).isTrue();
    assertThat(string(span, "demo.user_context.loyalty_level")).isEqualTo("silver");
    assertThat(bool(span, "demo.payment.charged")).isTrue();
    assertThat(string(span, "demo.payment.card_number")).isNull();
    assertThat(string(span, "user_agent.synthetic.type")).isNull();
  }

  @Test
  void chargesAMasterCard() {
    assertThat(service.charge(request(MASTERCARD, 123, 2030, 1))).matches(UUID_V4);
    assertThat(string(onlySpan(), "demo.payment.card_type")).isEqualTo("mastercard");
  }

  @Test
  void generatesADifferentTransactionIdEachTime() {
    assertThat(service.charge(request(VISA, 1, 2030, 1)))
        .isNotEqualTo(service.charge(request(VISA, 1, 2030, 1)));
  }

  @Test
  void picksTheLoyaltyLevelFromTheFourLevels() {
    when(random.nextInt(4)).thenReturn(0, 1, 2, 3);
    for (String expected : new String[] {"platinum", "gold", "silver", "bronze"}) {
      spans.reset();
      service.charge(request(VISA, 1, 2030, 1));
      assertThat(string(onlySpan(), "demo.user_context.loyalty_level")).isEqualTo(expected);
    }
  }

  @Test
  void countsTransactionsPerCurrency() {
    service.charge(request(VISA, 1, 2030, 1));
    service.charge(request(VISA, 1, 2030, 1));

    var usdPoints =
        metrics.collectAllMetrics().stream()
            .filter(metric -> metric.getName().equals("demo.payment.transactions"))
            .peek(metric -> assertThat(metric.getUnit()).isEqualTo("{transaction}"))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .filter(
                point ->
                    "USD"
                        .equals(
                            point
                                .getAttributes()
                                .get(AttributeKey.stringKey("demo.payment.currency"))))
            .mapToLong(LongPointData::getValue)
            .sum();
    assertThat(usdPoints).isEqualTo(2);
  }

  @Test
  void rejectsAnInvalidCard() {
    assertThatThrownBy(() -> service.charge(request("4111111111111112", 1, 2030, 1)))
        .isInstanceOf(PaymentException.class)
        .hasMessage("Credit card info is invalid.");

    SpanData span = onlySpan();
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(span.getStatus().getDescription()).isEqualTo("Credit card info is invalid.");
    assertThat(string(span, "error.type")).isEqualTo("Error");
    assertThat(bool(span, "demo.payment.card_valid")).isFalse();
    assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    assertThat(bool(span, "demo.payment.charged")).isNull();
  }

  @Test
  void rejectsAnUnknownCardAsInvalid() {
    assertThatThrownBy(() -> service.charge(request("1234567812345670", 1, 2030, 1)))
        .hasMessage("Credit card info is invalid.");
    assertThat(string(onlySpan(), "demo.payment.card_type")).isEqualTo("unknown");
  }

  @Test
  void onlyAcceptsVisaAndMasterCard() {
    assertThatThrownBy(() -> service.charge(request("378282246310005", 1234, 2030, 1)))
        .isInstanceOf(PaymentException.class)
        .hasMessage(
            "Sorry, we cannot process amex credit cards. Only VISA or MasterCard is accepted.");
    assertThat(bool(onlySpan(), "demo.payment.card_valid")).isTrue();
  }

  @Test
  void rejectsAnExpiredCardWithItsLastFourDigits() {
    assertThatThrownBy(() -> service.charge(request(VISA, 1, 2026, 10)))
        .hasMessage("The credit card (ending 1111) expired on 10/2026.");
    assertThatThrownBy(() -> service.charge(request(VISA, 1, 2025, 12)))
        .hasMessage("The credit card (ending 1111) expired on 12/2025.");
  }

  @Test
  void acceptsACardExpiringThisMonth() {
    assertThat(service.charge(request(VISA, 1, 2026, 11))).matches(UUID_V4);
  }

  @Test
  void neverCallsACardExpiredWhenTheYearOrMonthIsMissing() {
    // proto3 omits zero values; Node.js saw `undefined`, so its comparison was false
    assertThat(service.charge(request(VISA, 1, 0, 0))).matches(UUID_V4);
    assertThat(service.charge(request(VISA, 1, 2000, 0))).matches(UUID_V4);
  }

  @Test
  void failsAtTheConfiguredRateWithTheGoldLoyaltyLevel() {
    when(flags.getDoubleValue(eq("paymentFailure"), anyDouble())).thenReturn(0.25);
    when(random.nextDouble()).thenReturn(0.24);

    assertThatThrownBy(() -> service.charge(request(VISA, 1, 2030, 1)))
        .isInstanceOf(PaymentException.class)
        .hasMessage("Payment request failed. Invalid token. demo.user_context.loyalty_level=gold");

    SpanData span = onlySpan();
    assertThat(string(span, "demo.user_context.loyalty_level")).isEqualTo("gold");
    assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(string(span, "error.type")).isEqualTo("Error");
    assertThat(string(span, "demo.payment.card_type")).isNull();
  }

  @Test
  void succeedsWhenTheFailureRollIsAboveTheRate() {
    when(flags.getDoubleValue(eq("paymentFailure"), anyDouble())).thenReturn(0.25);
    when(random.nextDouble()).thenReturn(0.25);

    assertThat(service.charge(request(VISA, 1, 2030, 1))).matches(UUID_V4);
  }

  @Test
  void aFailureRateOfOneAlwaysFails() {
    when(flags.getDoubleValue(eq("paymentFailure"), anyDouble())).thenReturn(1.0);
    when(random.nextDouble()).thenReturn(0.999999);

    assertThatThrownBy(() -> service.charge(request(VISA, 1, 2030, 1)))
        .hasMessageContaining("Invalid token");
  }

  @Test
  void addsTheRawCardAttributesOnlyWhenEmitRawPiiIsOn() {
    when(flags.getBooleanValue(eq("emitRawPii"), anyBoolean())).thenReturn(true);

    service.charge(request(VISA, 737, 2030, 1));

    SpanData span = onlySpan();
    assertThat(string(span, "demo.payment.card_number")).isEqualTo(VISA);
    assertThat(span.getAttributes().get(AttributeKey.longKey("demo.payment.card_cvv")))
        .isEqualTo(737L);
  }

  @Test
  void marksSyntheticRequestsAsNotChargedAndAddsTheEnduser() {
    Baggage baggage =
        Baggage.builder().put("synthetic_request", "true").put("enduser.id", "user-42").build();
    try (Scope ignored = baggage.storeInContext(Context.current()).makeCurrent()) {
      service.charge(request(VISA, 1, 2030, 1));
    }

    SpanData span = onlySpan();
    assertThat(string(span, "user_agent.synthetic.type")).isEqualTo("test");
    assertThat(bool(span, "demo.payment.charged")).isFalse();
    assertThat(string(span, "enduser.id")).isEqualTo("user-42");
  }

  @Test
  void aRealRequestIsChargedAndHasNoEnduserWithoutBaggage() {
    service.charge(request(VISA, 1, 2030, 1));

    SpanData span = onlySpan();
    assertThat(bool(span, "demo.payment.charged")).isTrue();
    assertThat(string(span, "enduser.id")).isNull();
  }

  @Test
  void reportsMalformedRequestsAsTypeErrors() {
    ChargeRequest noCard =
        ChargeRequest.newBuilder().setAmount(Money.newBuilder().setCurrencyCode("USD")).build();
    assertThatThrownBy(() -> service.charge(noCard))
        .isInstanceOf(PaymentException.class)
        .hasMessageContaining("creditCardNumber");
    assertThat(string(onlySpan(), "error.type")).isEqualTo("TypeError");

    spans.reset();
    assertThatThrownBy(() -> service.charge(request("", 1, 2030, 1)))
        .isInstanceOf(PaymentException.class);
    assertThat(string(onlySpan(), "error.type")).isEqualTo("TypeError");
  }

  @Test
  void aVeryShortNumberIsJustInvalid() {
    assertThatThrownBy(() -> service.charge(request("12", 1, 2030, 1)))
        .hasMessage("Credit card info is invalid.");
  }

  private SpanData onlySpan() {
    assertThat(spans.getFinishedSpanItems()).hasSize(1);
    return spans.getFinishedSpanItems().get(0);
  }

  private static String string(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  private static Boolean bool(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.booleanKey(key));
  }

  private static ChargeRequest request(String number, int cvv, int year, int month) {
    return ChargeRequest.newBuilder()
        .setAmount(Money.newBuilder().setCurrencyCode("USD").setUnits(10).setNanos(500_000_000))
        .setCreditCard(
            CreditCardInfo.newBuilder()
                .setCreditCardNumber(number)
                .setCreditCardCvv(cvv)
                .setCreditCardExpirationYear(year)
                .setCreditCardExpirationMonth(month))
        .build();
  }
}
