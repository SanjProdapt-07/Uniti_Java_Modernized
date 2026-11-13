/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.quote;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Shipping cost calculation, with the same manual instrumentation as the PHP service. */
@Service
class QuoteService {

  static final double COST_PER_ITEM = 8.99;

  static final String NUMBER_OF_ITEMS = "numberOfItems";

  private static final AttributeKey<Long> ITEMS_COUNT =
      AttributeKey.longKey("demo.shipping.quote.items_count");
  private static final AttributeKey<Double> COST_TOTAL =
      AttributeKey.doubleKey("demo.shipping.quote.cost.total");
  private static final AttributeKey<Long> NUMBER_OF_ITEMS_ATTR = AttributeKey.longKey("number_of_items");

  private final Tracer tracer;
  private final LongCounter quotes;

  QuoteService(OpenTelemetry openTelemetry) {
    this.tracer = openTelemetry.getTracer("manual-instrumentation");
    this.quotes =
        openTelemetry
            .getMeter("quotes")
            .counterBuilder("quotes")
            .setUnit("quotes")
            .setDescription("number of quotes calculated")
            .build();
  }

  /**
   * @param parsedBody the decoded request body: a {@link Map} or {@link List} (a PHP array) or
   *     {@code null} when the body could not be decoded into one
   * @return the quote, 0.0 when it could not be calculated
   */
  double calculateQuote(Object parsedBody) {
    double quote = 0.0;
    Span childSpan = tracer.spanBuilder("calculate-quote").setSpanKind(SpanKind.INTERNAL).startSpan();
    try {
      childSpan.addEvent("Calculating quote");

      // PHP's array_key_exists() throws a TypeError for a non-array. The original code only
      // catches \Exception and returns from its finally block, so the Error is silently
      // swallowed: nothing is recorded and the quote is 0.0.
      if (!(parsedBody instanceof Map<?, ?> || parsedBody instanceof List<?>)) {
        return quote;
      }
      try {
        if (!(parsedBody instanceof Map<?, ?> map) || !map.containsKey(NUMBER_OF_ITEMS)) {
          throw new IllegalArgumentException("numberOfItems not provided");
        }
        long numberOfItems = PhpCompat.intval(map.get(NUMBER_OF_ITEMS));
        quote = PhpCompat.round(COST_PER_ITEM * numberOfItems, 2);

        childSpan.setAttribute(ITEMS_COUNT, numberOfItems);
        childSpan.setAttribute(COST_TOTAL, quote);

        childSpan.addEvent("Quote calculated, returning its value");

        // manual metrics
        quotes.add(1, Attributes.of(NUMBER_OF_ITEMS_ATTR, numberOfItems));
      } catch (Exception exception) {
        childSpan.recordException(exception);
      }
      return quote;
    } finally {
      childSpan.end();
    }
  }
}
