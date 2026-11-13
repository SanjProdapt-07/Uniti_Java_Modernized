/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.opentelemetry.api.OpenTelemetry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** initDatabase() in main.go: fail fast without a connection string or a reachable database. */
class DatabaseConfigurationTests {

  private final DatabaseConfiguration configuration = new DatabaseConfiguration();

  @Test
  void aMissingConnectionStringFailsWithTheOriginalMessage() {
    assertThatThrownBy(() -> configuration.dataSource(OpenTelemetry.noop(), "", 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DB_CONNECTION_STRING environment variable not set");
  }

  @Test
  void anUnreachableDatabaseFailsTheStartup() {
    assertThatThrownBy(
            () ->
                configuration.dataSource(
                    OpenTelemetry.noop(), "postgres://astronomy_user:secret@127.0.0.1:1/astronomy_db?sslmode=disable", 5))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void aReachableDatabaseYieldsAUsablePool() throws Exception {
    try (HikariDataSource dataSource =
        configuration.dataSource(
            OpenTelemetry.noop(), "jdbc:h2:mem:pooltest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", 3)) {
      assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
      assertThat(dataSource.getPoolName()).isEqualTo("product-catalog");
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery("SELECT 1")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
  }
}
