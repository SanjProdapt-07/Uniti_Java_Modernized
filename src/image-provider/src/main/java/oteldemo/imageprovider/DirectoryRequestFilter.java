/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * nginx behavior for directories with {@code autoindex off} and no index file: {@code /dir} is redirected
 * (301) to {@code /dir/}, and {@code /dir/} (including {@code /}) answers 403 Forbidden.
 */
class DirectoryRequestFilter extends OncePerRequestFilter {

  private final Path root;

  DirectoryRequestFilter(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String uri = request.getRequestURI();
    if (isDirectory(uri)) {
      if (uri.endsWith("/")) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
      } else {
        String query = request.getQueryString();
        String location = request.getScheme() + "://" + request.getServerName() + ":"
            + request.getServerPort() + uri + "/" + (query == null ? "" : "?" + query);
        response.setHeader("Location", location);
        response.sendError(HttpServletResponse.SC_MOVED_PERMANENTLY);
      }
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean isDirectory(String uri) {
    try {
      String decoded = URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8);
      Path candidate = root.resolve(decoded.startsWith("/") ? decoded.substring(1) : decoded).normalize();
      return candidate.startsWith(root) && Files.isDirectory(candidate);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
