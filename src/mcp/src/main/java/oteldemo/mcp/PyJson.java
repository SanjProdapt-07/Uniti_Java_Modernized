/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Helpers that reproduce Python's {@code json.dumps} / {@code repr} text formats, which are visible
 * to the LLM (tool messages) and to the shop API (query parameters) in the original services.
 */
final class PyJson {

  static final JsonMapper MAPPER = JsonMapper.builder().build();

  private PyJson() {}

  /** Equivalent of {@code json.dumps(obj, ensure_ascii=ensureAscii)} (separators ", " and ": "). */
  static String dumps(Object value, boolean ensureAscii) {
    return dumps(value, ensureAscii, false);
  }

  /** Equivalent of {@code json.dumps(obj, ensure_ascii=ensureAscii, sort_keys=sortKeys)}. */
  static String dumps(Object value, boolean ensureAscii, boolean sortKeys) {
    StringBuilder sb = new StringBuilder();
    write(sb, toNode(value), ensureAscii, sortKeys);
    return sb.toString();
  }

  static JsonNode toNode(Object value) {
    if (value instanceof JsonNode node) {
      return node;
    }
    return MAPPER.valueToTree(value);
  }

  private static void write(StringBuilder sb, JsonNode node, boolean ensureAscii, boolean sortKeys) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      sb.append("null");
    } else if (node.isObject()) {
      sb.append('{');
      boolean first = true;
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
      if (sortKeys) {
        entries.sort(Map.Entry.comparingByKey());
      }
      for (Map.Entry<String, JsonNode> e : entries) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        quote(sb, e.getKey(), ensureAscii);
        sb.append(": ");
        write(sb, e.getValue(), ensureAscii, sortKeys);
      }
      sb.append('}');
    } else if (node.isArray()) {
      sb.append('[');
      Iterator<JsonNode> it = node.iterator();
      boolean first = true;
      while (it.hasNext()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        write(sb, it.next(), ensureAscii, sortKeys);
      }
      sb.append(']');
    } else if (node.isString()) {
      quote(sb, node.asString(), ensureAscii);
    } else if (node.isBoolean()) {
      sb.append(node.asBoolean() ? "true" : "false");
    } else if (node.isFloatingPointNumber()) {
      sb.append(pyFloat(node.doubleValue()));
    } else {
      sb.append(node.asString());
    }
  }

  /** Python {@code repr(float)}. */
  static String pyFloat(double d) {
    if (Double.isNaN(d)) {
      return "NaN";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "Infinity" : "-Infinity";
    }
    if (d == 0) {
      return (1 / d < 0) ? "-0.0" : "0.0";
    }
    double abs = Math.abs(d);
    if (abs >= 1e-4 && abs < 1e16) {
      String plain = new BigDecimal(Double.toString(d)).toPlainString();
      return plain.contains(".") ? plain : plain + ".0";
    }
    // exponent form: 1e+16, 1.5e-05
    BigDecimal bd = new BigDecimal(Double.toString(d));
    String unscaled = bd.unscaledValue().abs().toString();
    int exp = unscaled.length() - 1 - bd.scale();
    String digits = unscaled.replaceAll("0+$", "");
    if (digits.isEmpty()) {
      digits = "0";
    }
    String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
    String expText = (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
    return (d < 0 ? "-" : "") + mantissa + "e" + expText;
  }

  private static void quote(StringBuilder sb, String s, boolean ensureAscii) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20 || (ensureAscii && c > 0x7e)) {
            sb.append("\\u").append(String.format("%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  /** Python {@code repr()} for JSON-like values. */
  static String repr(Object value) {
    if (value == null) {
      return "None";
    }
    if (value instanceof String s) {
      return reprString(s);
    }
    if (value instanceof Boolean b) {
      return b ? "True" : "False";
    }
    if (value instanceof Double || value instanceof Float) {
      return pyFloat(((Number) value).doubleValue());
    }
    if (value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Map<?, ?> m) {
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
      }
      return sb.append('}').toString();
    }
    if (value instanceof List<?> l) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(repr(l.get(i)));
      }
      return sb.append(']').toString();
    }
    return value.toString();
  }

  private static String reprString(String s) {
    char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
    StringBuilder sb = new StringBuilder().append(quote);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == quote || c == '\\') {
        sb.append('\\').append(c);
      } else if (c == '\n') {
        sb.append("\\n");
      } else if (c == '\r') {
        sb.append("\\r");
      } else if (c == '\t') {
        sb.append("\\t");
      } else if (c < 0x20 || c == 0x7f) {
        sb.append("\\x").append(String.format("%02x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append(quote).toString();
  }

  /** Python type name used in pydantic / TypeError messages. */
  static String pyTypeName(Object value) {
    if (value == null) {
      return "NoneType";
    }
    if (value instanceof String) {
      return "str";
    }
    if (value instanceof Boolean) {
      return "bool";
    }
    if (value instanceof Double || value instanceof Float) {
      return "float";
    }
    if (value instanceof Number) {
      return "int";
    }
    if (value instanceof Map) {
      return "dict";
    }
    if (value instanceof List) {
      return "list";
    }
    return value.getClass().getSimpleName();
  }
}
