/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.api;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Equivalent of the failure branch of {@code InstrumentationMiddleware.ts}: the exception is
 * recorded on the server span, the span is marked as errored and the client receives the same
 * {@code 500 Internal Server Error} Next.js produced for an unhandled error.
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
class ApiExceptionHandler {

  @ExceptionHandler(ApiSupport.InvalidJsonException.class)
  ResponseEntity<String> invalidJson(ApiSupport.InvalidJsonException e, HttpServletRequest request) {
    // Next.js' body parser rejects malformed JSON before the route runs, so the middleware never sees it.
    request.setAttribute(ApiInstrumentationInterceptor.HANDLED, Boolean.TRUE);
    return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("Invalid JSON");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<String> failed(Exception e, HttpServletRequest request) {
    Span span = Span.current();
    span.recordException(e);
    span.setStatus(StatusCode.ERROR);
    ApiInstrumentationInterceptor.logFailure(request, e);
    request.setAttribute(ApiInstrumentationInterceptor.HANDLED, Boolean.TRUE);
    return ResponseEntity.status(500)
        .contentType(MediaType.TEXT_PLAIN)
        .body("Internal Server Error");
  }
}
