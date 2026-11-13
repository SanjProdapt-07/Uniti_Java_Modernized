/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectionStringTests {

  @Test
  void theUrlOfTheComposeFile() {
    ConnectionString parsed =
        ConnectionString.parse(
            "postgres://astronomy_user:astronomy_password@astronomy-db/astronomy_db?sslmode=disable");

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://astronomy-db/astronomy_db");
    assertThat(parsed.username()).isEqualTo("astronomy_user");
    assertThat(parsed.password()).isEqualTo("astronomy_password");
    assertThat(parsed.properties()).isEqualTo(Map.of("sslmode", "disable"));
  }

  @Test
  void portPostgresqlSchemeAndPercentEncodedCredentials() {
    ConnectionString parsed =
        ConnectionString.parse("postgresql://us%40er:p%2Fss+w%3Ard@db.example.com:6543/my%20db");

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:6543/my db");
    assertThat(parsed.username()).isEqualTo("us@er");
    assertThat(parsed.password()).isEqualTo("p/ss+w:rd");
    assertThat(parsed.properties()).isEmpty();
  }

  @Test
  void queryParametersAreDecodedAndLibpqNamesAreTranslated() {
    ConnectionString parsed =
        ConnectionString.parse(
            "postgres://u:p@h/d?sslmode=require&connect_timeout=5&application_name=a+b&sslrootcert=%2Fca.pem");

    assertThat(parsed.properties())
        .containsEntry("sslmode", "require")
        .containsEntry("connectTimeout", "5")
        .containsEntry("ApplicationName", "a b")
        .containsEntry("sslrootcert", "/ca.pem");
  }

  @Test
  void ipv6HostsAndMissingPartsDefault() {
    assertThat(ConnectionString.parse("postgres://u@[::1]:5433/d").jdbcUrl())
        .isEqualTo("jdbc:postgresql://[::1]:5433/d");
    ConnectionString minimal = ConnectionString.parse("postgres://localhost");
    assertThat(minimal.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost/");
    assertThat(minimal.username()).isNull();
    assertThat(minimal.password()).isNull();
    assertThat(ConnectionString.parse("postgres:///d").jdbcUrl()).isEqualTo("jdbc:postgresql://localhost/d");
  }

  @Test
  void libpqKeyValueStrings() {
    ConnectionString parsed =
        ConnectionString.parse(
            "host=astronomy-db port=5432 user=astronomy_user password='pa ss\\'word' dbname=astronomy_db sslmode=disable");

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://astronomy-db:5432/astronomy_db");
    assertThat(parsed.username()).isEqualTo("astronomy_user");
    assertThat(parsed.password()).isEqualTo("pa ss'word");
    assertThat(parsed.properties()).isEqualTo(Map.of("sslmode", "disable"));
  }

  @Test
  void jdbcUrlsPassThrough() {
    ConnectionString parsed = ConnectionString.parse("jdbc:h2:mem:x;MODE=PostgreSQL");

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:h2:mem:x;MODE=PostgreSQL");
    assertThat(parsed.username()).isNull();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void anUnsetConnectionStringIsRejectedWithTheOriginalMessage(String value) {
    assertThatThrownBy(() -> ConnectionString.parse(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DB_CONNECTION_STRING environment variable not set");
  }

  @ParameterizedTest
  @ValueSource(strings = {"postgres://h:notaport/d", "host", "host='unterminated"})
  void malformedConnectionStringsAreRejected(String value) {
    assertThatThrownBy(() -> ConnectionString.parse(value)).isInstanceOf(IllegalArgumentException.class);
  }
}
