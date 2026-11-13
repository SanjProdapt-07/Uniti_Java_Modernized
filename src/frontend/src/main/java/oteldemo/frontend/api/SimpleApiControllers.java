/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import oteldemo.AdServiceGrpc.AdServiceBlockingStub;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.Demo.Ad;
import oteldemo.Demo.AdRequest;
import oteldemo.Demo.Empty;
import oteldemo.Demo.ListRecommendationsRequest;
import oteldemo.Demo.ListRecommendationsResponse;
import oteldemo.Demo.Money;
import oteldemo.RecommendationServiceGrpc.RecommendationServiceBlockingStub;
import oteldemo.frontend.gateway.ParallelExecutor;
import oteldemo.frontend.gateway.ProductCatalogService;
import oteldemo.frontend.gateway.ProtoJson;
import oteldemo.frontend.gateway.ShippingGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The read-only API routes: {@code /api/currency}, {@code /api/data}, {@code /api/products},
 * {@code /api/recommendations} and {@code /api/shipping}. Each one answers only GET and replies
 * {@code 405} with an empty body otherwise, exactly like the Next.js handlers. */
final class SimpleApiControllers {

  private SimpleApiControllers() {}

  private static ResponseEntity<Object> json(Object body) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }

  /** {@code pages/api/currency.ts}. */
  @RestController
  static class CurrencyApi {
    private final CurrencyServiceBlockingStub currency;

    CurrencyApi(CurrencyServiceBlockingStub currency) {
      this.currency = currency;
    }

    @RequestMapping("/api/currency")
    ResponseEntity<?> currency(HttpServletRequest request) {
      if (!"GET".equals(request.getMethod())) {
        return ApiSupport.methodNotAllowed();
      }
      return json(currency.getSupportedCurrencies(Empty.getDefaultInstance()).getCurrencyCodesList());
    }
  }

  /** {@code pages/api/data.ts}: ads for the given context keys. */
  @RestController
  static class DataApi {
    private final AdServiceBlockingStub adService;

    DataApi(AdServiceBlockingStub adService) {
      this.adService = adService;
    }

    @RequestMapping("/api/data")
    ResponseEntity<?> data(HttpServletRequest request) {
      if (!"GET".equals(request.getMethod())) {
        return ApiSupport.methodNotAllowed();
      }
      List<String> contextKeys = ApiSupport.listParam(request, "contextKeys");
      List<Ad> ads =
          adService.getAds(AdRequest.newBuilder().addAllContextKeys(contextKeys).build()).getAdsList();
      return json(ads.stream().map(ProtoJson::toMap).toList());
    }
  }

  /** {@code pages/api/products/index.ts} and {@code pages/api/products/[productId]/index.ts}. */
  @RestController
  static class ProductsApi {
    private final ProductCatalogService productCatalog;

    ProductsApi(ProductCatalogService productCatalog) {
      this.productCatalog = productCatalog;
    }

    @RequestMapping("/api/products")
    ResponseEntity<?> products(HttpServletRequest request) {
      if (!"GET".equals(request.getMethod())) {
        return ApiSupport.methodNotAllowed();
      }
      return json(productCatalog.listProducts(ApiSupport.param(request, "currencyCode", "")));
    }

    @RequestMapping("/api/products/{productId}")
    ResponseEntity<?> product(
        HttpServletRequest request, @PathVariable String productId) {
      if (!"GET".equals(request.getMethod())) {
        return ApiSupport.methodNotAllowed();
      }
      return json(productCatalog.getProduct(productId, ApiSupport.param(request, "currencyCode", "")));
    }
  }

  /** {@code pages/api/recommendations.ts}: at most four recommended products, priced. */
  @RestController
  static class RecommendationsApi {
    private static final int MAX_RECOMMENDATIONS = 4;

    private final RecommendationServiceBlockingStub recommendations;
    private final ProductCatalogService productCatalog;
    private final ParallelExecutor parallel;

    RecommendationsApi(
        RecommendationServiceBlockingStub recommendations,
        ProductCatalogService productCatalog,
        ParallelExecutor parallel) {
      this.recommendations = recommendations;
      this.productCatalog = productCatalog;
      this.parallel = parallel;
    }

    @RequestMapping("/api/recommendations")
    ResponseEntity<?> recommendations(HttpServletRequest request) {
      if (!"GET".equals(request.getMethod())) {
        return ApiSupport.methodNotAllowed();
      }
      String sessionId = ApiSupport.param(request, "sessionId", "");
      String currencyCode = ApiSupport.param(request, "currencyCode", "");
      String[] raw = request.getParameterValues("productIds");
      List<String> productIds =
          ApiSupport.splitCommaList(raw == null ? List.of() : List.of(raw));

      ListRecommendationsResponse response =
          recommendations.listRecommendations(
              ListRecommendationsRequest.newBuilder()
                  .setUserId(sessionId)
                  .addAllProductIds(productIds)
                  .build());
      List<String> recommended = response.getProductIdsList();
      List<String> firstFour =
          new ArrayList<>(recommended.subList(0, Math.min(MAX_RECOMMENDATIONS, recommended.size())));
      List<Map<String, Object>> products =
          parallel.map(firstFour, id -> productCatalog.getProduct(id, currencyCode));
      return json(products);
    }
  }

  /** {@code pages/api/shipping.ts}: quote through the shipping HTTP service, converted to the requested currency. */
  @RestController
  static class ShippingApi {
    private final ShippingGateway shipping;
    private final ProductCatalogService productCatalog;
    private final ObjectMapper mapper;

    ShippingApi(ShippingGateway shipping, ProductCatalogService productCatalog, ObjectMapper mapper) {
      this.shipping = shipping;
      this.productCatalog = productCatalog;
      this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    @RequestMapping("/api/shipping")
    ResponseEntity<?> shipping(HttpServletRequest request) {
      if (!"GET".equals(request.getMethod())) {
        return ApiSupport.methodNotAllowed();
      }
      String itemList = ApiSupport.param(request, "itemList", "");
      String currencyCode = ApiSupport.param(request, "currencyCode", "USD");
      String address = ApiSupport.param(request, "address", "");

      // JSON.parse('') throws in the original, which surfaces as a 500
      JsonNode items = parse(itemList);
      JsonNode addressNode = parse(address);
      Money costUsd =
          shipping.getShippingCost(
              mapper.convertValue(items, List.class), mapper.convertValue(addressNode, Map.class));
      Money cost =
          productCatalog.convert(costUsd == null ? Money.getDefaultInstance() : costUsd, currencyCode);
      return json(ProtoJson.toMap(cost));
    }

    private JsonNode parse(String text) {
      JsonNode node = mapper.readTree(text);
      if (node.isMissingNode()) {
        throw new IllegalArgumentException("Unexpected end of JSON input");
      }
      return node;
    }
  }
}
