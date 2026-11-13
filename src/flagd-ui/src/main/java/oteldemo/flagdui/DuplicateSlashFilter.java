/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Envoy matches {@code /feature} and rewrites that prefix to {@code /}, so a browser request for
 * {@code /feature/api/read} reaches the service as {@code //api/read}. Phoenix ignores empty path
 * segments; Spring MVC does not, so repeated slashes are collapsed (via an internal forward) before
 * the request is dispatched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DuplicateSlashFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    String path = uri.substring(context.length());
    if (path.contains("//")) {
      String normalized = path.replaceAll("/{2,}", "/");
      request.getRequestDispatcher(normalized).forward(request, response);
      return;
    }
    chain.doFilter(request, response);
  }
}
