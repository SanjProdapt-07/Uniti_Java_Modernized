/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Executes a shop tool for an MCP {@code tools/call} request and produces the same
 * Traceloop-style spans the Python MCP instrumentation (opentelemetry-instrumentation-mcp
 * FastMCP hook) emitted: a {@code mcp.server} span containing a {@code <tool>.tool} span.
 */
final class TracedToolRunner {

  private static final AttributeKey<String> SPAN_KIND =
      AttributeKey.stringKey("traceloop.span.kind");
  private static final AttributeKey<String> ENTITY_NAME =
      AttributeKey.stringKey("traceloop.entity.name");
  private static final AttributeKey<String> WORKFLOW_NAME =
      AttributeKey.stringKey("traceloop.workflow.name");
  private static final AttributeKey<String> ENTITY_INPUT =
      AttributeKey.stringKey("traceloop.entity.input");
  private static final AttributeKey<String> ENTITY_OUTPUT =
      AttributeKey.stringKey("traceloop.entity.output");
  private static final AttributeKey<String> MCP_RESPONSE_VALUE =
      AttributeKey.stringKey("mcp.response.value");
  private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

  /** Key under which {@link OtelContextExtractor} stores the request thread's OTel context. */
  static final String OTEL_CONTEXT_KEY = "otel.context";

  private final ShopTools shopTools;
  private final Tracer tracer;
  private final boolean traceContent;
  private final String workflowName;

  TracedToolRunner(
      ShopTools shopTools, OpenTelemetry openTelemetry, boolean traceContent, String serverName) {
    this.shopTools = shopTools;
    this.tracer = openTelemetry.getTracer("oteldemo.mcp");
    this.traceContent = traceContent;
    this.workflowName = serverName + ".mcp";
  }

  McpSchema.CallToolResult run(
      String toolName, McpSchema.CallToolRequest request, McpSyncServerExchange exchange) {
    Context parent = parentContext(exchange);
    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();

    Span mcpSpan =
        tracer
            .spanBuilder("mcp.server")
            .setParent(parent)
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
    mcpSpan.setAttribute(SPAN_KIND, "server");
    mcpSpan.setAttribute(ENTITY_NAME, "mcp.server");
    mcpSpan.setAttribute(WORKFLOW_NAME, workflowName);
    try (Scope ignored = mcpSpan.makeCurrent()) {
      Span toolSpan =
          tracer.spanBuilder(toolName + ".tool").setSpanKind(SpanKind.INTERNAL).startSpan();
      toolSpan.setAttribute(SPAN_KIND, "tool");
      toolSpan.setAttribute(ENTITY_NAME, toolName);
      toolSpan.setAttribute(WORKFLOW_NAME, workflowName);
      if (traceContent) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tool_name", toolName);
        input.put("arguments", arguments);
        toolSpan.setAttribute(ENTITY_INPUT, PyJson.dumps(input, true));
      }
      try (Scope toolScope = toolSpan.makeCurrent()) {
        Object result;
        try {
          result = shopTools.invoke(toolName, arguments);
        } catch (RuntimeException e) {
          String message = failureMessage(toolName, e);
          recordError(toolSpan, e, message);
          recordError(mcpSpan, e, message);
          return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
        }
        String text = result instanceof String s ? s : compact(result);
        if (traceContent && !text.isEmpty()) {
          String output = PyJson.dumps(List.of(Map.of("type", "text", "content", text)), true);
          toolSpan.setAttribute(ENTITY_OUTPUT, output);
          mcpSpan.setAttribute(MCP_RESPONSE_VALUE, output);
        }
        toolSpan.setStatus(StatusCode.OK);
        mcpSpan.setStatus(StatusCode.OK);
        McpSchema.CallToolResult.Builder out =
            McpSchema.CallToolResult.builder().addTextContent(text).isError(false);
        // FastMCP returns structured content for dict results only (list/str results have none)
        JsonNode node = result instanceof JsonNode n ? n : null;
        if (node != null && node.isObject()) {
          out.structuredContent(PyJson.MAPPER.convertValue(node, Map.class));
        } else if (result instanceof Map<?, ?> m) {
          out.structuredContent(m);
        }
        return out.build();
      } finally {
        toolSpan.end();
      }
    } finally {
      mcpSpan.end();
    }
  }

  private static String failureMessage(String toolName, RuntimeException e) {
    if (e instanceof ToolArguments.InvalidArguments) {
      return e.getMessage();
    }
    return "Error calling tool '" + toolName + "': " + ShopTools.message(e);
  }

  private static void recordError(Span span, Throwable e, String message) {
    span.setAttribute(ERROR_TYPE, e.getClass().getSimpleName());
    span.setStatus(StatusCode.ERROR, message);
  }

  private static String compact(Object result) {
    return PyJson.toNode(result).toString();
  }

  private static Context parentContext(McpSyncServerExchange exchange) {
    try {
      McpTransportContext tc = exchange.transportContext();
      Object ctx = tc == null ? null : tc.get(OTEL_CONTEXT_KEY);
      if (ctx instanceof Context c) {
        return c;
      }
    } catch (RuntimeException ignored) {
      // fall through to the thread's current context
    }
    return Context.current();
  }
}
