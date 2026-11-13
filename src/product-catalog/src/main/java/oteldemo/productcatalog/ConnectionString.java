/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the value of {@code DB_CONNECTION_STRING} into something the PostgreSQL JDBC driver
 * understands.
 *
 * <p>The Go service handed the variable to {@code lib/pq}, which takes either a URL
 * ({@code postgres://user:password@host:port/dbname?sslmode=disable}) or a libpq key/value string
 * ({@code host=db user=u password=p dbname=d sslmode=disable}). Both forms are accepted here, and
 * a ready-made {@code jdbc:} URL is passed through untouched.
 */
record ConnectionString(String jdbcUrl, String username, String password, Map<String, String> properties) {

  // lib/pq / libpq parameter names that differ in pgjdbc
  private static final Map<String, String> RENAMED =
      Map.of(
          "connect_timeout", "connectTimeout",
          "application_name", "ApplicationName",
          "target_session_attrs", "targetServerType");

  static ConnectionString parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DB_CONNECTION_STRING environment variable not set");
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("jdbc:")) {
      return new ConnectionString(trimmed, null, null, Map.of());
    }
    if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
      return parseUrl(trimmed);
    }
    return parseKeyValue(trimmed);
  }

  private static ConnectionString parseUrl(String value) {
    String rest = value.substring(value.indexOf("://") + 3);
    String query = null;
    int questionMark = rest.indexOf('?');
    if (questionMark >= 0) {
      query = rest.substring(questionMark + 1);
      rest = rest.substring(0, questionMark);
    }
    String authority = rest;
    String path = "";
    int slash = rest.indexOf('/');
    if (slash >= 0) {
      authority = rest.substring(0, slash);
      path = rest.substring(slash + 1);
    }

    String username = null;
    String password = null;
    int at = authority.lastIndexOf('@');
    if (at >= 0) {
      String userInfo = authority.substring(0, at);
      authority = authority.substring(at + 1);
      int colon = userInfo.indexOf(':');
      username = decode(colon >= 0 ? userInfo.substring(0, colon) : userInfo, false);
      password = colon >= 0 ? decode(userInfo.substring(colon + 1), false) : null;
    }

    String host = authority;
    int port = -1;
    int colon = authority.lastIndexOf(':');
    if (colon >= 0 && authority.indexOf(']', colon) < 0) {
      host = authority.substring(0, colon);
      String portText = authority.substring(colon + 1);
      if (!portText.isEmpty()) {
        try {
          port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("invalid port \"" + portText + "\" in connection string", e);
        }
      }
    }
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    host = decode(host, false);

    Map<String, String> properties = new LinkedHashMap<>();
    if (query != null) {
      for (String pair : query.split("&")) {
        if (pair.isEmpty()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String key = decode(eq >= 0 ? pair.substring(0, eq) : pair, true);
        String val = eq >= 0 ? decode(pair.substring(eq + 1), true) : "";
        properties.put(key, val);
      }
    }
    return build(host, port, decode(path, false), username, password, properties);
  }

  /** Parses libpq's {@code key=value key2='quoted value'} format. */
  private static ConnectionString parseKeyValue(String value) {
    Map<String, String> all = new LinkedHashMap<>();
    int i = 0;
    int n = value.length();
    while (i < n) {
      while (i < n && Character.isWhitespace(value.charAt(i))) {
        i++;
      }
      if (i >= n) {
        break;
      }
      int keyStart = i;
      while (i < n && value.charAt(i) != '=' && !Character.isWhitespace(value.charAt(i))) {
        i++;
      }
      String key = value.substring(keyStart, i);
      while (i < n && Character.isWhitespace(value.charAt(i))) {
        i++;
      }
      if (i >= n || value.charAt(i) != '=') {
        throw new IllegalArgumentException("missing \"=\" after \"" + key + "\" in connection info string");
      }
      i++; // '='
      while (i < n && Character.isWhitespace(value.charAt(i))) {
        i++;
      }
      StringBuilder val = new StringBuilder();
      if (i < n && value.charAt(i) == '\'') {
        i++;
        while (i < n && value.charAt(i) != '\'') {
          if (value.charAt(i) == '\\' && i + 1 < n) {
            i++;
          }
          val.append(value.charAt(i++));
        }
        if (i >= n) {
          throw new IllegalArgumentException("unterminated quoted string in connection info string");
        }
        i++; // closing quote
      } else {
        while (i < n && !Character.isWhitespace(value.charAt(i))) {
          if (value.charAt(i) == '\\' && i + 1 < n) {
            i++;
          }
          val.append(value.charAt(i++));
        }
      }
      all.put(key, val.toString());
    }

    String host = all.remove("host");
    String port = all.remove("port");
    String database = all.remove("dbname");
    String user = all.remove("user");
    String password = all.remove("password");
    return build(
        host == null ? "" : host,
        port == null || port.isBlank() ? -1 : Integer.parseInt(port.trim()),
        database == null ? "" : database,
        user,
        password,
        all);
  }

  private static ConnectionString build(
      String host, int port, String database, String user, String password, Map<String, String> params) {
    if (host.isEmpty()) {
      host = "localhost";
    }
    StringBuilder url = new StringBuilder("jdbc:postgresql://");
    url.append(host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host);
    if (port > 0) {
      url.append(':').append(port);
    }
    url.append('/').append(database);

    Map<String, String> translated = new LinkedHashMap<>();
    params.forEach((key, val) -> translated.put(RENAMED.getOrDefault(key, key), val));
    return new ConnectionString(url.toString(), user, password, translated);
  }

  // net/url: '+' means a space in query strings only
  private static String decode(String s, boolean query) {
    return URLDecoder.decode(query ? s : s.replace("+", "%2B"), StandardCharsets.UTF_8);
  }
}
