/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * With OPAMP_SERVER_ENDPOINT set the shared service.instance.id lands in the telemetry resource
 * (the Go test read it back with resource.New(WithFromEnv())), and an unreachable OpAMP server does
 * not stop the service from starting.
 */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "db.connection-string=jdbc:h2:mem:opamp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;"
          + "INIT=RUNSCRIPT FROM 'classpath:catalog-schema.sql'",
      "product-catalog.lock-contention.interval=1h",
      "opamp.server.endpoint=ws://127.0.0.1:1/v1/opamp",
      "otel.resource.attributes.service.namespace=opentelemetry-demo"
    })
class OpampWiringTests {

  @Autowired private ResourceCapture resource;
  @Autowired private Environment environment;
  @Autowired private OpampClientLifecycle opamp;

  @Test
  void theResourceCarriesTheOpampInstanceIdNextToTheConfiguredAttributes() {
    String uid = environment.getProperty("opamp.instance-uid");

    assertThat(uid).hasSize(32);
    assertThat(resource.attributes().get(AttributeKey.stringKey("service.instance.id"))).isEqualTo(uid);
    assertThat(resource.attributes().get(AttributeKey.stringKey("service.namespace")))
        .isEqualTo("opentelemetry-demo");
    assertThat(resource.attributes().get(AttributeKey.stringKey("service.name"))).isEqualTo("product-catalog");
  }

  @Test
  void theClientIsStartedEvenThoughTheServerCannotBeReached() {
    assertThat(opamp.isRunning()).isTrue();
  }
}
