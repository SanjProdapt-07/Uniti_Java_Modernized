/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Translates the {@code DB_CONNECTION_STRING} of the original service, an Npgsql keyword/value
 * string such as {@code Host=astronomy-db;Username=astronomy_user;Password=...;Database=astronomy_db},
 * into a PostgreSQL JDBC URL plus credentials. A value that already is a {@code jdbc:} URL is
 * passed through unchanged.
 */
record NpgsqlConnectionString(String jdbcUrl, String username, String password) {

  static NpgsqlConnectionString parse(String value) {
    String trimmed = value.trim();
    if (trimmed.regionMatches(true, 0, "jdbc:", 0, 5)) {
      return new NpgsqlConnectionString(trimmed, null, null);
    }

    Map<String, String> keywords = new LinkedHashMap<>();
    for (String pair : splitPairs(trimmed)) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        if (pair.isBlank()) {
          continue;
        }
        throw new IllegalArgumentException("Format of the initialization string does not conform to specification");
      }
      String key = normalize(pair.substring(0, eq));
      keywords.put(key, unquote(pair.substring(eq + 1).trim()));
    }

    String host = firstOf(keywords, "host", "server");
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("Host missing from the connection string");
    }
    String port = firstOf(keywords, "port");
    String username = firstOf(keywords, "username", "userid", "user", "uid");
    String password = firstOf(keywords, "password", "pwd");
    String database = firstOf(keywords, "database", "db");
    if (database == null || database.isBlank()) {
      // Npgsql falls back to the user name
      database = username;
    }

    // Npgsql accepts a comma separated host list; JDBC takes host:port pairs.
    StringBuilder hosts = new StringBuilder();
    for (String h : host.split(",")) {
      if (hosts.length() > 0) {
        hosts.append(',');
      }
      String name = h.trim();
      hosts.append(name.contains(":") && !name.startsWith("[") ? "[" + name + "]" : name);
      hosts.append(':').append(port == null || port.isBlank() ? "5432" : port.trim());
    }

    StringBuilder url = new StringBuilder("jdbc:postgresql://").append(hosts).append('/');
    if (database != null) {
      url.append(database);
    }
    String sslMode = sslMode(firstOf(keywords, "sslmode"));
    if (sslMode != null) {
      url.append("?sslmode=").append(sslMode);
    }
    return new NpgsqlConnectionString(url.toString(), username, password);
  }

  private static String sslMode(String npgsqlValue) {
    if (npgsqlValue == null) {
      return null;
    }
    return switch (normalize(npgsqlValue)) {
      case "disable" -> "disable";
      case "allow" -> "allow";
      case "prefer" -> "prefer";
      case "require" -> "require";
      case "verifyca" -> "verify-ca";
      case "verifyfull" -> "verify-full";
      default -> null;
    };
  }

  private static String firstOf(Map<String, String> keywords, String... names) {
    for (String name : names) {
      String value = keywords.get(name);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /** Keywords are case-insensitive and ignore spaces, underscores and dashes. */
  private static String normalize(String keyword) {
    return keyword.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
  }

  /** Splits on ';' outside of single or double quotes. */
  private static java.util.List<String> splitPairs(String value) {
    java.util.List<String> pairs = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (quote != 0) {
        current.append(c);
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '\'' || c == '"') {
        quote = c;
        current.append(c);
      } else if (c == ';') {
        pairs.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    pairs.add(current.toString());
    return pairs;
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '\'' || first == '"') && first == last) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }
}
