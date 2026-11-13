/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A conversation message with the same JSON shape LangChain's message models have when FastAPI
 * serializes the LangGraph result ({@code type} of human/ai/tool/system, {@code content}, {@code
 * tool_calls}, ...). The chatbot only reads {@code content} of the last message, but the whole
 * structure is returned from {@code /prompt} like in the original.
 */
final class AgentMessage {

  /** A tool call requested by the model. {@code args} is the parsed JSON object. */
  record ToolCall(String id, String name, Map<String, Object> args) {}

  private final String type;
  private final Object content;
  private final String id;
  private final String name;
  private final List<ToolCall> toolCalls;
  private final List<Map<String, Object>> invalidToolCalls;
  private final String toolCallId;
  private final String status;
  private final Map<String, Object> responseMetadata;
  private final Map<String, Object> usageMetadata;
  private final Map<String, Object> extra;

  private AgentMessage(Builder b) {
    this.type = b.type;
    this.content = b.content;
    this.id = b.id;
    this.name = b.name;
    this.toolCalls = b.toolCalls;
    this.invalidToolCalls = b.invalidToolCalls;
    this.toolCallId = b.toolCallId;
    this.status = b.status;
    this.responseMetadata = b.responseMetadata;
    this.usageMetadata = b.usageMetadata;
    this.extra = b.extra;
  }

  String type() {
    return type;
  }

  /** String, or a list of content-block maps (MCP tool results, multimodal user content). */
  Object content() {
    return content;
  }

  String name() {
    return name;
  }

  List<ToolCall> toolCalls() {
    return toolCalls;
  }

  String toolCallId() {
    return toolCallId;
  }

  /** Message text: the string content, or the concatenated text blocks of a list content. */
  String text() {
    if (content instanceof String s) {
      return s;
    }
    if (content instanceof List<?> blocks) {
      StringBuilder sb = new StringBuilder();
      for (Object block : blocks) {
        if (block instanceof String s) {
          sb.append(s);
        } else if (block instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
          Object t = m.get("text");
          if (t != null) {
            sb.append(t);
          }
        }
      }
      return sb.toString();
    }
    return content == null ? "" : content.toString();
  }

  /** The JSON structure returned from {@code /prompt}. */
  Map<String, Object> toJson() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("content", content);
    m.put("additional_kwargs", new LinkedHashMap<>());
    m.put("response_metadata", responseMetadata == null ? new LinkedHashMap<>() : responseMetadata);
    m.put("type", type);
    m.put("name", name);
    m.put("id", id);
    if (type.equals("ai")) {
      List<Map<String, Object>> calls = new ArrayList<>();
      for (ToolCall c : toolCalls) {
        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("name", c.name());
        cm.put("args", c.args());
        cm.put("id", c.id());
        cm.put("type", "tool_call");
        calls.add(cm);
      }
      m.put("tool_calls", calls);
      m.put("invalid_tool_calls", invalidToolCalls);
      m.put("usage_metadata", usageMetadata);
    } else if (type.equals("tool")) {
      m.put("tool_call_id", toolCallId);
      m.put("artifact", null);
      m.put("status", status == null ? "success" : status);
    }
    // extra keys supplied on history messages (LangChain messages allow extra fields)
    for (Map.Entry<String, Object> e : extra.entrySet()) {
      m.putIfAbsent(e.getKey(), e.getValue());
    }
    return m;
  }

  static Builder builder(String type) {
    return new Builder(type);
  }

  static AgentMessage human(String text) {
    return builder("human").content(text).build();
  }

  static final class Builder {
    private final String type;
    private Object content = "";
    private String id = UUID.randomUUID().toString();
    private String name;
    private List<ToolCall> toolCalls = List.of();
    private List<Map<String, Object>> invalidToolCalls = List.of();
    private String toolCallId;
    private String status;
    private Map<String, Object> responseMetadata;
    private Map<String, Object> usageMetadata;
    private Map<String, Object> extra = Map.of();

    private Builder(String type) {
      this.type = type;
    }

    Builder content(Object content) {
      this.content = content;
      return this;
    }

    Builder id(String id) {
      this.id = id;
      return this;
    }

    Builder name(String name) {
      this.name = name;
      return this;
    }

    Builder toolCalls(List<ToolCall> toolCalls) {
      this.toolCalls = toolCalls;
      return this;
    }

    Builder invalidToolCalls(List<Map<String, Object>> invalid) {
      this.invalidToolCalls = invalid;
      return this;
    }

    Builder toolCallId(String toolCallId) {
      this.toolCallId = toolCallId;
      return this;
    }

    Builder status(String status) {
      this.status = status;
      return this;
    }

    Builder responseMetadata(Map<String, Object> responseMetadata) {
      this.responseMetadata = responseMetadata;
      return this;
    }

    Builder usageMetadata(Map<String, Object> usageMetadata) {
      this.usageMetadata = usageMetadata;
      return this;
    }

    Builder extra(Map<String, Object> extra) {
      this.extra = extra;
      return this;
    }

    AgentMessage build() {
      return new AgentMessage(this);
    }
  }
}
