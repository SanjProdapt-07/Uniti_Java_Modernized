/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Error pages in the format of nginx with {@code server_tokens off}, e.g. for a missing image:
 * 404 with a small HTML body.
 */
@RestController
class NginxStyleErrorController implements ErrorController {

  @RequestMapping("/error")
  ResponseEntity<String> error(HttpServletRequest request) {
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    if (code instanceof Integer value) {
      HttpStatus resolved = HttpStatus.resolve(value);
      if (resolved != null) {
        status = resolved;
      }
    }
    String title = status.value() + " " + status.getReasonPhrase();
    String body = "<html>\r\n<head><title>" + title + "</title></head>\r\n<body>\r\n<center><h1>"
        + title + "</h1></center>\r\n<hr><center>nginx</center>\r\n</body>\r\n</html>\r\n";
    return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
  }
}
