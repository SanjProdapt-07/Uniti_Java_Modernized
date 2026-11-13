/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.page;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Spring Boot's default error handling so that every unmatched or failed request gets the
 * HTML pages of {@code pages/404.tsx} / {@code pages/500.tsx} (Next.js renders its 404 page for
 * unknown {@code /api/*} paths too). These pages have no header/footer on purpose, as before.
 */
@Controller
class ErrorPageController implements ErrorController {

  private final WindowEnvironment windowEnvironment;

  ErrorPageController(WindowEnvironment windowEnvironment) {
    this.windowEnvironment = windowEnvironment;
  }

  @RequestMapping("${server.error.path:/error}")
  String error(HttpServletRequest request, HttpServletResponse response, Model model) {
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int status = code instanceof Integer value ? value : 500;
    response.setStatus(status);
    model.addAttribute("env", windowEnvironment.current());
    if (status == 404) {
      model.addAttribute("title", "Otel Demo - Page Not Found");
      return "error/404";
    }
    model.addAttribute("title", "Otel Demo - Server Error");
    return "error/500";
  }
}
