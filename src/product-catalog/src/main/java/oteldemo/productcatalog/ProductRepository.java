/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import oteldemo.Demo.Money;
import oteldemo.Demo.Product;

/**
 * Reads the products from {@code catalog.products}. The SQL statements, the column handling and
 * the error texts are the ones of the Go service ({@code loadProductsFromDB}, {@code
 * searchProductsFromDB}, {@code getProductFromDB}).
 */
@Repository
class ProductRepository {

  private static final Logger logger = LoggerFactory.getLogger(ProductRepository.class);

  private static final String SELECT =
      """
      SELECT p.id, p.name, p.description, p.picture,
             p.price_currency_code, p.price_units, p.price_nanos, p.categories
      FROM catalog.products p
      """;

  private final JdbcTemplate jdbc;

  ProductRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** All products ordered by id. */
  List<Product> loadProducts() {
    return query(SELECT + "ORDER BY p.id\n");
  }

  /** Products whose name or description contains the (lower-cased) query. */
  List<Product> searchProducts(String query) {
    String searchPattern = "%" + query.toLowerCase(Locale.ROOT) + "%";
    return query(
        SELECT + "WHERE LOWER(p.name) LIKE ? OR LOWER(p.description) LIKE ?\nORDER BY p.id\n",
        searchPattern,
        searchPattern);
  }

  /**
   * One product by id.
   *
   * @throws ProductNotFoundException when there is no such product or it cannot be read
   */
  Product getProduct(String productId) {
    try {
      return jdbc.queryForObject(SELECT + "WHERE p.id = ?\n", productRowMapper(), productId);
    } catch (EmptyResultDataAccessException e) {
      throw new ProductNotFoundException("product not found", e);
    } catch (ScanException e) {
      throw new ProductNotFoundException("failed to scan product row: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new ProductNotFoundException(describe("failed to scan product row", e), e);
    }
  }

  private List<Product> query(String sql, Object... args) {
    List<Product> products;
    try {
      products = new ArrayList<>(jdbc.query(sql, productRowMapper(), args));
    } catch (ScanException e) {
      throw new ProductQueryException(
          "failed to get products from rows: failed to scan product row: " + e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new ProductQueryException(describe("failed to query products", e), e);
    }

    logger
        .atInfo()
        .addKeyValue("products", products.size())
        .log("Found {} products from database", products.size());
    return products;
  }

  private static String describe(String prefix, Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return prefix + ": " + (root.getMessage() != null ? root.getMessage() : root.toString());
  }

  private static RowMapper<Product> productRowMapper() {
    return (rs, rowNum) -> {
      String id = text(rs, 1);
      String name = text(rs, 2);
      String description = text(rs, 3);
      String picture = text(rs, 4);
      String currencyCode = text(rs, 5);
      long units = rs.getLong(6);
      int nanos = rs.getInt(7);
      String categories = text(rs, 8);
      return parseProductRow(id, name, description, picture, currencyCode, categories, units, nanos);
    };
  }

  // database/sql refuses to scan a NULL into a Go string; so does this.
  private static String text(ResultSet rs, int column) throws SQLException {
    String value = rs.getString(column);
    if (value == null) {
      ResultSetMetaData meta = rs.getMetaData();
      throw new ScanException(
          "sql: Scan error on column index %d, name \"%s\": converting NULL to string is unsupported"
              .formatted(column - 1, meta.getColumnLabel(column)));
    }
    return value;
  }

  static Product parseProductRow(
      String id,
      String name,
      String description,
      String picture,
      String currencyCode,
      String categoriesStr,
      long units,
      int nanos) {
    Product.Builder product =
        Product.newBuilder()
            .setId(id)
            .setName(name)
            .setDescription(description)
            .setPicture(picture)
            .setPriceUsd(
                Money.newBuilder().setCurrencyCode(currencyCode).setUnits(units).setNanos(nanos));
    // comma-delimited categories, each trimmed
    if (!categoriesStr.isEmpty()) {
      for (String category : categoriesStr.split(",", -1)) {
        product.addCategories(category.strip());
      }
    }
    return product.build();
  }

  /** A row could not be read into a product. */
  private static final class ScanException extends RuntimeException {
    ScanException(String message) {
      super(message);
    }
  }

  /** Listing or searching failed; the message is the text of the Go error. */
  static final class ProductQueryException extends RuntimeException {
    ProductQueryException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A single product could not be loaded. */
  static final class ProductNotFoundException extends RuntimeException {
    ProductNotFoundException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
