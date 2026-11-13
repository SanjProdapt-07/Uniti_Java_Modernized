/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import java.net.http.HttpClient;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/** Creates per-user {@link UserHttpSession}s sharing one (OpenTelemetry instrumented) HTTP client. */
public class HttpSessionFactory {

  private final HttpClient httpClient;
  private final Supplier<String> host;
  private final String userAgent;
  private final RequestStats stats;
  private final LoadGeneratorMetrics metrics;
  private final JsonMapper mapper;

  public HttpSessionFactory(
      HttpClient httpClient,
      Supplier<String> host,
      String userAgent,
      RequestStats stats,
      LoadGeneratorMetrics metrics,
      JsonMapper mapper) {
    this.httpClient = httpClient;
    this.host = host;
    this.userAgent = userAgent;
    this.stats = stats;
    this.metrics = metrics;
    this.mapper = mapper;
  }

  public UserHttpSession newSession() {
    return new UserHttpSession(httpClient, host, userAgent, stats, metrics, mapper);
  }
}
