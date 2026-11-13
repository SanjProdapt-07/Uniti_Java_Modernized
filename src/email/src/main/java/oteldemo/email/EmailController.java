/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.email;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /send_order_confirmation}, the one route of the original Sinatra service. The body
 * is {@code {"email": "...", "order": {OrderResult}}}; the answer is an empty 200.
 */
@RestController
public class EmailController {

  private static final AttributeKey<String> ORDER_ID = AttributeKey.stringKey("demo.order.id");
  static final MediaType HTML_UTF8 = MediaType.parseMediaType("text/html;charset=utf-8");

  // Ruby's JSON.parse rejects anything after the first value
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private final EmailService emailService;

  public EmailController(EmailService emailService) {
    this.emailService = emailService;
  }

  @PostMapping("/send_order_confirmation")
  public ResponseEntity<Void> sendOrderConfirmation(@RequestBody(required = false) byte[] body) {
    JsonNode data = parse(body);
    JsonNode order = order(data);
    String orderId = text(order.get("order_id"));

    // get the current auto-instrumented span
    if (orderId != null) {
      Span.current().setAttribute(ORDER_ID, orderId);
    }

    emailService.countConfirmation();
    emailService.sendEmail(text(data.get("email")), order, orderId);

    // Sinatra answered the nil returned by the route with an empty text/html 200
    return ResponseEntity.ok().contentType(HTML_UTF8).build();
  }

  private static JsonNode parse(byte[] body) {
    if (body == null || body.length == 0) {
      throw new IllegalArgumentException("unexpected end of input");
    }
    JsonNode data = JSON.readTree(body);
    if (data == null || !data.isObject()) {
      throw new IllegalArgumentException("undefined method 'order' for " + data);
    }
    return data;
  }

  /** {@code data.order}: reading {@code order_id} of a missing or non-object order fails. */
  private static JsonNode order(JsonNode data) {
    JsonNode order = data.get("order");
    if (order == null || !order.isObject()) {
      throw new IllegalArgumentException("undefined method 'order_id' for " + order);
    }
    return order;
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return node.isString() ? node.asString() : node.toString();
  }
}
