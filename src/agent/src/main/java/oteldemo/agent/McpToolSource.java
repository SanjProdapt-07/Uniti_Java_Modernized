/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Tools loaded from the MCP server ({@code load_mcp_tools(session)}): the tool list is read from the
 * server on every agent request and each call goes over the MCP session.
 */
final class McpToolSource implements AgentTool.Source, AutoCloseable {

  /** An MCP tool call that came back with {@code isError} (a ToolException in langchain-mcp-adapters). */
  static final class McpToolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    McpToolException(String message) {
      super(message);
    }
  }

  private final McpSyncClient client;

  McpToolSource(McpSyncClient client) {
    this.client = client;
  }

  @Override
  public void close() {
    try {
      client.closeGracefully();
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(McpToolSource.class)
          .error("Error closing connection : {}", e.toString());
    }
  }

  @Override
  public List<AgentTool> tools() {
    List<AgentTool> out = new ArrayList<>();
    String cursor = null;
    do {
      McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
      for (McpSchema.Tool t : page.tools()) {
        out.add(new McpTool(client, t));
      }
      cursor = page.nextCursor();
    } while (cursor != null);
    return out;
  }

  private record McpTool(McpSyncClient client, McpSchema.Tool tool) implements AgentTool {

    @Override
    public String name() {
      return tool.name();
    }

    @Override
    public String description() {
      return tool.description() == null ? "" : tool.description();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> inputSchema() {
      JsonNode node = PyJson.MAPPER.valueToTree(tool.inputSchema());
      Map<String, Object> schema = PyJson.MAPPER.convertValue(node, LinkedHashMap.class);
      return schema;
    }

    @Override
    public Object call(Map<String, Object> args) {
      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest(tool.name(), args));
      List<Map<String, Object>> blocks = new ArrayList<>();
      StringBuilder text = new StringBuilder();
      for (McpSchema.Content c : result.content()) {
        if (c instanceof McpSchema.TextContent t) {
          Map<String, Object> block = new LinkedHashMap<>();
          block.put("type", "text");
          block.put("text", t.text());
          blocks.add(block);
          text.append(t.text());
        }
      }
      if (Boolean.TRUE.equals(result.isError())) {
        throw new McpToolException(text.toString());
      }
      return blocks;
    }
  }
}
