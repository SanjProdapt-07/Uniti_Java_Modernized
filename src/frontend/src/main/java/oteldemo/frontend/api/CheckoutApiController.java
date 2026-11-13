/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.api;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import oteldemo.CheckoutServiceGrpc.CheckoutServiceBlockingStub;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;
import oteldemo.Demo.PlaceOrderRequest;
import oteldemo.Demo.PlaceOrderResponse;
import oteldemo.frontend.gateway.ParallelExecutor;
import oteldemo.frontend.gateway.ProductCatalogService;
import oteldemo.frontend.gateway.ProtoJson;
import tools.jackson.databind.ObjectMapper;

/** {@code /api/checkout}: port of {@code pages/api/checkout.ts}. */
@RestController
class CheckoutApiController {

  private static final Logger logger = LoggerFactory.getLogger(CheckoutApiController.class);

  // Prefix checkout's Go service always uses for chargeCard failures (src/checkout/main.go), the
  // only signal available to tell a payment decline apart from an unrelated internal error.
  static final String PAYMENT_FAILURE_PREFIX = "failed to charge card:";

  static final String PAYMENT_FAILED_MESSAGE =
      "Your payment could not be processed. Please check your card details and try again.";

  private final CheckoutServiceBlockingStub checkout;
  private final ProductCatalogService productCatalog;
  private final ParallelExecutor parallel;
  private final ObjectMapper mapper;

  CheckoutApiController(
      CheckoutServiceBlockingStub checkout,
      ProductCatalogService productCatalog,
      ParallelExecutor parallel,
      ObjectMapper mapper) {
    this.checkout = checkout;
    this.productCatalog = productCatalog;
    this.parallel = parallel;
    this.mapper = mapper;
  }

  @RequestMapping("/api/checkout")
  ResponseEntity<?> checkout(HttpServletRequest request) {
    if (!"POST".equals(request.getMethod())) {
      return ApiSupport.methodNotAllowed();
    }
    String currencyCode = ApiSupport.param(request, "currencyCode", "");
    PlaceOrderRequest order =
        ProtoJson.merge(
            ApiSupport.body(request, mapper).toString(), PlaceOrderRequest.newBuilder()).build();

    PlaceOrderResponse placeOrderResponse;
    try {
      placeOrderResponse = checkout.placeOrder(order);
    } catch (RuntimeException error) {
      Span span = Span.current();
      span.recordException(error);
      span.setStatus(StatusCode.ERROR);

      String details = details(error);
      if (details.startsWith(PAYMENT_FAILURE_PREFIX)
          && !details.contains("code = Unavailable")
          && !details.contains("code = Internal")) {
        logger.atInfo().addKeyValue("details", details).log("Checkout payment declined");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", PAYMENT_FAILED_MESSAGE);
        body.put("code", "PAYMENT_FAILED");
        return ResponseEntity.status(422).contentType(MediaType.APPLICATION_JSON).body(body);
      }

      logger.atError().setCause(error).log("Checkout failed to place order");
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("error", "Failed to place order."));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    List<OrderItem> items = List.of();
    if (placeOrderResponse.hasOrder()) {
      OrderResult result = placeOrderResponse.getOrder();
      items = result.getItemsList();
      response.putAll(ProtoJson.toMap(result));
    }
    response.remove("items");
    response.put(
        "items",
        parallel.map(
            items,
            orderItem -> {
              String productId = orderItem.hasItem() ? orderItem.getItem().getProductId() : "";
              int quantity = orderItem.hasItem() ? orderItem.getItem().getQuantity() : 0;
              Map<String, Object> line = new LinkedHashMap<>();
              if (orderItem.hasCost()) {
                line.put("cost", ProtoJson.toMap(orderItem.getCost()));
              }
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("productId", productId);
              item.put("quantity", quantity);
              item.put("product", productCatalog.getProduct(productId, currencyCode));
              line.put("item", item);
              return line;
            }));

    logger
        .atInfo()
        .addKeyValue(
            "demo.order.id",
            placeOrderResponse.hasOrder() ? placeOrderResponse.getOrder().getOrderId() : "")
        .log("Order placed successfully");
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
  }

  /** grpc-js {@code ServiceError.details} is the status description; fall back to the message. */
  private static String details(RuntimeException error) {
    if (error instanceof StatusRuntimeException grpcError) {
      Status status = grpcError.getStatus();
      if (status.getDescription() != null) {
        return status.getDescription();
      }
    }
    return error.getMessage() == null ? "" : error.getMessage();
  }
}
