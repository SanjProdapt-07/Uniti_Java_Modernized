/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON decoding and encoding that mimics what the Elixir service did with Jason: documents are
 * decoded into plain {@link Map}/{@link List} trees whose objects are key-sorted (Elixir maps
 * iterate in key order), and are encoded either compactly ({@code Jason.encode!/1}) or with the
 * two-space pretty format of {@code Jason.encode!(x, pretty: true)}.
 */
public final class FlagJson {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private FlagJson() {}

  /** Raised for text that is not a single valid JSON value. */
  public static final class InvalidJsonException extends Exception {
    InvalidJsonException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Decodes a JSON text. Empty input is invalid, as in Jason. */
  public static Object decode(String json) throws InvalidJsonException {
    JsonNode node = readTree(json);
    if (node == null || node.isMissingNode()) {
      throw new InvalidJsonException("empty JSON document", null);
    }
    return convert(node);
  }

  public static JsonNode readTree(String json) throws InvalidJsonException {
    try {
      return MAPPER.readTree(json);
    } catch (JacksonException e) {
      throw new InvalidJsonException("invalid JSON", e);
    }
  }

  /** Converts a parsed Jackson tree into the sorted Map/List representation. */
  public static Object convert(JsonNode node) {
    if (node.isObject()) {
      Map<String, Object> map = new TreeMap<>();
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        map.put(entry.getKey(), convert(entry.getValue()));
      }
      return map;
    }
    if (node.isArray()) {
      List<Object> list = new ArrayList<>(node.size());
      for (JsonNode element : node) {
        list.add(convert(element));
      }
      return list;
    }
    if (node.isString()) {
      return node.stringValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNull()) {
      return null;
    }
    if (node.isIntegralNumber()) {
      BigInteger big = node.bigIntegerValue();
      return big.bitLength() < 64 ? (Object) big.longValue() : big;
    }
    return node.doubleValue();
  }

  public static String encode(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, false, 0);
    return out.toString();
  }

  public static String encodePretty(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value, true, 0);
    return out.toString();
  }

  private static void write(StringBuilder out, Object value, boolean pretty, int depth) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      writeString(out, s);
    } else if (value instanceof Boolean b) {
      out.append(b ? "true" : "false");
    } else if (value instanceof Double || value instanceof Float) {
      out.append(formatDouble(((Number) value).doubleValue()));
    } else if (value instanceof Number n) {
      out.append(n);
    } else if (value instanceof Map<?, ?> map) {
      writeMap(out, map, pretty, depth);
    } else if (value instanceof Iterable<?> iterable) {
      writeList(out, iterable, pretty, depth);
    } else {
      writeString(out, value.toString());
    }
  }

  private static void writeMap(StringBuilder out, Map<?, ?> map, boolean pretty, int depth) {
    if (map.isEmpty()) {
      out.append("{}");
      return;
    }
    Map<String, Object> sorted = new TreeMap<>();
    map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
    out.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : sorted.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      newline(out, pretty, depth + 1);
      writeString(out, entry.getKey());
      out.append(pretty ? ": " : ":");
      write(out, entry.getValue(), pretty, depth + 1);
    }
    newline(out, pretty, depth);
    out.append('}');
  }

  private static void writeList(StringBuilder out, Iterable<?> list, boolean pretty, int depth) {
    if (!list.iterator().hasNext()) {
      out.append("[]");
      return;
    }
    out.append('[');
    boolean first = true;
    for (Object element : list) {
      if (!first) {
        out.append(',');
      }
      first = false;
      newline(out, pretty, depth + 1);
      write(out, element, pretty, depth + 1);
    }
    newline(out, pretty, depth);
    out.append(']');
  }

  private static void newline(StringBuilder out, boolean pretty, int depth) {
    if (pretty) {
      out.append('\n');
      out.append("  ".repeat(depth));
    }
  }

  private static void writeString(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** Shortest round-trip representation, in the style Erlang/Jason prints floats. */
  static String formatDouble(double d) {
    if (d == 0.0) {
      return (1 / d < 0) ? "-0.0" : "0.0";
    }
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("cannot encode " + d + " as JSON");
    }
    String s = Double.toString(d);
    double abs = Math.abs(d);
    if (s.indexOf('E') >= 0 && abs >= 1e-4 && abs < 1e16) {
      s = new BigDecimal(s).toPlainString();
      if (s.indexOf('.') < 0) {
        s = s + ".0";
      }
      return s;
    }
    return s.replace("E", "e");
  }
}
