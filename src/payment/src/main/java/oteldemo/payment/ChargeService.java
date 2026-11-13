/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.payment;

import dev.openfeature.sdk.Client;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import oteldemo.Demo.ChargeRequest;
import oteldemo.Demo.CreditCardInfo;
import oteldemo.Demo.Money;
import oteldemo.payment.CardValidator.CardDetails;

/** Port of {@code charge.js}: validates the card and "charges" it. */
@Service
public class ChargeService {

  private static final Logger logger = LoggerFactory.getLogger(ChargeService.class);

  private static final List<String> LOYALTY_LEVEL = List.of("platinum", "gold", "silver", "bronze");
  private static final List<String> ACCEPTED_CARD_TYPES = List.of("visa", "mastercard");
  private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("demo.payment.currency");

  private final Client featureFlags;
  private final Tracer tracer;
  private final LongCounter transactions;
  private final Clock clock;
  private final RandomGenerator random;

  @Autowired
  public ChargeService(Client featureFlags, OpenTelemetry openTelemetry) {
    this(featureFlags, openTelemetry, Clock.systemDefaultZone(), RandomGenerator.getDefault());
  }

  ChargeService(
      Client featureFlags, OpenTelemetry openTelemetry, Clock clock, RandomGenerator random) {
    this.featureFlags = featureFlags;
    this.clock = clock;
    this.random = random;
    this.tracer = openTelemetry.getTracer("payment");
    this.transactions =
        openTelemetry
            .getMeter("payment")
            .counterBuilder("demo.payment.transactions")
            .setUnit("{transaction}")
            .build();
  }

  /**
   * Validates and charges the card and returns the transaction id.
   *
   * @throws PaymentException if the charge fails, with the same messages the Node.js service used
   */
  public String charge(ChargeRequest request) {
    Span span = tracer.spanBuilder("charge").startSpan();
    try {
      Baggage baggage = Baggage.current();
      boolean syntheticRequest = "true".equals(baggageValue(baggage, "synthetic_request"));

      if (syntheticRequest) {
        span.setAttribute("user_agent.synthetic.type", "test");
      }

      double failureRate = featureFlags.getDoubleValue("paymentFailure", 0.0);
      boolean emitRawPii = featureFlags.getBooleanValue("emitRawPii", false);

      if (failureRate > 0) {
        // n% chance to fail with demo.user_context.loyalty_level=gold
        if (random.nextDouble() < failureRate) {
          span.setAttribute("demo.user_context.loyalty_level", "gold");

          throw new PaymentException(
              "Payment request failed. Invalid token. demo.user_context.loyalty_level=gold");
        }
      }

      if (!request.hasCreditCard()) {
        throw new PaymentException(
            "TypeError",
            "Cannot destructure property 'creditCardNumber' of 'request.creditCard' as it is undefined.");
      }
      CreditCardInfo creditCard = request.getCreditCard();
      String number = creditCard.getCreditCardNumber();
      int cvv = creditCard.getCreditCardCvv();
      int year = creditCard.getCreditCardExpirationYear();
      int month = creditCard.getCreditCardExpirationMonth();
      LocalDate today = LocalDate.now(clock);
      int currentMonth = today.getMonthValue();
      int currentYear = today.getYear();
      if (number.isEmpty()) {
        // proto3 omits empty strings, so the Node.js service saw `undefined` here
        throw new PaymentException(
            "TypeError", "Cannot read properties of undefined (reading 'substr')");
      }
      String lastFourDigits = number.length() <= 4 ? number : number.substring(number.length() - 4);
      String transactionId = UUID.randomUUID().toString();

      CardDetails card = CardValidator.getCardDetails(number);
      String cardType = card.cardType();
      boolean valid = card.valid();

      String loyaltyLevel = LOYALTY_LEVEL.get(random.nextInt(LOYALTY_LEVEL.size()));

      span.setAttribute("demo.payment.card_type", cardType);
      span.setAttribute("demo.payment.card_valid", valid);
      span.setAttribute("demo.user_context.loyalty_level", loyaltyLevel);
      if (emitRawPii) {
        span.setAttribute("demo.payment.card_number", number);
        span.setAttribute("demo.payment.card_cvv", (long) cvv);
      }

      if (!valid) {
        throw new PaymentException("Credit card info is invalid.");
      }

      if (!ACCEPTED_CARD_TYPES.contains(cardType)) {
        throw new PaymentException(
            "Sorry, we cannot process "
                + cardType
                + " credit cards. Only VISA or MasterCard is accepted.");
      }

      // An absent (zero) year or month was `undefined` in Node.js, which made the sum NaN and the
      // comparison false, so such a card was never reported as expired.
      boolean expiryGiven = year != 0 && month != 0;
      if (expiryGiven && ((long) currentYear * 12 + currentMonth) > ((long) year * 12 + month)) {
        throw new PaymentException(
            "The credit card (ending " + lastFourDigits + ") expired on " + month + "/" + year + ".");
      }

      // Do not charge synthetic requests.
      span.setAttribute("demo.payment.charged", !syntheticRequest);

      String enduserId = baggageValue(baggage, "enduser.id");
      if (enduserId != null && !enduserId.isEmpty()) {
        span.setAttribute("enduser.id", enduserId);
      }

      Money amount = request.getAmount();
      logger
          .atInfo()
          .addKeyValue("transactionId", transactionId)
          .addKeyValue("cardType", cardType)
          .addKeyValue("lastFourDigits", lastFourDigits)
          .addKeyValue("amount.units", amount.getUnits())
          .addKeyValue("amount.nanos", amount.getNanos())
          .addKeyValue("amount.currencyCode", amount.getCurrencyCode())
          .addKeyValue("loyalty_level", loyaltyLevel)
          .log("Transaction complete.");
      transactions.add(1, Attributes.of(CURRENCY, amount.getCurrencyCode()));

      return transactionId;
    } catch (RuntimeException e) {
      span.recordException(e);
      span.setStatus(StatusCode.ERROR, e.getMessage());
      span.setAttribute("error.type", errorType(e));
      throw e;
    } finally {
      span.end();
    }
  }

  static String errorType(RuntimeException e) {
    return e instanceof PaymentException payment ? payment.errorType() : e.getClass().getSimpleName();
  }

  private static String baggageValue(Baggage baggage, String key) {
    BaggageEntry entry = baggage.getEntry(key);
    return entry == null ? null : entry.getValue();
  }
}
