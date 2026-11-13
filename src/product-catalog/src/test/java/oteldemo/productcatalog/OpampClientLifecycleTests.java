/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.opamp.client.internal.request.Request;
import io.opentelemetry.opamp.client.request.service.RequestService;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import opamp.proto.AgentCapabilities;
import opamp.proto.AgentToServer;
import opamp.proto.KeyValue;
import org.junit.jupiter.api.Test;

/** The OpAMP agent description, health and capabilities (startOpAMPClient in opamp.go). */
class OpampClientLifecycleTests {

  private static final String UID = "00112233445566778899aabbccddeeff";

  private static final Attributes RESOURCE =
      Attributes.builder()
          .put(AttributeKey.stringKey("service.name"), "product-catalog")
          .put(AttributeKey.stringKey("service.namespace"), "opentelemetry-demo")
          .put(AttributeKey.stringKey("service.instance.id"), UID)
          .put(AttributeKey.stringKey("service.criticality"), "high")
          .put(AttributeKey.stringKey("host.name"), "pc-host")
          .put(AttributeKey.longKey("process.pid"), 42L)
          .build();

  /** Records what the OpAMP client wants to send instead of opening a connection. */
  private static final class RecordingRequestService implements RequestService {
    String url;
    boolean skipTlsVerification;
    Supplier<Request> request;
    final AtomicInteger stops = new AtomicInteger();

    @Override
    public void start(Callback callback, Supplier<Request> request) {
      this.request = request;
    }

    @Override
    public void sendRequest() {}

    @Override
    public void stop() {
      stops.incrementAndGet();
    }
  }

  @Test
  void reportsTheResourceAsAgentDescriptionAndIsHealthy() {
    RecordingRequestService service = new RecordingRequestService();
    OpampClientLifecycle lifecycle = lifecycle("false", service);
    long before = System.currentTimeMillis() * 1_000_000L;

    lifecycle.start();

    assertThat(lifecycle.isRunning()).isTrue();
    assertThat(service.url).isEqualTo("wss://opamp-server:4320/v1/opamp");
    assertThat(service.skipTlsVerification).isFalse();

    AgentToServer message = service.request.get().getAgentToServer();
    assertThat(message.instance_uid.toByteArray()).isEqualTo(HexFormat.of().parseHex(UID));

    assertThat(attributes(message.agent_description.identifying_attributes))
        .containsOnlyKeys("service.name", "service.namespace", "service.instance.id")
        .containsEntry("service.name", "product-catalog")
        .containsEntry("service.instance.id", UID);
    assertThat(attributes(message.agent_description.non_identifying_attributes))
        .containsOnlyKeys("service.criticality", "host.name", "process.pid")
        .containsEntry("process.pid", "42");

    assertThat(message.health.healthy).isTrue();
    assertThat(message.health.start_time_unix_nano).isBetween(before, System.currentTimeMillis() * 1_000_000L + 1_000_000_000L);
    assertThat(message.capabilities & AgentCapabilities.AgentCapabilities_ReportsHealth.getValue())
        .isEqualTo(AgentCapabilities.AgentCapabilities_ReportsHealth.getValue());

    lifecycle.stop();
    assertThat(lifecycle.isRunning()).isFalse();
    assertThat(service.stops).hasValue(1);
  }

  @Test
  void theInsecureSkipVerifyOptionReachesTheTransport() {
    RecordingRequestService service = new RecordingRequestService();

    lifecycle("true", service).start();

    assertThat(service.skipTlsVerification).isTrue();
  }

  @Test
  void anUnparsableSkipVerifyValueOnlyFailsTheClient() {
    RecordingRequestService service = new RecordingRequestService();
    OpampClientLifecycle lifecycle = lifecycle("maybe", service);

    lifecycle.start(); // must not throw: the Go service logged the error and carried on

    assertThat(lifecycle.isRunning()).isFalse();
    assertThat(service.request).isNull();
  }

  @Test
  void theCommonSpellingsOfBooleansAreAccepted() {
    for (String truthy : List.of("1", "t", "T", "TRUE", "true", "True")) {
      RecordingRequestService service = new RecordingRequestService();
      lifecycle(truthy, service).start();
      assertThat(service.skipTlsVerification).as(truthy).isTrue();
    }
    for (String falsy : List.of("", "0", "f", "F", "FALSE", "false", "False")) {
      RecordingRequestService service = new RecordingRequestService();
      lifecycle(falsy, service).start();
      assertThat(service.skipTlsVerification).as(falsy).isFalse();
    }
  }

  private static OpampClientLifecycle lifecycle(String skipTlsVerification, RecordingRequestService service) {
    return new OpampClientLifecycle(
        "wss://opamp-server:4320/v1/opamp",
        skipTlsVerification,
        UID,
        RESOURCE,
        (url, skip) -> {
          service.url = url;
          service.skipTlsVerification = skip;
          return service;
        });
  }

  private static Map<String, String> attributes(List<KeyValue> keyValues) {
    return keyValues.stream()
        .collect(Collectors.toMap(kv -> kv.key, kv -> kv.value.string_value));
  }
}
