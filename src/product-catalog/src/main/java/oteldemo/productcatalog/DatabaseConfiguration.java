/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.hikaricp.v3_0.HikariTelemetry;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The connection pool to the product database. Like the Go service, the application refuses to
 * start when {@code DB_CONNECTION_STRING} is not set or the database cannot be reached.
 *
 * <p>The pool is a plain {@link HikariDataSource}; the OpenTelemetry Spring Boot starter wraps
 * every {@link DataSource} bean with the JDBC instrumentation (client spans, SQLCommenter).
 */
@Configuration(proxyBeanMethods = false)
class DatabaseConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(DatabaseConfiguration.class);

  @Bean(destroyMethod = "close")
  HikariDataSource dataSource(
      OpenTelemetry openTelemetry,
      @Value("${db.connection-string:}") String connectionString,
      @Value("${db.max-pool-size:20}") int maxPoolSize) {
    ConnectionString parsed;
    try {
      parsed = ConnectionString.parse(connectionString);
    } catch (IllegalArgumentException e) {
      logger.error("Error initializing database: {}", e.getMessage());
      throw e;
    }

    HikariConfig config = new HikariConfig();
    config.setPoolName("product-catalog");
    config.setJdbcUrl(parsed.jdbcUrl());
    if (parsed.username() != null) {
      config.setUsername(parsed.username());
    }
    if (parsed.password() != null) {
      config.setPassword(parsed.password());
    }
    parsed.properties().forEach(config::addDataSourceProperty);
    config.setMaximumPoolSize(maxPoolSize);
    // db.client.connection.* pool metrics, the counterpart of otelsql.RegisterDBStatsMetrics
    config.setMetricsTrackerFactory(
        HikariTelemetry.create(openTelemetry).createMetricsTrackerFactory());

    // Constructing the pool connects once and fails when the database cannot be reached
    // (the Go service pinged the database and exited on failure).
    HikariDataSource dataSource;
    try {
      dataSource = new HikariDataSource(config);
    } catch (RuntimeException e) {
      logger.error("Error initializing database: failed to ping database: {}", e.getMessage());
      throw e;
    }
    logger.info("Database connection established");
    return dataSource;
  }
}
