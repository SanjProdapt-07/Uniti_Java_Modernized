/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** A tiny in-process HTTP site that records every request; stands in for the frontend proxy / agent. */
final class FakeSite implements AutoCloseable {

  record Recorded(String method, String uri, Map<String, List<String>> headers, String body) {
    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return String.join(",", e.getValue());
        }
      }
      return null;
    }

    String path() {
      int q = uri.indexOf('?');
      return q < 0 ? uri : uri.substring(0, q);
    }
  }

  record Reply(int status, String body, Map<String, String> headers) {
    static Reply ok(String body) {
      return new Reply(200, body, Map.of());
    }
  }

  private final HttpServer server;
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Reply> replies = new ConcurrentHashMap<>();

  FakeSite() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  String url() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  int port() {
    return server.getAddress().getPort();
  }

  List<Recorded> requests() {
    return requests;
  }

  void reply(String path, Reply reply) {
    replies.put(path, reply);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Recorded recorded =
        new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().toString(), Map.copyOf(exchange.getRequestHeaders()), body);
    requests.add(recorded);
    Reply reply = replies.getOrDefault(exchange.getRequestURI().getPath(), Reply.ok("{}"));
    reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
