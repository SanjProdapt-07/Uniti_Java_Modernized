/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the chat history dictionaries of the request like {@code langchain convert_to_messages}. */
final class HistoryConverter {

  private HistoryConverter() {}

  /** Raised for history entries LangChain rejects (the original surfaced these as HTTP 500). */
  static final class InvalidMessage extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    InvalidMessage(String message) {
      super(message);
    }
  }

  static AgentMessage convert(Map<String, Object> message) {
    Map<String, Object> kwargs = new LinkedHashMap<>(message);
    Object type;
    if (kwargs.containsKey("role")) {
      type = kwargs.remove("role");
    } else if (kwargs.containsKey("type")) {
      type = kwargs.remove("type");
    } else {
      throw new InvalidMessage(
          "Message dict must contain 'role' and 'content' keys, got " + PyJson.repr(message));
    }
    if (!kwargs.containsKey("content")) {
      throw new InvalidMessage(
          "Message dict must contain 'role' and 'content' keys, got " + PyJson.repr(message));
    }
    Object content = kwargs.remove("content");
    if (content == null) {
      content = "";
    }
    String role = String.valueOf(type);
    String id = kwargs.get("id") instanceof String s ? s : null;
    kwargs.remove("id");
    String name = kwargs.get("name") instanceof String s ? s : null;
    kwargs.remove("name");

    AgentMessage.Builder b;
    switch (role) {
      case "human", "user" -> b = AgentMessage.builder("human");
      case "ai", "assistant" -> {
        b = AgentMessage.builder("ai");
        Object calls = kwargs.remove("tool_calls");
        if (calls instanceof List<?> list) {
          List<AgentMessage.ToolCall> tcs = new ArrayList<>();
          for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
              Object args = m.get("args");
              @SuppressWarnings("unchecked")
              Map<String, Object> argMap =
                  args instanceof Map ? (Map<String, Object>) args : new LinkedHashMap<>();
              tcs.add(
                  new AgentMessage.ToolCall(
                      m.get("id") == null ? null : String.valueOf(m.get("id")),
                      String.valueOf(m.get("name")),
                      argMap));
            }
          }
          b.toolCalls(tcs);
        }
      }
      case "system", "developer" -> b = AgentMessage.builder("system");
      case "tool" -> {
        b = AgentMessage.builder("tool");
        Object tcid = kwargs.remove("tool_call_id");
        if (tcid == null) {
          throw new InvalidMessage("Tool messages must have a tool_call_id");
        }
        b.toolCallId(String.valueOf(tcid));
        if (kwargs.get("status") instanceof String st) {
          b.status(st);
          kwargs.remove("status");
        }
      }
      default ->
          throw new InvalidMessage(
              "Unexpected message type: '"
                  + role
                  + "'. Use one of 'human', 'user', 'ai', 'assistant', 'function', 'tool',"
                  + " 'system', or 'developer'.");
    }
    if (id != null) {
      b.id(id);
    }
    return b.content(content).name(name).extra(kwargs).build();
  }

  static List<AgentMessage> convertAll(List<Map<String, Object>> history) {
    List<AgentMessage> out = new ArrayList<>();
    if (history != null) {
      for (Map<String, Object> m : history) {
        out.add(convert(m));
      }
    }
    return out;
  }
}
