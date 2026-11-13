/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import oteldemo.shipping.ShippingTypes.Quote;

/** Asks the quote service for the shipping cost of an item count. */
@Component
class QuoteClient {

  private static final Logger logger = LoggerFactory.getLogger(QuoteClient.class);

  private static final AttributeKey<String> COST_TOTAL =
      AttributeKey.stringKey("demo.shipping.cost.total");
  private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
  // Rust's f64::from_str grammar: optional sign, then inf | infinity | nan | decimal number.
  private static final Pattern RUST_F64 =
      Pattern.compile(
          "[+-]?(?:inf|infinity|nan|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)",
          Pattern.CASE_INSENSITIVE);

  private final RestClient restClient;
  private final String quoteUrl;
  private final LongCounter itemsShipped;

  QuoteClient(
      RestClient.Builder restClientBuilder,
      OpenTelemetry openTelemetry,
      @Value("${quote.addr:http://quote:8090}") String quoteAddr) {
    this.restClient = restClientBuilder.build();
    this.quoteUrl = quoteAddr + "/getquote";
    this.itemsShipped =
        openTelemetry
            .getMeter("otel_demo.shipping.quote")
            .counterBuilder("demo.shipping.items_shipped")
            .build();
  }

  /**
   * Fetches the quote for {@code count} items (a u32 in the original).
   *
   * @throws QuoteException carrying the error text the Rust service put into its 500 response
   */
  Quote createQuoteFromCount(long count) {
    double value;
    try {
      value = requestQuote(count);
    } catch (QuoteFailure e) {
      throw new QuoteException(e.getMessage());
    }

    itemsShipped.add(count, Attributes.empty());

    Quote quote = createQuoteFromFloat(value);
    Span span = Span.current();
    if (span.isRecording()) {
      String cost = quote.toString();
      span.addEvent("shipping.quote.received", Attributes.of(COST_TOTAL, cost));
      span.setAttribute(COST_TOTAL, cost);
    }
    return quote;
  }

  private double requestQuote(long count) {
    logger
        .atInfo()
        .addKeyValue("event.name", "shipping.quote.requested")
        .addKeyValue("quote_service_addr", quoteUrl)
        .log("Requesting quote");

    byte[] bytes;
    try {
      bytes =
          restClient
              .post()
              .uri(quoteUrl)
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("numberOfItems", count))
              // Like awc, any HTTP status is accepted: only the body decides.
              .exchange(
                  (request, response) -> {
                    try {
                      return response.getBody().readAllBytes();
                    } catch (IOException e) {
                      throw new QuoteFailure("Failed to read response body from quote service");
                    }
                  });
    } catch (RestClientException e) {
      throw new QuoteFailure("Failed to call quote service: " + describe(e));
    }

    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new QuoteFailure("Failed to parse quote service response as UTF-8");
    }

    return parseF64(text);
  }

  private static String describe(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage() != null ? root.getMessage() : root.toString();
  }

  /** Parses like Rust's {@code str::parse::<f64>()}: no whitespace, no type suffixes. */
  static double parseF64(String text) {
    if (!RUST_F64.matcher(text).matches()) {
      throw new QuoteFailure("Failed to parse quote value as f64");
    }
    String lower = text.toLowerCase(Locale.ROOT);
    boolean negative = lower.startsWith("-");
    String unsigned = negative || lower.startsWith("+") ? lower.substring(1) : lower;
    if (unsigned.equals("nan")) {
      return Double.NaN;
    }
    if (unsigned.equals("inf") || unsigned.equals("infinity")) {
      return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }
    return Double.parseDouble(text);
  }

  /** Same arithmetic as Rust's {@code (value * 100.0).round() as u64} (saturating cast). */
  static Quote createQuoteFromFloat(double value) {
    double scaled = value * 100d;
    BigInteger totalCents;
    if (Double.isNaN(scaled) || scaled <= 0d) {
      totalCents = BigInteger.ZERO;
    } else if (Double.isInfinite(scaled)) {
      totalCents = U64_MAX;
    } else {
      totalCents = new BigDecimal(scaled).setScale(0, RoundingMode.HALF_UP).toBigInteger();
      if (totalCents.compareTo(U64_MAX) > 0) {
        totalCents = U64_MAX;
      }
    }
    BigInteger[] divided = totalCents.divideAndRemainder(BigInteger.valueOf(100));
    return new Quote(divided[0], divided[1].intValue());
  }

  /** The quote could not be obtained; the message is the error text of the Rust service. */
  static final class QuoteException extends RuntimeException {
    QuoteException(String message) {
      super(message);
    }
  }

  /** Internal failure carrying the (anyhow) message of the failing step. */
  private static final class QuoteFailure extends RuntimeException {
    QuoteFailure(String message) {
      super(message, null, false, false);
    }
  }
}
