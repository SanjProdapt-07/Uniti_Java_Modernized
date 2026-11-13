/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The agent's chat model ({@code ChatLLM} in llm.py): one Spring AI {@link ChatClient} call per
 * model turn, preceded by the {@code aiSlowResponse} feature-flag delay and wrapped in the VCR
 * cassette replay/record behaviour of the original.
 */
class ChatLlm {

  private static final Logger log = LoggerFactory.getLogger(ChatLlm.class);

  /** A tool as offered to the model. */
  record ToolSpec(String name, String description, Map<String, Object> schema) {}

  /** A completed model turn. */
  record Completion(
      String id,
      String model,
      String finishReason,
      String content,
      List<AgentMessage.ToolCall> toolCalls,
      List<Map<String, Object>> invalidToolCalls,
      Integer promptTokens,
      Integer completionTokens) {}

  private final ChatClient chatClient;
  private final FlagService flags;
  private final String model;
  private final CassetteStore cassettes; // null when USE_VCR is false

  ChatLlm(ChatClient chatClient, FlagService flags, String model, CassetteStore cassettes) {
    this.chatClient = chatClient;
    this.flags = flags;
    this.model = model;
    this.cassettes = cassettes;
  }

  String model() {
    return model;
  }

  Completion complete(List<AgentMessage> messages, List<ToolSpec> tools) {
    int delayMs = flags.intFlag("aiSlowResponse");
    if (delayMs > 0) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while delaying the LLM call", e);
      }
    }
    if (cassettes == null) {
      return live(messages, tools);
    }
    String cassette = CassetteStore.cassetteName(model);
    List<Map<String, Object>> wire = wireMessages(messages);
    Optional<String> recorded = cassettes.replay(cassette, wire);
    if (recorded.isPresent()) {
      Completion c = parseResponse(recorded.get());
      if (c != null) {
        return c;
      }
    }
    Completion c = live(messages, tools);
    cassettes.record(
        cassette,
        PyJson.dumps(requestBody(wire, tools), true, true),
        PyJson.dumps(responseBody(c), true, true));
    return c;
  }

  // ---- live call through Spring AI -------------------------------------------------------------

  private Completion live(List<AgentMessage> messages, List<ToolSpec> tools) {
    List<Message> prompt = new ArrayList<>();
    for (AgentMessage m : messages) {
      switch (m.type()) {
        case "system" -> prompt.add(new SystemMessage(m.text()));
        case "human" -> prompt.add(new UserMessage(m.text()));
        case "ai" -> {
          List<AssistantMessage.ToolCall> calls = new ArrayList<>();
          for (AgentMessage.ToolCall c : m.toolCalls()) {
            calls.add(
                new AssistantMessage.ToolCall(
                    c.id(), "function", c.name(), PyJson.dumps(c.args(), false)));
          }
          prompt.add(AssistantMessage.builder().content(m.text()).toolCalls(calls).build());
        }
        case "tool" ->
            prompt.add(
                ToolResponseMessage.builder()
                    .responses(
                        List.of(
                            new ToolResponseMessage.ToolResponse(
                                m.toolCallId(), m.name() == null ? "" : m.name(), m.text())))
                    .build());
        default -> throw new IllegalArgumentException("Unsupported message type " + m.type());
      }
    }
    List<ToolCallback> callbacks = new ArrayList<>();
    for (ToolSpec t : tools) {
      callbacks.add(new DefinitionOnlyToolCallback(t));
    }
    // In Spring AI 2.x the chat model only advertises the tools and returns the model's tool calls;
    // executing them is the job of the agent loop (LangGraph's tools node in the original), so no
    // tool-calling advisor is installed.
    ChatResponse response =
        chatClient
            .prompt()
            .messages(prompt)
            .options(OpenAiChatOptions.builder().model(model))
            .toolCallbacks(callbacks)
            .call()
            .chatResponse();
    if (response == null || response.getResult() == null) {
      throw new IllegalStateException("The LLM returned no response");
    }
    Generation generation = response.getResult();
    AssistantMessage out = generation.getOutput();
    List<AgentMessage.ToolCall> calls = new ArrayList<>();
    List<Map<String, Object>> invalid = new ArrayList<>();
    for (AssistantMessage.ToolCall c : out.getToolCalls()) {
      addToolCall(calls, invalid, c.id(), c.name(), c.arguments());
    }
    String finish =
        generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason();
    String responseModel = response.getMetadata() == null ? null : response.getMetadata().getModel();
    String id = response.getMetadata() == null ? null : response.getMetadata().getId();
    Integer prompt0 = null;
    Integer completion0 = null;
    if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
      prompt0 = response.getMetadata().getUsage().getPromptTokens();
      completion0 = response.getMetadata().getUsage().getCompletionTokens();
    }
    return new Completion(
        id,
        responseModel == null || responseModel.isEmpty() ? model : responseModel,
        finish == null ? (calls.isEmpty() ? "stop" : "tool_calls") : finish.toLowerCase(),
        out.getText() == null ? "" : out.getText(),
        calls,
        invalid,
        prompt0,
        completion0);
  }

  @SuppressWarnings("unchecked")
  private static void addToolCall(
      List<AgentMessage.ToolCall> calls,
      List<Map<String, Object>> invalid,
      String id,
      String name,
      String arguments) {
    try {
      JsonNode node = PyJson.MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
      if (node.isObject()) {
        calls.add(
            new AgentMessage.ToolCall(id, name, PyJson.MAPPER.convertValue(node, LinkedHashMap.class)));
        return;
      }
    } catch (JacksonException e) {
      // fall through: LangChain records unparsable calls as invalid_tool_calls
    }
    Map<String, Object> inv = new LinkedHashMap<>();
    inv.put("name", name);
    inv.put("args", arguments);
    inv.put("id", id);
    inv.put("error", "Function " + name + " arguments:\n\n" + arguments + "\n\nare not valid JSON.");
    inv.put("type", "invalid_tool_call");
    invalid.add(inv);
  }

  /** Tool definition carried to the model; execution is done by the agent loop. */
  private record DefinitionOnlyToolCallback(ToolSpec spec) implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name(spec.name())
          .description(spec.description())
          .inputSchema(PyJson.toNode(spec.schema()).toString())
          .build();
    }

    @Override
    public String call(String toolInput) {
      throw new UnsupportedOperationException("Tools are executed by the agent loop");
    }
  }

  // ---- OpenAI wire format (what langchain-openai sent; used for VCR matching/recording) -------

  /** Messages in the OpenAI chat-completions format langchain-openai produces. */
  static List<Map<String, Object>> wireMessages(List<AgentMessage> messages) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (AgentMessage m : messages) {
      Map<String, Object> w = new LinkedHashMap<>();
      switch (m.type()) {
        case "system" -> {
          w.put("content", m.content());
          w.put("role", "system");
        }
        case "human" -> {
          w.put("content", m.content());
          w.put("role", "user");
        }
        case "ai" -> {
          String text = m.text();
          w.put("content", text.isEmpty() && !m.toolCalls().isEmpty() ? null : m.content());
          w.put("role", "assistant");
          if (!m.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (AgentMessage.ToolCall c : m.toolCalls()) {
              Map<String, Object> fn = new LinkedHashMap<>();
              fn.put("arguments", PyJson.dumps(c.args(), false));
              fn.put("name", c.name());
              Map<String, Object> call = new LinkedHashMap<>();
              call.put("function", fn);
              call.put("id", c.id());
              call.put("type", "function");
              calls.add(call);
            }
            w.put("tool_calls", calls);
          }
        }
        case "tool" -> {
          w.put("content", m.content());
          w.put("role", "tool");
          w.put("tool_call_id", m.toolCallId());
        }
        default -> throw new IllegalArgumentException("Unsupported message type " + m.type());
      }
      out.add(w);
    }
    return out;
  }

  private Map<String, Object> requestBody(List<Map<String, Object>> wire, List<ToolSpec> tools) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messages", wire);
    body.put("model", model);
    body.put("stream", false);
    if (!tools.isEmpty()) {
      List<Map<String, Object>> defs = new ArrayList<>();
      for (ToolSpec t : tools) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("description", t.description());
        fn.put("name", t.name());
        fn.put("parameters", t.schema());
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("function", fn);
        def.put("type", "function");
        defs.add(def);
      }
      body.put("tools", defs);
    }
    return body;
  }

  /** The cleaned recorded response ({@code clean_response}: fixed id/created, no usage). */
  private Map<String, Object> responseBody(Completion c) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("content", c.content());
    message.put("role", "assistant");
    if (!c.toolCalls().isEmpty()) {
      List<Map<String, Object>> calls = new ArrayList<>();
      int index = 0;
      for (AgentMessage.ToolCall tc : c.toolCalls()) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("arguments", PyJson.dumps(tc.args(), false));
        fn.put("name", tc.name());
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("function", fn);
        call.put("id", tc.id());
        call.put("index", index++);
        call.put("type", "function");
        calls.add(call);
      }
      message.put("tool_calls", calls);
    }
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("finish_reason", c.finishReason());
    choice.put("index", 0);
    choice.put("message", message);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("choices", List.of(choice));
    body.put("created", 0);
    body.put("id", "chatcmpl-fixture");
    body.put("model", c.model());
    body.put("object", "chat.completion");
    return body;
  }

  /** Parses a recorded chat-completion response body. */
  @SuppressWarnings("unchecked")
  static Completion parseResponse(String body) {
    try {
      JsonNode root = PyJson.MAPPER.readTree(body);
      JsonNode choice = root.path("choices").path(0);
      JsonNode message = choice.path("message");
      if (message.isMissingNode()) {
        return null;
      }
      List<AgentMessage.ToolCall> calls = new ArrayList<>();
      List<Map<String, Object>> invalid = new ArrayList<>();
      for (JsonNode tc : message.path("tool_calls")) {
        addToolCall(
            calls,
            invalid,
            tc.path("id").asString(null),
            tc.path("function").path("name").asString(""),
            tc.path("function").path("arguments").asString("{}"));
      }
      return new Completion(
          root.path("id").asString(null),
          root.path("model").asString(null),
          choice.path("finish_reason").asString(calls.isEmpty() ? "stop" : "tool_calls"),
          message.path("content").isNull() ? "" : message.path("content").asString(""),
          calls,
          invalid,
          null,
          null);
    } catch (JacksonException e) {
      log.warn("VCR: unparsable recorded response: {}", e.toString());
      return null;
    }
  }
}
