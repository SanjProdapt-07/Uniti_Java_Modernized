/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP mode: the tool list is read from the MCP server and tool calls go over the MCP session. The
 * server is a minimal streamable-HTTP (JSON responses) stub.
 */
class McpToolSourceTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HttpServer server;
  private McpSyncClient client;
  private final List<String> methods = new CopyOnWriteArrayList<>();
  private volatile String toolResultJson = "{\"content\":[{\"type\":\"text\",\"text\":\"[1,2]\"}],\"isError\":false}";

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mcp", this::handle);
    server.start();
    client =
        McpClient.sync(
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.getAddress().getPort())
                    .endpoint("/mcp")
                    .build())
            .requestTimeout(Duration.ofSeconds(10))
            .build();
    client.initialize();
  }

  @AfterEach
  void stop() {
    client.close();
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("POST")) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    JsonNode request;
    try (InputStream in = exchange.getRequestBody()) {
      request = JSON.readTree(in.readAllBytes());
    }
    String method = request.path("method").asString("");
    methods.add(method);
    if (!request.has("id")) { // notification
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }
    String id = request.get("id").toString();
    String result =
        switch (method) {
          case "initialize" ->
              "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"stub\",\"version\":\"1\"}}";
          case "tools/list" ->
              "{\"tools\":[{\"name\":\"list_products\",\"description\":\"List all products.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},"
                  + "{\"name\":\"get_cart\",\"description\":\"Cart.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"user_id\":{\"type\":\"string\"}},\"required\":[\"user_id\"]}}]}";
          case "tools/call" -> toolResultJson;
          default -> "{}";
        };
    byte[] bytes =
        ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.getResponseHeaders().add("Mcp-Session-Id", "s1");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Test
  void toolListIsReadFromTheServerOnEveryCall() {
    McpToolSource source = new McpToolSource(client);

    List<AgentTool> first = source.tools();
    source.tools();

    assertThat(first.stream().map(AgentTool::name)).containsExactly("list_products", "get_cart");
    assertThat(first.get(1).description()).isEqualTo("Cart.");
    assertThat(first.get(1).inputSchema()).containsKey("properties");
    assertThat(methods.stream().filter("tools/list"::equals)).hasSize(2);
  }

  @Test
  void toolCallsGoOverTheMcpSessionAndReturnContentBlocks() {
    McpToolSource source = new McpToolSource(client);

    Object content = source.tools().get(1).call(Map.of("user_id", "u1"));

    assertThat(content).isEqualTo(List.of(Map.of("type", "text", "text", "[1,2]")));
    assertThat(methods).contains("initialize", "tools/call");
  }

  @Test
  void errorResultsFailTheRequest() {
    toolResultJson = "{\"content\":[{\"type\":\"text\",\"text\":\"boom\"}],\"isError\":true}";
    McpToolSource source = new McpToolSource(client);

    assertThatThrownBy(() -> source.tools().get(0).call(Map.of()))
        .isInstanceOf(McpToolSource.McpToolException.class)
        .hasMessage("boom");
  }
}
