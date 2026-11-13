/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test: a real MCP client talks streamable HTTP to the running server, which in turn
 * calls a fake frontend.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"otel.traces.exporter=none", "otel.metrics.exporter=none", "otel.logs.exporter=none",
      "server.shutdown=immediate"})
class McpServerIntegrationTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static HttpServer frontend;
  private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

  @LocalServerPort int port;
  @Autowired InMemorySpanExporter spans;
  @Autowired InMemoryMetricReader metrics;

  McpSyncClient client;

  @BeforeAll
  static void startFrontend() throws IOException {
    frontend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    frontend.createContext(
        "/",
        exchange -> {
          String body;
          try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          REQUESTS.add(
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI()
                  + (body.isEmpty() ? "" : " " + body));
          String path = exchange.getRequestURI().getPath();
          int status = 200;
          String response = "[{\"id\":\"OLJCESPC7Z\",\"priceUsd\":{\"units\":175,\"nanos\":0}}]";
          if (path.equals("/api/currency")) {
            response = "{\"currencyCodes\":[\"USD\",\"EUR\"]}";
          } else if (path.equals("/api/products/missing")) {
            status = 404;
            response = "not found";
          }
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    frontend.start();
  }

  @AfterAll
  static void stopFrontend() {
    frontend.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "shop.application-endpoint", () -> "127.0.0.1:" + frontend.getAddress().getPort());
  }

  @BeforeEach
  void connect() {
    REQUESTS.clear();
    spans.reset();
    client =
        McpClient.sync(
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                    .endpoint("/mcp")
                    .build())
            .requestTimeout(Duration.ofSeconds(20))
            .build();
    client.initialize();
  }

  @AfterEach
  void disconnect() {
    client.closeGracefully();
  }

  @Test
  void serverIdentifiesAsAstronomyShopMcp() {
    assertThat(client.getServerInfo().name()).isEqualTo("astronomy-shop-mcp");
  }

  @Test
  void toolListMatchesFastMcpOutput() throws Exception {
    JsonNode expected =
        JSON.readTree(getClass().getResourceAsStream("/fastmcp-tools.json"));
    List<McpSchema.Tool> tools = client.listTools().tools();

    assertThat(tools).hasSize(expected.size());
    for (int i = 0; i < expected.size(); i++) {
      JsonNode exp = expected.get(i);
      McpSchema.Tool actual = tools.get(i);
      assertThat(actual.name()).isEqualTo(exp.get("name").asString());
      assertThat(actual.description()).isEqualTo(exp.get("description").asString());
      JsonNode actualSchema = JSON.valueToTree(actual.inputSchema());
      // properties/required/type/additionalProperties must be identical, in order
      assertThat(actualSchema.get("type")).isEqualTo(exp.get("inputSchema").get("type"));
      assertThat(actualSchema.get("properties")).isEqualTo(exp.get("inputSchema").get("properties"));
      assertThat(actualSchema.get("additionalProperties"))
          .isEqualTo(exp.get("inputSchema").get("additionalProperties"));
      assertThat(actualSchema.path("required")).isEqualTo(exp.get("inputSchema").path("required"));
      assertThat(JSON.writeValueAsString(actualSchema.get("properties")))
          .isEqualTo(JSON.writeValueAsString(exp.get("inputSchema").get("properties")));
    }
  }

  @Test
  void listProductsReturnsFrontendJsonAsTextContent() {
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest("list_products", Map.of()));

    assertThat(result.isError()).isFalse();
    assertThat(((McpSchema.TextContent) result.content().get(0)).text())
        .isEqualTo("[{\"id\":\"OLJCESPC7Z\",\"priceUsd\":{\"units\":175,\"nanos\":0}}]");
    // a JSON list result has no structured content in FastMCP
    assertThat(result.structuredContent()).isNull();
    assertThat(REQUESTS).containsExactly("GET /api/products");
  }

  @Test
  void dictResultsCarryStructuredContent() {
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest("get_supported_currencies", Map.of()));

    assertThat(((McpSchema.TextContent) result.content().get(0)).text())
        .isEqualTo("{\"currencyCodes\":[\"USD\",\"EUR\"]}");
    assertThat(result.structuredContent()).isNotNull();
  }

  @Test
  void addToCartSendsOriginalPayloadAndDefaultQuantity() {
    client.callTool(
        new McpSchema.CallToolRequest(
            "add_to_cart", Map.of("user_id", "u1", "product_id", "OLJCESPC7Z")));

    assertThat(REQUESTS)
        .containsExactly(
            "POST /api/cart {\"item\":{\"productId\":\"OLJCESPC7Z\",\"quantity\":1},\"userId\":\"u1\"}");
  }

  @Test
  void httpErrorsAreReturnedAsTextNotToolErrors() {
    McpSchema.CallToolResult result =
        client.callTool(
            new McpSchema.CallToolRequest("get_product", Map.of("product_id", "missing")));

    assertThat(result.isError()).isFalse();
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    assertThat(text)
        .startsWith("Error while fetching product missing: Client error '404 Not Found' for url '")
        .endsWith(
            "/api/products/missing'\nFor more information check: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/404");
  }

  @Test
  void invalidArgumentsProduceToolError() {
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest("get_cart", Map.of("zz", 1)));

    assertThat(result.isError()).isTrue();
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    assertThat(text).startsWith("2 validation errors for call[get_cart]");
    assertThat(text).contains("Missing required argument").contains("Unexpected keyword argument");
  }

  @Test
  void toolCallEmitsTraceloopSpansAndCountsCall() {
    client.callTool(new McpSchema.CallToolRequest("get_supported_currencies", Map.of()));

    List<SpanData> finished = spans.getFinishedSpanItems();
    SpanData tool =
        finished.stream()
            .filter(s -> s.getName().equals("get_supported_currencies.tool"))
            .findFirst()
            .orElseThrow();
    SpanData server =
        finished.stream().filter(s -> s.getName().equals("mcp.server")).findFirst().orElseThrow();
    assertThat(tool.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(tool.getAttributes().get(AttributeKey.stringKey("traceloop.span.kind")))
        .isEqualTo("tool");
    assertThat(tool.getAttributes().get(AttributeKey.stringKey("traceloop.entity.name")))
        .isEqualTo("get_supported_currencies");
    assertThat(tool.getAttributes().get(AttributeKey.stringKey("traceloop.workflow.name")))
        .isEqualTo("astronomy-shop-mcp.mcp");
    assertThat(tool.getAttributes().get(AttributeKey.stringKey("traceloop.entity.input")))
        .isEqualTo("{\"tool_name\": \"get_supported_currencies\", \"arguments\": {}}");
    assertThat(tool.getAttributes().get(AttributeKey.stringKey("traceloop.entity.output")))
        .isEqualTo("[{\"type\": \"text\", \"content\": \"{\\\"currencyCodes\\\":[\\\"USD\\\",\\\"EUR\\\"]}\"}]");
    assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("traceloop.span.kind")))
        .isEqualTo("server");
    assertThat(server.getAttributes().get(AttributeKey.stringKey("mcp.response.value")))
        .isNotNull();

    // the outbound HTTP call to the frontend is traced as a client span below the tool span
    assertThat(finished)
        .anySatisfy(
            s -> {
              assertThat(s.getKind()).isEqualTo(SpanKind.CLIENT);
              assertThat(s.getTraceId()).isEqualTo(tool.getTraceId());
            });

    assertThat(metrics.collectAllMetrics())
        .anySatisfy(
            m -> {
              assertThat(m.getName()).isEqualTo("demo.tool.calls");
              assertThat(m.getUnit()).isEqualTo("{call}");
              assertThat(m.getLongSumData().getPoints())
                  .anySatisfy(
                      (LongPointData p) -> {
                        assertThat(
                                p.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
                            .isEqualTo("get_supported_currencies");
                        assertThat(p.getValue()).isGreaterThanOrEqualTo(1);
                      });
            });
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    InMemoryMetricReader inMemoryMetricReader() {
      return InMemoryMetricReader.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(
        InMemorySpanExporter spanExporter, InMemoryMetricReader metricReader) {
      return customizer ->
          customizer
              .addTracerProviderCustomizer(
                  (tracerProvider, config) ->
                      tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addMeterProviderCustomizer(
                  (meterProvider, config) -> meterProvider.registerMetricReader(metricReader));
    }
  }
}
