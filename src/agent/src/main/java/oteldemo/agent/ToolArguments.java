/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Argument validation/coercion equivalent to what pydantic does for the Python tool signatures
 * ({@code user_id: str}, {@code quantity: int = 1}, untyped parameters accept anything). Error text
 * follows pydantic's "N validation error(s) for call[tool]" layout.
 */
final class ToolArguments {

  /** Raised when the supplied arguments do not satisfy the tool signature. */
  static final class InvalidArguments extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidArguments(String message) {
      super(message);
    }
  }

  private ToolArguments() {}

  @SuppressWarnings("unchecked")
  static Map<String, Object> validate(ShopTools.Tool tool, Map<String, Object> raw) {
    Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
    List<String> required =
        (List<String>) tool.inputSchema().getOrDefault("required", List.<String>of());
    List<String[]> errors = new ArrayList<>();
    Map<String, Object> result = new LinkedHashMap<>();

    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      String field = entry.getKey();
      Map<String, Object> spec = (Map<String, Object>) entry.getValue();
      if (!raw.containsKey(field)) {
        if (required.contains(field)) {
          errors.add(
              new String[] {
                field,
                "Missing required argument",
                "missing_argument",
                "input_value=" + PyJson.repr(raw) + ", input_type=dict"
              });
        } else if (spec.containsKey("default")) {
          result.put(field, spec.get("default"));
        }
        continue;
      }
      Object value = raw.get(field);
      String type = (String) spec.get("type");
      if (type == null) {
        result.put(field, value);
      } else if (type.equals("string")) {
        if (value instanceof String) {
          result.put(field, value);
        } else {
          errors.add(
              error(field, "Input should be a valid string", "string_type", value));
        }
      } else if (type.equals("integer")) {
        coerceInteger(field, value, result, errors);
      } else {
        result.put(field, value);
      }
    }
    for (Map.Entry<String, Object> e : raw.entrySet()) {
      if (!properties.containsKey(e.getKey())) {
        errors.add(
            error(e.getKey(), "Unexpected keyword argument", "unexpected_keyword_argument", e.getValue()));
      }
    }
    if (!errors.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append(errors.size())
          .append(" validation error")
          .append(errors.size() == 1 ? "" : "s")
          .append(" for call[")
          .append(tool.name())
          .append(']');
      for (String[] e : errors) {
        sb.append('\n')
            .append(e[0])
            .append("\n  ")
            .append(e[1])
            .append(" [type=")
            .append(e[2])
            .append(", ")
            .append(e[3])
            .append("]\n    For further information visit https://errors.pydantic.dev/2.14/v/")
            .append(e[2]);
      }
      throw new InvalidArguments(sb.toString());
    }
    return result;
  }

  private static void coerceInteger(
      String field, Object value, Map<String, Object> result, List<String[]> errors) {
    if (value instanceof Integer || value instanceof Long) {
      result.put(field, value);
    } else if (value instanceof Boolean b) {
      result.put(field, b ? 1 : 0);
    } else if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (d == Math.rint(d) && !Double.isInfinite(d)) {
        result.put(field, (long) d);
      } else {
        errors.add(
            error(
                field,
                "Input should be a valid integer, got a number with a fractional part",
                "int_from_float",
                value));
      }
    } else if (value instanceof String s) {
      try {
        result.put(field, Long.parseLong(s.strip()));
      } catch (NumberFormatException e) {
        errors.add(
            error(
                field,
                "Input should be a valid integer, unable to parse string as an integer",
                "int_parsing",
                value));
      }
    } else if (value instanceof Number n) {
      result.put(field, n.longValue());
    } else {
      errors.add(error(field, "Input should be a valid integer", "int_type", value));
    }
  }

  private static String[] error(String field, String message, String type, Object value) {
    return new String[] {
      field,
      message,
      type,
      "input_value=" + PyJson.repr(value) + ", input_type=" + PyJson.pyTypeName(value)
    };
  }
}
