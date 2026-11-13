/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Spring AI OpenAI chat model against a fake OpenAI-compatible endpoint: checks what goes
 * over the wire (path under LLM_BASE_URL, API key, messages, advertised tools) and how responses
 * are mapped back, with no tool being executed by Spring AI itself.
 */
class OpenAiWireTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HttpServer server;
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final List<String> auth = new CopyOnWriteArrayList<>();
  private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
  private volatile String response;
  private volatile int status = 200;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (InputStream in = exchange.getRequestBody()) {
            bodies.add(JSON.readTree(in.readAllBytes()));
          }
          paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private ChatLlm llm(String apiKey) {
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    OpenAiChatModel model = new AgentConfiguration().openAiChatModel(baseUrl, "wire-model", apiKey, "True");
    FlagService noFlags =
        new FlagService(null) {
          @Override
          int intFlag(String name) {
            return 0;
          }
        };
    return new ChatLlm(AgentConfiguration.toolAdvertisingChatClient(model), noFlags, "wire-model", null);
  }

  private static final List<ChatLlm.ToolSpec> TOOLS =
      List.of(
          new ChatLlm.ToolSpec(
              "get_ads",
              "Fetch promotional ads.",
              Map.of(
                  "type",
                  "object",
                  "properties",
                  Map.of("category", Map.of("type", "string")),
                  "required",
                  List.of("category"))),
          new ChatLlm.ToolSpec("list_products", "List all products.", Map.of("type", "object", "properties", Map.of())));

  private static List<AgentMessage> prompt() {
    return List.of(
        AgentMessage.builder("system").content("You are a helpful assistant. Be concise and accurate.").build(),
        AgentMessage.human("What promotions are there for binoculars?"));
  }

  @Test
  void toolCallResponseIsMappedAndNothingIsExecuted() {
    response =
        "{\"id\":\"chatcmpl-9\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"wire-model-2026\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
            + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
            + "{\"name\":\"get_ads\",\"arguments\":\"{\\\"category\\\": \\\"binoculars\\\"}\"}}]}}],"
            + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}";

    ChatLlm.Completion c = llm("secret-key").complete(prompt(), TOOLS);

    assertThat(paths).containsExactly("POST /v1/chat/completions");
    assertThat(auth).containsExactly("Bearer secret-key");
    JsonNode body = bodies.get(0);
    assertThat(body.get("model").asString()).isEqualTo("wire-model");
    assertThat(body.get("messages")).hasSize(2);
    assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("system");
    assertThat(body.get("messages").get(0).get("content").asString())
        .isEqualTo("You are a helpful assistant. Be concise and accurate.");
    assertThat(body.get("messages").get(1).get("role").asString()).isEqualTo("user");
    assertThat(body.get("tools")).hasSize(2);
    JsonNode fn = body.get("tools").get(0).get("function");
    assertThat(body.get("tools").get(0).get("type").asString()).isEqualTo("function");
    assertThat(fn.get("name").asString()).isEqualTo("get_ads");
    assertThat(fn.get("description").asString()).isEqualTo("Fetch promotional ads.");
    assertThat(fn.get("parameters").get("required").get(0).asString()).isEqualTo("category");

    assertThat(c.finishReason()).isEqualTo("tool_calls");
    assertThat(c.model()).isEqualTo("wire-model-2026");
    assertThat(c.id()).isEqualTo("chatcmpl-9");
    assertThat(c.toolCalls()).hasSize(1);
    assertThat(c.toolCalls().get(0).id()).isEqualTo("call_1");
    assertThat(c.toolCalls().get(0).name()).isEqualTo("get_ads");
    assertThat(c.toolCalls().get(0).args()).isEqualTo(Map.of("category", "binoculars"));
    assertThat(c.promptTokens()).isEqualTo(20);
    assertThat(c.completionTokens()).isEqualTo(5);
    // only one HTTP call: Spring AI did not try to execute the tool and loop
    assertThat(paths).hasSize(1);
  }

  @Test
  void toolMessagesAndAssistantToolCallsAreSentInTheSecondTurn() {
    response =
        "{\"id\":\"c2\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"wire-model\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
            + "\"content\":\"We have a 10% binoculars promotion.\"}}]}";
    List<AgentMessage> messages =
        List.of(
            AgentMessage.builder("system").content("sys").build(),
            AgentMessage.human("promotions?"),
            AgentMessage.builder("ai")
                .content("")
                .toolCalls(List.of(new AgentMessage.ToolCall("call_1", "get_ads", Map.of("category", "binoculars"))))
                .build(),
            AgentMessage.builder("tool")
                .content("[{\"text\": \"10% off\"}]")
                .name("get_ads")
                .toolCallId("call_1")
                .build());

    ChatLlm.Completion c = llm("k").complete(messages, TOOLS);

    JsonNode sent = bodies.get(0).get("messages");
    assertThat(sent).hasSize(4);
    assertThat(sent.get(2).get("role").asString()).isEqualTo("assistant");
    assertThat(sent.get(2).get("tool_calls").get(0).get("id").asString()).isEqualTo("call_1");
    assertThat(sent.get(2).get("tool_calls").get(0).get("function").get("name").asString()).isEqualTo("get_ads");
    assertThat(JSON.readTree(sent.get(2).get("tool_calls").get(0).get("function").get("arguments").asString()).get("category").asString())
        .isEqualTo("binoculars");
    assertThat(sent.get(3).get("role").asString()).isEqualTo("tool");
    assertThat(sent.get(3).get("tool_call_id").asString()).isEqualTo("call_1");
    assertThat(sent.get(3).get("content").asString()).isEqualTo("[{\"text\": \"10% off\"}]");

    assertThat(c.content()).isEqualTo("We have a 10% binoculars promotion.");
    assertThat(c.toolCalls()).isEmpty();
    assertThat(c.finishReason()).isEqualTo("stop");
  }

  @Test
  void missingApiKeyFallsBackToTheDummyKey() {
    response =
        "{\"id\":\"c3\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    llm("").complete(prompt(), List.of());

    assertThat(auth).containsExactly("Bearer sk-dummy");
    assertThat(bodies.get(0).has("tools")).isFalse();
  }

  @Test
  void httpErrorsPropagate() {
    status = 401;
    response = "{\"error\":{\"message\":\"bad key\",\"type\":\"invalid_request_error\"}}";

    assertThatThrownBy(() -> llm("bad").complete(prompt(), TOOLS)).isInstanceOf(RuntimeException.class);
  }
}
