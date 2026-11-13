/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import oteldemo.shipping.QuoteClient.QuoteException;
import oteldemo.shipping.ShippingTypes.CartItem;
import oteldemo.shipping.ShippingTypes.GetQuoteRequest;
import oteldemo.shipping.ShippingTypes.GetQuoteResponse;
import oteldemo.shipping.ShippingTypes.Money;
import oteldemo.shipping.ShippingTypes.Quote;
import oteldemo.shipping.ShippingTypes.ShipOrderRequest;
import oteldemo.shipping.ShippingTypes.ShipOrderResponse;

@RestController
class ShippingController {

  private static final Logger logger = LoggerFactory.getLogger(ShippingController.class);

  private static final String SLOWDOWN_FLAG = "intlShippingSlowdown";
  private static final long NANOS_MULTIPLE = 10_000_000L;
  private static final Set<String> US_NAMES =
      Set.of("US", "USA", "UNITED STATES", "UNITED STATES OF AMERICA");

  private final QuoteClient quoteClient;
  private final Client featureFlags;

  ShippingController(QuoteClient quoteClient, Client featureFlags) {
    this.quoteClient = quoteClient;
    this.featureFlags = featureFlags;
  }

  @GetMapping("/health")
  ResponseEntity<Void> health() {
    return ResponseEntity.ok().build();
  }

  @PostMapping(path = "/get-quote", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<?> getQuote(@RequestBody GetQuoteRequest request) {
    // The Rust code summed the quantities as u32 (wrapping in release builds).
    long itemCount = totalQuantity(request.items());

    Quote quote;
    try {
      quote = quoteClient.createQuoteFromCount(itemCount);
    } catch (QuoteException e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .contentType(MediaType.TEXT_PLAIN)
          .body("Failed to get quote: " + tonicStatusDisplay(e.getMessage()));
    }

    GetQuoteResponse reply =
        new GetQuoteResponse(
            new Money(
                "USD",
                quote.dollars(),
                // quote.cents * 10_000_000 as u32 (max 990_000_000)
                (int) (quote.cents() * NANOS_MULTIPLE)));

    logger
        .atInfo()
        .addKeyValue("event.name", "shipping.quote.sent")
        .addKeyValue("dollars", quote.dollars())
        .addKeyValue("cents", quote.cents())
        .log("Sending Quote");

    return ResponseEntity.ok(reply);
  }

  @PostMapping(path = "/ship-order", produces = MediaType.APPLICATION_JSON_VALUE)
  ShipOrderResponse shipOrder(@RequestBody ShipOrderRequest request) throws InterruptedException {
    boolean isOutsideUs =
        request.address() != null && !US_NAMES.contains(normalizeCountry(request.address().country()));

    long slowdownSecs = isOutsideUs ? slowdownSeconds() : 0;

    if (slowdownSecs > 0) {
      logger
          .atInfo()
          .addKeyValue("event.name", "shipping.international.slowdown")
          .addKeyValue("delay_secs", slowdownSecs)
          .log("Delaying international shipment due to intlShippingSlowdown feature flag");
      TimeUnit.SECONDS.sleep(slowdownSecs);
    }

    String trackingId = UUID.randomUUID().toString();
    logger
        .atInfo()
        .addKeyValue("event.name", "shipping.tracking_id.created")
        .addKeyValue("tracking_id", trackingId)
        .log("Tracking ID Created");
    return new ShipOrderResponse(trackingId);
  }

  private long slowdownSeconds() {
    FlagEvaluationDetails<Integer> details = featureFlags.getIntegerDetails(SLOWDOWN_FLAG, 0);
    if (details.getErrorCode() != null) {
      logger
          .atWarn()
          .addKeyValue("event.name", "shipping.feature_flag.evaluation_failed")
          .addKeyValue("error", details.getErrorCode() + ": " + details.getErrorMessage())
          .log("shipping.feature_flag.evaluation_failed");
      return 0;
    }
    logger
        .atInfo()
        .addKeyValue("event.name", "shipping.feature_flag.evaluated")
        .addKeyValue("feature_flag_key", SLOWDOWN_FLAG)
        .addKeyValue("feature_flag_provider_name", "flagd")
        .addKeyValue("feature_flag_variant", details.getVariant() != null ? details.getVariant() : "unknown")
        .log("shipping.feature_flag.evaluated");
    return details.getValue();
  }

  // Rust: addr.country.to_uppercase().trim()
  private static String normalizeCountry(String country) {
    return country.toUpperCase(Locale.ROOT).strip();
  }

  private static long totalQuantity(List<CartItem> items) {
    long total = 0;
    for (CartItem item : items) {
      total = (total + item.quantity()) & 0xFFFF_FFFFL;
    }
    return total;
  }

  /** Text of tonic's {@code Status::unknown(message)} as printed with {@code {}}. */
  private static String tonicStatusDisplay(String message) {
    return "status: Unknown, message: \""
        + message.replace("\\", "\\\\").replace("\"", "\\\"")
        + "\", details: [], metadata: MetadataMap { headers: {} }";
  }
}
