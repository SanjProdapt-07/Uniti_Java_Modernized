/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the shop tools (the same ten tools {@code AstronomyShopMcp._register_tools} registers) into
 * the Spring AI MCP server (streamable HTTP transport, endpoint {@code /mcp}).
 */
@Configuration(proxyBeanMethods = false)
class McpServerConfiguration {

  /** httpx.Timeout(10.0): connect/read/write/pool all 10 seconds. */
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Bean
  RestClient shopRestClient(RestClient.Builder builder) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(TIMEOUT);
    return builder.requestFactory(factory).build();
  }

  @Bean
  ShopTools shopTools(
      RestClient shopRestClient,
      @Value("${shop.application-endpoint}") String applicationEndpoint,
      OpenTelemetry openTelemetry) {
    return new ShopTools(shopRestClient, applicationEndpoint, openTelemetry);
  }

  @Bean
  TracedToolRunner tracedToolRunner(
      ShopTools shopTools,
      OpenTelemetry openTelemetry,
      @Value("${traceloop.trace-content:true}") boolean traceContent,
      @Value("${spring.ai.mcp.server.name}") String serverName) {
    return new TracedToolRunner(shopTools, openTelemetry, traceContent, serverName);
  }

  /**
   * One {@link SyncToolSpecification} per shop tool, with the name, description and input schema
   * FastMCP reports for the Python functions.
   */
  @Bean
  List<SyncToolSpecification> astronomyShopTools(ShopTools shopTools, TracedToolRunner runner) {
    List<SyncToolSpecification> specs = new ArrayList<>();
    for (ShopTools.Tool tool : shopTools.tools()) {
      McpSchema.Tool mcpTool =
          McpSchema.Tool.builder()
              .name(tool.name())
              .description(tool.description())
              .inputSchema(tool.inputSchema())
              .build();
      specs.add(
          SyncToolSpecification.builder()
              .tool(mcpTool)
              .callHandler((exchange, request) -> runner.run(tool.name(), request, exchange))
              .build());
    }
    return specs;
  }

  /**
   * Tool arguments are validated by {@link ToolArguments} (pydantic-style coercion and messages,
   * as FastMCP did), not by the SDK's strict JSON-schema validator.
   */
  @Bean
  @Primary
  McpSyncServerCustomizer toolInputValidationCustomizer() {
    // immediateExecution(true) is what Spring AI's own servlet customizer sets: tool handlers
    // run on the request thread, so the servlet span / OpenTelemetry context is current
    return spec -> spec.validateToolInputs(false).immediateExecution(true);
  }
}
