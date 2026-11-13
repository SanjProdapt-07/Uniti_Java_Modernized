/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.api;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Port of {@code utils/telemetry/InstrumentationMiddleware.ts} for every {@code /api/*} route: sets
 * the {@code http.status_code} attribute on the server span and writes the
 * {@code API request completed} / {@code API request failed} log lines.
 */
public class ApiInstrumentationInterceptor implements HandlerInterceptor {

  /** Request attribute set by {@link ApiExceptionHandler} once it answered a failed request. */
  static final String HANDLED = ApiInstrumentationInterceptor.class.getName() + ".handled";

  private static final Logger logger = LoggerFactory.getLogger(ApiInstrumentationInterceptor.class);

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    int status = response.getStatus();
    Span.current().setAttribute("http.status_code", (long) status);
    if (request.getAttribute(HANDLED) == null) {
      logger
          .atInfo()
          .addKeyValue("http.request.method", request.getMethod())
          .addKeyValue("url.path", request.getRequestURI())
          .addKeyValue("http.response.status_code", status)
          .log("API request completed");
    }
  }

  static void logFailure(HttpServletRequest request, Exception error) {
    logger
        .atError()
        .setCause(error)
        .addKeyValue("http.request.method", request.getMethod())
        .addKeyValue("url.path", request.getRequestURI())
        .addKeyValue("http.response.status_code", 500)
        .log("API request failed");
  }
}
