/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Redirects {@code /some/path/} to {@code /some/path} with a 308, keeping the query string. */
class TrailingSlashRedirectFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (path.length() > 1 && path.endsWith("/")) {
      String target = path.replaceAll("/+$", "");
      if (target.isEmpty()) {
        target = "/";
      }
      String query = request.getQueryString();
      response.setStatus(308);
      response.setHeader("Location", query == null ? target : target + "?" + query);
      response.setContentLength(0);
      return;
    }
    chain.doFilter(request, response);
  }
}
