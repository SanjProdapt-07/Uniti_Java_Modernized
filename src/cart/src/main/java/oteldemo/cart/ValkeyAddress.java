/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

/** Host and port of a {@code VALKEY_ADDR} value ({@code host[:port]}, port 6379 by default). */
record ValkeyAddress(String host, int port) {

  static final int DEFAULT_PORT = 6379;

  static ValkeyAddress parse(String address) {
    String value = address.trim();
    int colon = value.lastIndexOf(':');
    if (colon < 0 || value.indexOf(':') != colon) {
      // no port, or a bare IPv6 address
      return new ValkeyAddress(value, DEFAULT_PORT);
    }
    String host = value.substring(0, colon);
    String port = value.substring(colon + 1);
    return new ValkeyAddress(host, port.isEmpty() ? DEFAULT_PORT : Integer.parseInt(port));
  }

  /** The connection string of the original, which is what gets logged (never the password). */
  String connectionString(boolean tls) {
    return host + ":" + port + ",ssl=" + (tls ? "true" : "false") + ",allowAdmin=true,abortConnect=false";
  }
}
