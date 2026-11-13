/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.email;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Reproduces Sinatra's error behavior: the {@code error do} block and the default 404. */
@RestControllerAdvice
class EmailExceptionHandler {

  private static final Logger logger = LoggerFactory.getLogger(EmailExceptionHandler.class);

  /**
   * The original's {@code error} block recorded the exception on the current server span, and
   * Sinatra answered 500; its block returned the span rather than a body, so the body is empty.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<Void> onError(Exception e) {
    Span.current().recordException(e);
    logger.error("Failed to send the order confirmation", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(EmailController.HTML_UTF8)
        .build();
  }

  /** Sinatra's page for an unknown route, including a GET or other verb on the POST route. */
  @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
  ResponseEntity<String> onNotFound(Exception e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(EmailController.HTML_UTF8)
        .body("<h1>Not Found</h1>");
  }
}
