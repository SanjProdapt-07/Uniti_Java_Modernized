/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ten built-in shop tools used when {@code MCP_ENABLED} is not {@code True}. Descriptions keep
 * the (indented) Python docstrings LangChain sends to the model.
 */
final class BuiltinToolSource implements AgentTool.Source {

  private final List<AgentTool> tools = new ArrayList<>();

  BuiltinToolSource(ShopTools shopTools) {
    // original order of the `tool_list` in agents.py
    for (String name :
        List.of(
            "add_to_cart",
            "checkout",
            "empty_cart",
            "get_ads",
            "get_cart",
            "get_product",
            "get_recommendations",
            "get_shipping_quote",
            "get_supported_currencies",
            "list_products")) {
      ShopTools.Tool tool = shopTools.tool(name);
      tools.add(new BuiltinTool(shopTools, tool));
    }
  }

  @Override
  public List<AgentTool> tools() {
    return tools;
  }

  private record BuiltinTool(ShopTools shopTools, ShopTools.Tool tool) implements AgentTool {

    @Override
    public String name() {
      return tool.name();
    }

    @Override
    public String description() {
      // LangChain uses the raw docstring: continuation lines keep their 4-space indentation
      return tool.description().replace("\n", "\n    ");
    }

    @Override
    public Map<String, Object> inputSchema() {
      // pydantic's args schema (no additionalProperties) as in the recorded LLM requests
      Map<String, Object> schema = new java.util.LinkedHashMap<>(tool.inputSchema());
      schema.remove("additionalProperties");
      return schema;
    }

    @Override
    public Object call(Map<String, Object> args) {
      Object result = shopTools.invoke(tool.name(), args);
      return result instanceof String s ? s : PyJson.dumps(result, false);
    }
  }
}
