/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.util.List;
import java.util.Map;

/** A tool the agent can offer to the model (built-in shop tool or one loaded from the MCP server). */
interface AgentTool {

  String name();

  String description();

  /** JSON schema of the arguments, as sent to the LLM. */
  Map<String, Object> inputSchema();

  /**
   * Runs the tool. Returns the {@code ToolMessage} content: a string, or a list of content blocks
   * (MCP tools). Argument validation errors raise {@link ToolArguments.InvalidArguments}; other
   * exceptions abort the request (HTTP 500), like unhandled tool exceptions in LangGraph.
   */
  Object call(Map<String, Object> args);

  /** Where the tool list comes from; evaluated on every request (the MCP list is re-read each time). */
  interface Source {
    List<AgentTool> tools();
  }
}
