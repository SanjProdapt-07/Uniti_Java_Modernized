/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import oteldemo.CurrencyServiceGrpc.CurrencyServiceBlockingStub;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.Empty;
import oteldemo.Demo.GetProductRequest;
import oteldemo.Demo.Money;
import oteldemo.Demo.Product;
import oteldemo.ProductCatalogServiceGrpc.ProductCatalogServiceBlockingStub;

/**
 * Port of {@code services/ProductCatalog.service.ts}: catalog lookups priced in the requested
 * currency. A product is converted only when a currency other than USD is requested.
 */
@Service
public class ProductCatalogService {

  private static final String DEFAULT_CURRENCY_CODE = "USD";

  private final ProductCatalogServiceBlockingStub productCatalog;
  private final CurrencyServiceBlockingStub currency;
  private final ParallelExecutor parallel;

  public ProductCatalogService(
      ProductCatalogServiceBlockingStub productCatalog,
      CurrencyServiceBlockingStub currency,
      ParallelExecutor parallel) {
    this.productCatalog = productCatalog;
    this.currency = currency;
    this.parallel = parallel;
  }

  public List<Map<String, Object>> listProducts(String currencyCode) {
    List<Product> products =
        productCatalog.listProducts(Empty.getDefaultInstance()).getProductsList();
    return parallel.map(products, product -> priced(product, currencyCode));
  }

  public Map<String, Object> getProduct(String id, String currencyCode) {
    Product product = productCatalog.getProduct(GetProductRequest.newBuilder().setId(id).build());
    return priced(product, currencyCode);
  }

  private Map<String, Object> priced(Product product, String currencyCode) {
    Map<String, Object> json = new LinkedHashMap<>(ProtoJson.toMap(product));
    boolean convert =
        currencyCode != null
            && !currencyCode.isEmpty()
            && !DEFAULT_CURRENCY_CODE.equals(currencyCode);
    if (convert) {
      Money price = product.hasPriceUsd() ? product.getPriceUsd() : Money.getDefaultInstance();
      json.put("priceUsd", ProtoJson.toMap(convert(price, currencyCode)));
    }
    return json;
  }

  /** Currency conversion through the currency service (also used for shipping costs). */
  public Money convert(Money from, String toCode) {
    return currency.convert(
        CurrencyConversionRequest.newBuilder().setFrom(from).setToCode(toCode).build());
  }
}
