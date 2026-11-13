/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Request helpers shared by the API controllers; they mirror the Next.js API route semantics. */
final class ApiSupport {

  private ApiSupport() {}

  /** {@code res.status(405).send('')}: every route answers unsupported methods like this. */
  static ResponseEntity<Void> methodNotAllowed() {
    return ResponseEntity.status(405).build();
  }

  /** First value of a query parameter or the default when it is absent (Next's {@code query.x = d}). */
  static String param(HttpServletRequest request, String name, String defaultValue) {
    String value = request.getParameter(name);
    return value == null ? defaultValue : value;
  }

  /**
   * Parameter values the way the original handlers flatten them: a repeated parameter is already
   * an array, a single value is split on commas, an absent one is an empty list.
   */
  static List<String> listParam(HttpServletRequest request, String name) {
    String[] values = request.getParameterValues(name);
    if (values == null) {
      return List.of();
    }
    if (values.length > 1) {
      return List.of(values);
    }
    return List.of(values[0].split(",", -1));
  }

  /** Parses a JSON request body; an empty body is an empty object, anything else invalid is a 400. */
  static JsonNode body(HttpServletRequest request, ObjectMapper mapper) {
    try {
      byte[] bytes = request.getInputStream().readAllBytes();
      if (bytes.length == 0) {
        return mapper.createObjectNode();
      }
      return mapper.readTree(bytes);
    } catch (JacksonException | IOException e) {
      throw new InvalidJsonException(e);
    }
  }

  static List<String> splitCommaList(List<String> values) {
    List<String> out = new ArrayList<>();
    for (String value : values) {
      if (!value.isEmpty()) {
        out.addAll(List.of(value.split(",", -1)));
      }
    }
    return out;
  }

  /** Request body that is not valid JSON; answered with {@code 400 Invalid JSON} like Next. */
  static final class InvalidJsonException extends RuntimeException {
    InvalidJsonException(Throwable cause) {
      super("Invalid JSON", cause);
    }
  }
}
