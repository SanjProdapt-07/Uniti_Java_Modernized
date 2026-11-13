/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.quote;

import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mirrors Slim's {@code BodyParsingMiddleware}: the body is decoded according to the request's
 * media type. The result is either an "array" ({@link Map} for objects and form data, {@link
 * List} for JSON lists) or {@code null} when nothing usable could be parsed. XML bodies are parsed
 * to an object by Slim, which the quote calculation cannot use either, so they also yield null.
 */
final class RequestBodyParser {

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private RequestBodyParser() {}

  static Object parse(String contentTypeHeader, byte[] body) {
    String mediaType = mediaType(contentTypeHeader);
    if (mediaType == null) {
      return null;
    }
    String input = new String(body, StandardCharsets.UTF_8);
    if (mediaType.equals("application/json") || mediaType.endsWith("+json")) {
      return parseJson(input);
    }
    if (mediaType.equals("application/x-www-form-urlencoded")) {
      return parseForm(input);
    }
    return null;
  }

  private static String mediaType(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    int semicolon = header.indexOf(';');
    String type = semicolon >= 0 ? header.substring(0, semicolon) : header;
    return type.trim().toLowerCase(Locale.ROOT);
  }

  private static Object parseJson(String input) {
    JsonNode root;
    try {
      root = JSON.readTree(input);
    } catch (JacksonException e) {
      return null;
    }
    if (root == null || !(root.isObject() || root.isArray())) {
      return null;
    }
    return convert(root);
  }

  private static Object convert(JsonNode node) {
    if (node.isObject()) {
      Map<String, Object> map = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        map.put(entry.getKey(), convert(entry.getValue()));
      }
      return map;
    }
    if (node.isArray()) {
      List<Object> list = new ArrayList<>();
      node.forEach(child -> list.add(convert(child)));
      return list;
    }
    if (node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isIntegralNumber()) {
      BigInteger big = node.bigIntegerValue();
      if (big.bitLength() <= 63) {
        return big.longValue();
      }
      return big;
    }
    if (node.isNumber()) {
      return node.doubleValue();
    }
    return node.asString();
  }

  /** parse_str(): {@code key=value&key2=value2}, the last occurrence of a key wins. */
  private static Object parseForm(String input) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (input.isEmpty()) {
      return map;
    }
    for (String pair : input.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String key = decode(eq >= 0 ? pair.substring(0, eq) : pair);
      String value = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
      map.put(key, value);
    }
    return map;
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return s;
    }
  }
}
