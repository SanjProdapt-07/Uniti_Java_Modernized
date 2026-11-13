/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.quote;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QuoteController {

  // Name of the Monolog logger of the PHP service ("slim-app").
  private static final Logger LOGGER = LoggerFactory.getLogger("slim-app");

  private static final AttributeKey<Double> COST_TOTAL =
      AttributeKey.doubleKey("demo.shipping.quote.cost.total");

  private final QuoteService quoteService;

  QuoteController(QuoteService quoteService) {
    this.quoteService = quoteService;
  }

  @PostMapping("/getquote")
  ResponseEntity<String> getQuote(HttpServletRequest request) throws IOException {
    Span span = Span.current();
    span.addEvent("Received get quote request, processing it");

    // The raw body is read here (not with @RequestBody) so that, as with Slim, any content type
    // is accepted and the body is decoded by media type.
    Object parsedBody =
        RequestBodyParser.parse(
            request.getHeader(HttpHeaders.CONTENT_TYPE), request.getInputStream().readAllBytes());

    double data = quoteService.calculateQuote(parsedBody);

    String payload = PhpCompat.jsonEncode(data);

    span.addEvent("Quote processed, response sent back", Attributes.of(COST_TOTAL, data));
    // exported as an OpenTelemetry log record
    LOGGER.atInfo().setMessage("Calculated quote").addKeyValue("total", data).log();

    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(payload);
  }
}
