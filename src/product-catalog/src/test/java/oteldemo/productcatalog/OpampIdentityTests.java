/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/** Ports of opamp_test.go (TestPrepareOpAMPIdentity*), plus the Spring wiring around them. */
class OpampIdentityTests {

  private static final byte[] UID = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");

  @Test
  void disabledWithoutAnEndpoint() {
    assertThat(OpampIdentity.prepare("", null, () -> UID)).isEmpty();
    assertThat(OpampIdentity.prepare(null, null, () -> UID)).isEmpty();
  }

  @Test
  void theInstanceUidBecomesTheServiceInstanceIdOfTheResource() {
    Optional<OpampIdentity> identity =
        OpampIdentity.prepare("wss://localhost:4320/v1/opamp", null, () -> UID);

    assertThat(identity).isPresent();
    assertThat(identity.get().instanceUid()).isEqualTo(UID);
    assertThat(identity.get().additionalResourceAttributes())
        .isEqualTo(Map.of("service.instance.id", "00112233445566778899aabbccddeeff"));
  }

  @Test
  void aConfiguredServiceInstanceIdIsPreserved() {
    Optional<OpampIdentity> identity =
        OpampIdentity.prepare("wss://localhost:4320/v1/opamp", "configured-id", () -> UID);

    assertThat(identity).isPresent();
    assertThat(identity.get().additionalResourceAttributes()).isEmpty();
  }

  @Test
  void randomInstanceUidsAre16BytesAndDiffer() {
    byte[] first = OpampIdentity.randomInstanceUid();
    byte[] second = OpampIdentity.randomInstanceUid();

    assertThat(first).hasSize(16);
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void findsTheServiceInstanceIdInAnOtelResourceAttributesList() {
    assertThat(OpampIdentity.serviceInstanceIdFrom("service.namespace=opentelemetry-demo,service.instance.id=configured-id"))
        .isEqualTo("configured-id");
    assertThat(OpampIdentity.serviceInstanceIdFrom("service.namespace=opentelemetry-demo")).isNull();
    assertThat(OpampIdentity.serviceInstanceIdFrom(null)).isNull();
  }

  // ---- the EnvironmentPostProcessor ---------------------------------------------------------

  @Test
  void theEnvironmentIsLeftAloneWhileOpampIsDisabled() {
    MockEnvironment env =
        new MockEnvironment().withProperty("OTEL_RESOURCE_ATTRIBUTES", "service.namespace=opentelemetry-demo");

    new OpampEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getPropertySources().contains(OpampEnvironmentPostProcessor.PROPERTY_SOURCE)).isFalse();
    assertThat(env.getProperty("otel.resource.attributes.service.instance.id")).isNull();
  }

  @Test
  void theServiceInstanceIdIsSharedWithTheResourceWhenEnabled() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("OPAMP_SERVER_ENDPOINT", "wss://localhost:4320/v1/opamp")
            .withProperty("OTEL_RESOURCE_ATTRIBUTES", "service.namespace=opentelemetry-demo");

    new OpampEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

    String uid = env.getProperty("opamp.instance-uid");
    assertThat(uid).hasSize(32);
    assertThat(env.getProperty("otel.resource.attributes.service.instance.id")).isEqualTo(uid);
  }

  @Test
  void aServiceInstanceIdFromTheEnvironmentIsPreserved() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("opamp.server.endpoint", "wss://localhost:4320/v1/opamp")
            .withProperty(
                "OTEL_RESOURCE_ATTRIBUTES",
                "service.namespace=opentelemetry-demo,service.instance.id=configured-id");

    new OpampEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("opamp.instance-uid")).hasSize(32);
    assertThat(env.getProperty("otel.resource.attributes.service.instance.id")).isNull();
  }

  @Test
  void aServiceInstanceIdFromSpringPropertiesIsPreserved() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("opamp.server.endpoint", "wss://localhost:4320/v1/opamp")
            .withProperty("otel.resource.attributes.service.instance.id", "from-properties");

    new OpampEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("otel.resource.attributes.service.instance.id")).isEqualTo("from-properties");
  }

  @Test
  void worksWithTheStandardEnvironmentToo() {
    StandardEnvironment env = new StandardEnvironment();
    new OpampEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    // no OPAMP_SERVER_ENDPOINT in the environment of the build: nothing happens
    if (System.getenv("OPAMP_SERVER_ENDPOINT") == null) {
      assertThat(env.getProperty("opamp.instance-uid")).isNull();
    }
  }
}
