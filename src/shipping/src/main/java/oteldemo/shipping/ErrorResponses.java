/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Reproduces how actix-web answered requests the routes could not handle: a malformed or
 * incomplete JSON body is a 400 with a plain-text message, a body that is not declared as JSON is
 * a 415, a known route with the wrong method is an empty 405 and every unknown route an empty 404.
 */
@RestControllerAdvice
class ErrorResponses {

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<String> unreadableBody(HttpMessageNotReadableException e) {
    Throwable cause = e.getMostSpecificCause();
    String detail = cause.getMessage() != null ? cause.getMessage() : "invalid request body";
    int newline = detail.indexOf('\n');
    if (newline >= 0) {
      detail = detail.substring(0, newline);
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.TEXT_PLAIN)
        .body("Json deserialize error: " + detail);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<String> unsupportedMediaType() {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .contentType(MediaType.TEXT_PLAIN)
        .body("Content type error");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Void> methodNotAllowed() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Void> notFound() {
    return ResponseEntity.notFound().build();
  }
}
