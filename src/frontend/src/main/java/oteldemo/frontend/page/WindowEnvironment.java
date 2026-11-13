/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.page;

import io.opentelemetry.api.baggage.Baggage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code window.ENV} object the browser code reads. Port of the {@code envString} of
 * {@code pages/_document.tsx}: requests flagged as synthetic through the {@code synthetic_request}
 * baggage entry (the load generator) send their browser traces straight to the collector instead of
 * the public endpoint.
 */
@Component
public class WindowEnvironment {

  private final String platform;
  private final String webServiceName;
  private final String publicTracesEndpoint;
  private final String collectorHost;

  public WindowEnvironment(
      @Value("${frontend.env-platform:local}") String platform,
      @Value("${frontend.web-otel-service-name:frontend-web}") String webServiceName,
      @Value("${frontend.public-otel-traces-endpoint:}") String publicTracesEndpoint,
      @Value("${frontend.otel-collector-host:otel-collector}") String collectorHost) {
    this.platform = platform;
    this.webServiceName = webServiceName;
    this.publicTracesEndpoint = publicTracesEndpoint;
    this.collectorHost = collectorHost;
  }

  /** Values for the current request; must be called on the request thread (reads the baggage). */
  public Map<String, String> current() {
    boolean synthetic = "true".equals(Baggage.current().getEntryValue("synthetic_request"));
    String endpoint =
        synthetic ? "http://" + collectorHost + ":4318/v1/traces" : publicTracesEndpoint;

    Map<String, String> env = new LinkedHashMap<>();
    env.put("NEXT_PUBLIC_PLATFORM", platform);
    env.put("NEXT_PUBLIC_OTEL_SERVICE_NAME", webServiceName);
    env.put("NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", endpoint);
    env.put("IS_SYNTHETIC_REQUEST", String.valueOf(synthetic));
    return env;
  }
}
