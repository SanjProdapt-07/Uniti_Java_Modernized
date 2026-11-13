/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Minimal in-JVM HTTP server that stands in for the shipping and email services. */
final class TestHttpServer implements AutoCloseable {

  record Request(String method, String path, Map<String, List<String>> headers, String body) {
    String header(String name) {
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name)) {
          return entry.getValue().get(0);
        }
      }
      return null;
    }
  }

  record Response(int status, String body) {}

  private final HttpServer server;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<Request, Response>> handlers = new ConcurrentHashMap<>();
  private final List<String> callLog;

  TestHttpServer(List<String> callLog) {
    this.callLog = callLog;
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/", this::handle);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void on(String path, Function<Request, Response> handler) {
    handlers.put(path, handler);
  }

  List<Request> requests() {
    return requests;
  }

  void reset() {
    requests.clear();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Request request =
        new Request(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            Map.copyOf(exchange.getRequestHeaders()),
            body);
    requests.add(request);
    if (callLog != null) {
      callLog.add("HTTP " + request.path());
    }
    Function<Request, Response> handler = handlers.get(request.path());
    Response response = handler == null ? new Response(404, "") : handler.apply(request);
    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
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
