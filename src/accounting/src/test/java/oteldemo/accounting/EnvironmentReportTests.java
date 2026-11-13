/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentReportTests {

  @Test
  void printsOnlyRelevantVariablesSortedByName() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    EnvironmentReport.print(
        Map.of(
            "OTEL_SERVICE_NAME", "accounting",
            "KAFKA_TOPIC", "orders",
            "KAFKA_ADDR", "kafka:9092",
            "PATH", "/usr/bin",
            "DB_CONNECTION_STRING", "Host=h;Password=secret"),
        new PrintStream(bytes, true, StandardCharsets.UTF_8));

    assertThat(bytes.toString(StandardCharsets.UTF_8).lines())
        .containsExactly(
            "[KAFKA_ADDR, kafka:9092]", "[KAFKA_TOPIC, orders]", "[OTEL_SERVICE_NAME, accounting]");
  }

  @Test
  void masksSecretLookingVariables() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    EnvironmentReport.print(
        Map.of(
            "OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer abc",
            "OTEL_API_KEY", "k",
            "KAFKA_SASL_PASSWORD", "p",
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318"),
        new PrintStream(bytes, true, StandardCharsets.UTF_8));

    assertThat(bytes.toString(StandardCharsets.UTF_8).lines())
        .containsExactly(
            "[KAFKA_SASL_PASSWORD, [REDACTED]]",
            "[OTEL_API_KEY, [REDACTED]]",
            "[OTEL_EXPORTER_OTLP_ENDPOINT, http://collector:4318]",
            "[OTEL_EXPORTER_OTLP_HEADERS, [REDACTED]]");
  }

  @Test
  void prefixMatchingIsCaseInsensitive() {
    assertThat(EnvironmentReport.filterRelevant(Map.of("otel_resource_attributes", "a=b", "other", "x")))
        .containsOnlyKeys("otel_resource_attributes");
  }

  @Test
  void isSensitiveFollowsTheOriginalPattern() {
    assertThat(EnvironmentReport.isSensitive("OTEL_EXPORTER_OTLP_HEADERS")).isTrue();
    assertThat(EnvironmentReport.isSensitive("OTEL_EXPORTER_OTLP_TRACES_HEADERS")).isTrue();
    assertThat(EnvironmentReport.isSensitive("KAFKA_ADDR")).isFalse();
    assertThat(EnvironmentReport.isSensitive("OTEL_SERVICE_NAME")).isFalse();
    assertThat(EnvironmentReport.isSensitive("MONKEY")).isFalse();
    assertThat(EnvironmentReport.isSensitive(null)).isFalse();
  }
}
