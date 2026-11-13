/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Error rendering equivalent to Phoenix's {@code ErrorHTML} and {@code ErrorJSON}: HTML requests
 * get the plain status message, requests below {@code /api} get {@code {"errors":{"detail": msg}}}.
 */
@Controller
public class ErrorPages implements ErrorController {

  /** Port of {@code Phoenix.Controller.status_message_from_template/1}. */
  public static String statusMessageFromTemplate(String template) {
    try {
      int code = Integer.parseInt(template.split("\\.", -1)[0]);
      return statusMessage(code);
    } catch (NumberFormatException e) {
      return "Internal Server Error";
    }
  }

  public static String statusMessage(int code) {
    HttpStatus status = HttpStatus.resolve(code);
    return status != null ? status.getReasonPhrase() : "Internal Server Error";
  }

  /** Equivalent of {@code ErrorJSON.render/2}. */
  public static Map<String, Object> errorJson(String template) {
    return Map.of("errors", Map.of("detail", statusMessageFromTemplate(template)));
  }

  /** The JSON error document for a status code, as a response. */
  public static ResponseEntity<String> json(int code) {
    String body = FlagJson.encode(Map.of("errors", Map.of("detail", statusMessage(code))));
    return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  @RequestMapping("${server.error.path:/error}")
  public ResponseEntity<String> error(HttpServletRequest request) {
    Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int code = statusAttribute instanceof Integer i ? i : 500;
    Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    if (path instanceof String p && (p.equals("/api") || p.startsWith("/api/"))) {
      return json(code);
    }
    return ResponseEntity.status(code)
        .contentType(MediaType.TEXT_HTML)
        .body(statusMessage(code));
  }
}
