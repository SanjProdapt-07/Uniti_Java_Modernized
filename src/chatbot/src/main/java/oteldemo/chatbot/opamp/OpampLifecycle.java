/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot.opamp;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Starts the OpAMP client when {@code OPAMP_SERVER_ENDPOINT} is set ({@code start_opamp_agent}) and
 * stops it with the application ({@code stop_opamp_agent}). {@code
 * OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY} disables TLS verification of the server.
 */
@Component
class OpampLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(OpampLifecycle.class);

  private static final Set<String> IDENTIFYING_KEYS =
      Set.of("service.name", "service.instance.id", "service.namespace");

  private final String endpoint;
  private final String skipTlsVerify;
  private final ObjectProvider<Resource> resource;
  private OpampAgent agent;

  OpampLifecycle(
      @Value("${opamp.server.endpoint:}") String endpoint,
      @Value("${opamp.server.tls-insecure-skip-verify:}") String skipTlsVerify,
      ObjectProvider<Resource> resource) {
    this.endpoint = endpoint;
    this.skipTlsVerify = skipTlsVerify;
    this.resource = resource;
  }

  @Override
  public void start() {
    if (endpoint == null || endpoint.isEmpty()) {
      return;
    }
    boolean skipVerification;
    try {
      skipVerification = parseBool("OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY", skipTlsVerify);
    } catch (IllegalArgumentException e) {
      log.error("OpAMP client disabled: {}", e.getMessage());
      return;
    }
    Map<String, Object> identifying = new LinkedHashMap<>();
    Map<String, Object> nonIdentifying = new LinkedHashMap<>();
    Resource res = resource.getIfAvailable(Resource::getDefault);
    for (Map.Entry<AttributeKey<?>, Object> e : res.getAttributes().asMap().entrySet()) {
      Object value = e.getValue();
      if (value == null) {
        continue;
      }
      if (value instanceof List<?> list) {
        value = list.toString();
      }
      (IDENTIFYING_KEYS.contains(e.getKey().getKey()) ? identifying : nonIdentifying)
          .put(e.getKey().getKey(), value);
    }
    OpampWebSocketTransport transport =
        new OpampWebSocketTransport(endpoint, Duration.ofSeconds(1), skipVerification);
    agent = new OpampAgent(transport::send, identifying, nonIdentifying, Duration.ofSeconds(30));
    agent.start();
    log.info("Started OpAMP client");
  }

  @Override
  public void stop() {
    if (agent != null) {
      agent.stop(Duration.ofSeconds(5));
      agent = null;
      log.info("Stopped OpAMP client");
    }
  }

  @Override
  public boolean isRunning() {
    return agent != null;
  }

  static boolean parseBool(String key, String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    String normalized = value.toLowerCase();
    if (Set.of("true", "1", "yes", "y", "on").contains(normalized)) {
      return true;
    }
    if (Set.of("false", "0", "no", "n", "off").contains(normalized)) {
      return false;
    }
    throw new IllegalArgumentException(key + " must be a boolean");
  }
}
