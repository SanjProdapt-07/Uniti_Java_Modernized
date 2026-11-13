/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Without DB_CONNECTION_STRING no database is configured and orders are only logged. */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "spring.kafka.bootstrap-servers=localhost:1",
      "spring.kafka.listener.auto-startup=false",
      "DB_CONNECTION_STRING="
    })
class WithoutDatabaseTests {

  @Autowired ApplicationContext context;

  @Test
  void noDataSourceNorStoreIsCreated() {
    assertThat(context.getBeanProvider(DataSource.class).getIfAvailable()).isNull();
    assertThat(context.getBeanProvider(OrderStore.class).getIfAvailable()).isNull();
    assertThat(context.getBean(OrderConsumer.class)).isNotNull();
  }
}
