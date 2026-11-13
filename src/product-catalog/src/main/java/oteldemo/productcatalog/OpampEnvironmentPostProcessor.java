/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Runs before the OpenTelemetry SDK is built, so that the {@code service.instance.id} chosen for
 * OpAMP (see {@link OpampIdentity}) is part of the telemetry resource. The Go service did the
 * same by appending it to {@code OTEL_RESOURCE_ATTRIBUTES}.
 */
public class OpampEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String PROPERTY_SOURCE = "opampIdentity";
  static final String INSTANCE_UID_PROPERTY = "opamp.instance-uid";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    String endpoint = environment.getProperty("opamp.server.endpoint", environment.getProperty("OPAMP_SERVER_ENDPOINT", ""));
    String configured =
        firstNonEmpty(
            environment.getProperty("otel.resource.attributes." + OpampIdentity.SERVICE_INSTANCE_ID),
            OpampIdentity.serviceInstanceIdFrom(environment.getProperty("OTEL_RESOURCE_ATTRIBUTES")),
            OpampIdentity.serviceInstanceIdFrom(environment.getProperty("otel.resource.attributes")));

    OpampIdentity.prepare(endpoint, configured, OpampIdentity::randomInstanceUid)
        .ifPresent(
            identity -> {
              Map<String, Object> properties = new LinkedHashMap<>();
              properties.put(INSTANCE_UID_PROPERTY, HexFormat.of().formatHex(identity.instanceUid()));
              identity
                  .additionalResourceAttributes()
                  .forEach((key, value) -> properties.put("otel.resource.attributes." + key, value));
              environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, properties));
            });
  }

  private static String firstNonEmpty(String... values) {
    for (String value : values) {
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    return null;
  }
}
