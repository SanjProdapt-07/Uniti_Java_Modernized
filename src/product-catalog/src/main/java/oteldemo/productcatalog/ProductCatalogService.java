/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.Value;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import oteldemo.Demo.Empty;
import oteldemo.Demo.GetProductRequest;
import oteldemo.Demo.ListProductsResponse;
import oteldemo.Demo.Product;
import oteldemo.Demo.SearchProductsRequest;
import oteldemo.Demo.SearchProductsResponse;
import oteldemo.ProductCatalogServiceGrpc;
import oteldemo.productcatalog.ProductRepository.ProductNotFoundException;
import oteldemo.productcatalog.ProductRepository.ProductQueryException;

@Service
public class ProductCatalogService extends ProductCatalogServiceGrpc.ProductCatalogServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(ProductCatalogService.class);

  static final String PRODUCT_CATALOG_FAILURE_FLAG = "productCatalogFailure";

  private static final AttributeKey<Long> PRODUCT_COUNT = AttributeKey.longKey("demo.product.count");
  private static final AttributeKey<Long> SEARCH_COUNT =
      AttributeKey.longKey("demo.product.search.count");
  private static final AttributeKey<String> PRODUCT_ID = AttributeKey.stringKey("demo.product.id");
  private static final AttributeKey<String> PRODUCT_NAME =
      AttributeKey.stringKey("demo.product.name");

  private final ProductRepository products;
  private final Client featureFlags;

  ProductCatalogService(ProductRepository products, Client featureFlags) {
    this.products = products;
    this.featureFlags = featureFlags;
  }

  @Override
  public void listProducts(Empty request, StreamObserver<ListProductsResponse> responseObserver) {
    Span span = Span.current();

    List<Product> all;
    try {
      all = products.loadProducts();
    } catch (ProductQueryException e) {
      String msg = "failed to load products: " + e.getMessage();
      span.setStatus(StatusCode.ERROR, e.getMessage());
      responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
      return;
    }

    span.setAttribute(PRODUCT_COUNT, (long) all.size());
    responseObserver.onNext(ListProductsResponse.newBuilder().addAllProducts(all).build());
    responseObserver.onCompleted();
  }

  @Override
  public void getProduct(GetProductRequest request, StreamObserver<Product> responseObserver) {
    Span span = Span.current();
    String id = request.getId();
    span.setAttribute(PRODUCT_ID, id);

    // GetProduct will fail on a specific product when the feature flag is enabled
    if (checkProductFailure(id)) {
      String msg = "Error: Product Catalog Fail Feature Flag Enabled";
      span.setStatus(StatusCode.ERROR, msg);
      span.addEvent(msg);
      responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
      return;
    }

    Product found;
    try {
      found = products.getProduct(id);
    } catch (ProductNotFoundException e) {
      String msg = "Product Not Found: " + id;
      span.setStatus(StatusCode.ERROR, msg);
      span.addEvent(msg);
      responseObserver.onError(Status.NOT_FOUND.withDescription(msg).asRuntimeException());
      return;
    }

    span.addEvent("Product Found");
    span.setAttribute(PRODUCT_ID, id);
    span.setAttribute(PRODUCT_NAME, found.getName());

    logger
        .atInfo()
        .addKeyValue("demo.product.name", found.getName())
        .addKeyValue("demo.product.id", id)
        .log("Product Found");

    responseObserver.onNext(found);
    responseObserver.onCompleted();
  }

  @Override
  public void searchProducts(
      SearchProductsRequest request, StreamObserver<SearchProductsResponse> responseObserver) {
    Span span = Span.current();

    List<Product> result;
    try {
      result = products.searchProducts(request.getQuery());
    } catch (ProductQueryException e) {
      String msg = "failed to search products: " + e.getMessage();
      span.setStatus(StatusCode.ERROR, e.getMessage());
      responseObserver.onError(Status.INTERNAL.withDescription(msg).asRuntimeException());
      return;
    }

    span.setAttribute(SEARCH_COUNT, (long) result.size());
    responseObserver.onNext(SearchProductsResponse.newBuilder().addAllResults(result).build());
    responseObserver.onCompleted();
  }

  private boolean checkProductFailure(String id) {
    return featureFlags.getBooleanValue(
        PRODUCT_CATALOG_FAILURE_FLAG,
        false,
        new ImmutableContext(Map.of("product_id", new Value(id))));
  }
}
