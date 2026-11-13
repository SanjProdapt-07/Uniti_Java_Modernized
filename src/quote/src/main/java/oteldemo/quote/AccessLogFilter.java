/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.quote;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Prints the same per request line to stdout as the PHP service, for example {@code [2024-01-01
 * 12:00:00+00:00] "POST /getquote HTTP/1.1" 200 5}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessLogFilter extends OncePerRequestFilter {

  private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    try {
      chain.doFilter(request, wrapper);
    } finally {
      int size = wrapper.getContentSize();
      wrapper.copyBodyToResponse();
      System.out.printf(
          "[%s] \"%s %s %s\" %d %d %n",
          FORMAT.format(OffsetDateTime.now(ZoneId.systemDefault())),
          request.getMethod(),
          request.getRequestURI(),
          request.getProtocol(),
          wrapper.getStatus(),
          size);
    }
  }
}
