/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import oteldemo.CartServiceGrpc.CartServiceBlockingStub;
import oteldemo.Demo.AddItemRequest;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.EmptyCartRequest;
import oteldemo.Demo.GetCartRequest;
import oteldemo.frontend.gateway.ParallelExecutor;
import oteldemo.frontend.gateway.ProductCatalogService;
import oteldemo.frontend.gateway.ProtoJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** {@code /api/cart}: port of {@code pages/api/cart.ts}. */
@RestController
class CartApiController {

  private final CartServiceBlockingStub cartService;
  private final ProductCatalogService productCatalog;
  private final ParallelExecutor parallel;
  private final ObjectMapper mapper;

  CartApiController(
      CartServiceBlockingStub cartService,
      ProductCatalogService productCatalog,
      ParallelExecutor parallel,
      ObjectMapper mapper) {
    this.cartService = cartService;
    this.productCatalog = productCatalog;
    this.parallel = parallel;
    this.mapper = mapper;
  }

  @RequestMapping("/api/cart")
  ResponseEntity<?> cart(HttpServletRequest request) {
    return switch (request.getMethod()) {
      case "GET" -> getCart(request);
      case "POST" -> addItem(request);
      case "DELETE" -> emptyCart(request);
      default -> ApiSupport.methodNotAllowed();
    };
  }

  private ResponseEntity<?> getCart(HttpServletRequest request) {
    String sessionId = ApiSupport.param(request, "sessionId", "");
    String currencyCode = ApiSupport.param(request, "currencyCode", "");

    Cart cart = cartService.getCart(GetCartRequest.newBuilder().setUserId(sessionId).build());
    List<Map<String, Object>> items =
        parallel.map(
            cart.getItemsList(),
            item -> {
              Map<String, Object> line = new LinkedHashMap<>();
              line.put("productId", item.getProductId());
              line.put("quantity", item.getQuantity());
              line.put("product", productCatalog.getProduct(item.getProductId(), currencyCode));
              return line;
            });

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("userId", cart.getUserId());
    body.put("items", items);
    return json(body);
  }

  private ResponseEntity<?> addItem(HttpServletRequest request) {
    JsonNode body = ApiSupport.body(request, mapper);
    String userId = body.path("userId").asString("");
    AddItemRequest.Builder add = AddItemRequest.newBuilder().setUserId(userId);
    JsonNode item = body.get("item");
    if (item != null && item.isObject()) {
      add.setItem(ProtoJson.merge(item.toString(), CartItem.newBuilder()));
    }
    cartService.addItem(add.build());
    Cart cart = cartService.getCart(GetCartRequest.newBuilder().setUserId(userId).build());
    return json(ProtoJson.toMap(cart));
  }

  private ResponseEntity<?> emptyCart(HttpServletRequest request) {
    JsonNode body = ApiSupport.body(request, mapper);
    cartService.emptyCart(
        EmptyCartRequest.newBuilder().setUserId(body.path("userId").asString("")).build());
    return ResponseEntity.noContent().build();
  }

  private static ResponseEntity<Object> json(Object body) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
