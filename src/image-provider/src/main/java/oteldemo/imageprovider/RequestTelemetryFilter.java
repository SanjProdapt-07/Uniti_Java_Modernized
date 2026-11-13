/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Names every server span {@code image-provider} (nginx: {@code otel_span_name image-provider}) and keeps the
 * counters behind {@code /status}. Registered inside the OpenTelemetry servlet filter so that the server span
 * is current; the name is set last so it overrides any route based name.
 */
class RequestTelemetryFilter extends OncePerRequestFilter {

  static final String SPAN_NAME = "image-provider";

  private final StubStatus stubStatus;

  RequestTelemetryFilter(StubStatus stubStatus) {
    this.stubStatus = stubStatus;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    stubStatus.requestStarted();
    try {
      chain.doFilter(request, response);
    } finally {
      stubStatus.requestFinished();
      Span.current().updateName(SPAN_NAME);
    }
  }
}
