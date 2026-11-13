/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database is optional, exactly as in the original: without {@code DB_CONNECTION_STRING} no
 * database access is configured and orders are only logged.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(DatabaseConfiguration.DbConnectionStringPresent.class)
class DatabaseConfiguration {

  @Bean(destroyMethod = "close")
  DataSource accountingDataSource(Environment environment) {
    NpgsqlConnectionString connection =
        NpgsqlConnectionString.parse(environment.getRequiredProperty("DB_CONNECTION_STRING"));

    HikariConfig config = new HikariConfig();
    config.setPoolName("accounting");
    config.setJdbcUrl(connection.jdbcUrl());
    if (connection.username() != null) {
      config.setUsername(connection.username());
    }
    if (connection.password() != null) {
      config.setPassword(connection.password());
    }
    // One consumer thread writes one order at a time.
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(0);
    // The original opened a connection per message, so an unavailable database never prevented the
    // service from starting; it only made the individual orders fail.
    config.setInitializationFailTimeout(-1);
    return new HikariDataSource(config);
  }

  @Bean
  OrderStore orderStore(DataSource dataSource) {
    return new OrderStore(new JdbcTemplate(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  /** Matches when {@code DB_CONNECTION_STRING} is set to something other than blank. */
  static class DbConnectionStringPresent implements Condition {

    @Override
    public boolean matches(
        org.springframework.context.annotation.ConditionContext context,
        org.springframework.core.type.AnnotatedTypeMetadata metadata) {
      String value = context.getEnvironment().getProperty("DB_CONNECTION_STRING");
      return value != null && !value.isBlank();
    }
  }
}
