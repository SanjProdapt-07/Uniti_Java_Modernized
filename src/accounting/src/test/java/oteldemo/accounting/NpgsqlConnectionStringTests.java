/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NpgsqlConnectionStringTests {

  @Test
  void translatesTheComposeConnectionString() {
    NpgsqlConnectionString parsed =
        NpgsqlConnectionString.parse(
            "Host=astronomy-db;Username=astronomy_user;Password=astronomy_password;Database=astronomy_db");

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://astronomy-db:5432/astronomy_db");
    assertThat(parsed.username()).isEqualTo("astronomy_user");
    assertThat(parsed.password()).isEqualTo("astronomy_password");
  }

  @Test
  void keywordsAreCaseInsensitiveAndAcceptAliases() {
    NpgsqlConnectionString parsed =
        NpgsqlConnectionString.parse("server=db.example;PORT=6543;User Id=u;pwd=p;db=d;SSL Mode=VerifyFull");

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example:6543/d?sslmode=verify-full");
    assertThat(parsed.username()).isEqualTo("u");
    assertThat(parsed.password()).isEqualTo("p");
  }

  @Test
  void quotedValuesMayContainSemicolons() {
    NpgsqlConnectionString parsed = NpgsqlConnectionString.parse("Host=h;Username=u;Password='a;b=c';Database=d");

    assertThat(parsed.password()).isEqualTo("a;b=c");
  }

  @Test
  void databaseDefaultsToTheUserName() {
    assertThat(NpgsqlConnectionString.parse("Host=h;Username=bob;Password=x").jdbcUrl())
        .isEqualTo("jdbc:postgresql://h:5432/bob");
  }

  @Test
  void ipv6HostsAreBracketed() {
    assertThat(NpgsqlConnectionString.parse("Host=::1;Database=d").jdbcUrl())
        .isEqualTo("jdbc:postgresql://[::1]:5432/d");
  }

  @Test
  void jdbcUrlsPassThrough() {
    NpgsqlConnectionString parsed = NpgsqlConnectionString.parse("jdbc:h2:mem:x");

    assertThat(parsed.jdbcUrl()).isEqualTo("jdbc:h2:mem:x");
    assertThat(parsed.username()).isNull();
  }

  @Test
  void hostIsRequired() {
    assertThatThrownBy(() -> NpgsqlConnectionString.parse("Username=u;Database=d"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NpgsqlConnectionString.parse("nonsense"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
