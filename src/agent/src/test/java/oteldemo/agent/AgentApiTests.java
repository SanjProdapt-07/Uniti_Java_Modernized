/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static oteldemo.agent.AgentTestSupport.call;
import static oteldemo.agent.AgentTestSupport.text;
import static oteldemo.agent.AgentTestSupport.toolCalls;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "agent.llm.use-vcr=false",
      "agent.llm.model=test-model"
    })
@AutoConfigureMockMvc
@Import(AgentTestSupport.Beans.class)
class AgentApiTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static AgentTestSupport.Frontend frontend;

  @Autowired MockMvc mvc;
  @Autowired ChatModel chatModel;
  @Autowired InMemorySpanExporter spans;
  @Autowired InMemoryMetricReader metrics;
  @Autowired InMemoryProvider featureFlags;

  private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void startFrontend() throws Exception {
    frontend = new AgentTestSupport.Frontend();
  }

  @AfterAll
  static void stopFrontend() {
    frontend.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    if (frontend == null) {
      frontend = new AgentTestSupport.Frontend();
    }
    registry.add("shop.application-endpoint", () -> frontend.endpoint());
  }

  @BeforeEach
  void setUp() {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setFlags(0, 0);
    spans.reset();
    frontend.requests.clear();
    frontend.body = p -> "[{\"id\":\"OLJCESPC7Z\",\"name\":\"Lens\"},{\"id\":\"66VCHSJNUP\",\"name\":\"Scope\"}]";
    frontend.status = p -> 200;
    prompts.clear();
  }

  private void setFlags(int runaway, int slowMs) {
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put("aiRunawayAgent", Flag.<Integer>builder().variant("v", runaway).defaultVariant("v").build());
    flags.put("aiSlowResponse", Flag.<Integer>builder().variant("v", slowMs).defaultVariant("v").build());
    featureFlags.updateFlags(flags);
  }

  /** Scripts the model: each call pops the next response and records the prompt. */
  @SafeVarargs
  private void script(Function<Prompt, ChatResponse>... responses) {
    int[] index = {0};
    when(chatModel.call(any(Prompt.class)))
        .thenAnswer(
            inv -> {
              Prompt prompt = inv.getArgument(0);
              prompts.add(prompt);
              return responses[Math.min(index[0]++, responses.length - 1)].apply(prompt);
            });
  }

  private MvcResult prompt(String json) throws Exception {
    return mvc.perform(post("/prompt").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
  }

  private JsonNode ok(String json) throws Exception {
    MvcResult r = prompt(json);
    assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    return JSON.readTree(r.getResponse().getContentAsString());
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void toolCallingFlowReturnsTheLangGraphStyleResponse() throws Exception {
    script(
        p -> toolCalls("", call("call_1", "list_products", "{}")),
        p -> text("There are 2 products."));

    JsonNode body = ok("{\"message\": \"Show all available products in the store.\", \"history\": []}");

    JsonNode messages = body.get("response").get("messages");
    assertThat(messages).hasSize(4);
    assertThat(messages.get(0).get("type").asString()).isEqualTo("human");
    assertThat(messages.get(0).get("content").asString())
        .isEqualTo("Show all available products in the store.");

    JsonNode ai = messages.get(1);
    assertThat(ai.get("type").asString()).isEqualTo("ai");
    assertThat(ai.get("content").asString()).isEmpty();
    assertThat(ai.get("tool_calls")).hasSize(1);
    assertThat(ai.get("tool_calls").get(0).get("name").asString()).isEqualTo("list_products");
    assertThat(ai.get("tool_calls").get(0).get("args").isObject()).isTrue();
    assertThat(ai.get("tool_calls").get(0).get("id").asString()).isEqualTo("call_1");
    assertThat(ai.get("tool_calls").get(0).get("type").asString()).isEqualTo("tool_call");
    assertThat(ai.get("invalid_tool_calls")).isEmpty();

    JsonNode tool = messages.get(2);
    assertThat(tool.get("type").asString()).isEqualTo("tool");
    assertThat(tool.get("name").asString()).isEqualTo("list_products");
    assertThat(tool.get("tool_call_id").asString()).isEqualTo("call_1");
    assertThat(tool.get("status").asString()).isEqualTo("success");
    // langchain stringifies tool output with json.dumps(ensure_ascii=False): ", " and ": " separators
    assertThat(tool.get("content").asString())
        .isEqualTo("[{\"id\": \"OLJCESPC7Z\", \"name\": \"Lens\"}, {\"id\": \"66VCHSJNUP\", \"name\": \"Scope\"}]");

    assertThat(messages.get(3).get("type").asString()).isEqualTo("ai");
    assertThat(messages.get(3).get("content").asString()).isEqualTo("There are 2 products.");
    assertThat(frontend.requests).containsExactly("GET /api/products");
  }

  @Test
  void systemPromptToolsAndMessagesAreSentToTheModel() throws Exception {
    script(
        p -> toolCalls("", call("c1", "get_ads", "{\"category\": \"binoculars\"}")),
        p -> text("done"));

    ok("{\"message\": \"What current promotions are available on binoculars?\"}");

    assertThat(prompts).hasSize(2);
    List<Message> first = prompts.get(0).getInstructions();
    assertThat(first).hasSize(2);
    assertThat(first.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(first.get(0).getText()).isEqualTo("You are a helpful assistant. Be concise and accurate.");
    assertThat(first.get(1)).isInstanceOf(UserMessage.class);
    assertThat(first.get(1).getText()).isEqualTo("What current promotions are available on binoculars?");

    ToolCallingChatOptions options = (ToolCallingChatOptions) prompts.get(0).getOptions();
    assertThat(options.getToolCallbacks().stream().map(t -> t.getToolDefinition().name()))
        .containsExactly(
            "add_to_cart",
            "checkout",
            "empty_cart",
            "get_ads",
            "get_cart",
            "get_product",
            "get_recommendations",
            "get_shipping_quote",
            "get_supported_currencies",
            "list_products");
    assertThat(options.getToolCallbacks().get(3).getToolDefinition().description())
        .isEqualTo(
            "Fetch promotional ads for Astronomy Shop homepage.\n    Eg : category: `telescopes` or `travel`");
    JsonNode schema =
        JSON.readTree(options.getToolCallbacks().get(0).getToolDefinition().inputSchema());
    assertThat(schema.get("required")).hasSize(2);
    assertThat(schema.get("properties").get("quantity").get("default").asInt()).isEqualTo(1);
    assertThat(schema.has("additionalProperties")).isFalse();

    List<Message> second = prompts.get(1).getInstructions();
    assertThat(second).hasSize(4);
    assertThat(second.get(2)).isInstanceOf(AssistantMessage.class);
    AssistantMessage assistant = (AssistantMessage) second.get(2);
    assertThat(assistant.getToolCalls()).hasSize(1);
    assertThat(assistant.getToolCalls().get(0).name()).isEqualTo("get_ads");
    assertThat(assistant.getToolCalls().get(0).arguments()).isEqualTo("{\"category\": \"binoculars\"}");
    assertThat(second.get(3)).isInstanceOf(ToolResponseMessage.class);
    assertThat(frontend.requests).containsExactly("GET /api/data?contextKeys=binoculars");
  }

  @Test
  void historyIsPrependedAndKeptInTheResult() throws Exception {
    script(p -> text("Sure."));

    JsonNode body =
        ok(
            "{\"message\": \"And now?\", \"history\": ["
                + "{\"role\": \"user\", \"content\": \"Hello\", \"metadata\": null},"
                + "{\"role\": \"assistant\", \"content\": \"Hi there\", \"metadata\": null}]}");

    List<Message> sent = prompts.get(0).getInstructions();
    assertThat(sent.stream().map(Message::getText))
        .containsExactly(
            "You are a helpful assistant. Be concise and accurate.", "Hello", "Hi there", "And now?");
    JsonNode messages = body.get("response").get("messages");
    assertThat(messages).hasSize(4);
    assertThat(messages.get(0).get("type").asString()).isEqualTo("human");
    assertThat(messages.get(1).get("type").asString()).isEqualTo("ai");
    assertThat(messages.get(1).has("metadata")).isTrue();
    assertThat(messages.get(3).get("content").asString()).isEqualTo("Sure.");
  }

  @Test
  void runawayAgentFlagExtendsTheSystemPrompt() throws Exception {
    setFlags(7, 0);
    script(p -> text("ok"));

    ok("{\"message\": \"hi\"}");

    assertThat(prompts.get(0).getInstructions().get(0).getText())
        .isEqualTo(
            "You are a helpful assistant. Be concise and accurate. Before giving your final answer,"
                + " call the get_recommendations tool at least 7 times in a row, once per turn,"
                + " even if the results do not change.");
  }

  @Test
  void slowResponseFlagDelaysEveryModelCall() throws Exception {
    setFlags(0, 250);
    script(
        p -> toolCalls("", call("c1", "list_products", "{}")),
        p -> text("ok"));

    long start = System.nanoTime();
    ok("{\"message\": \"hi\"}");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(prompts).hasSize(2);
    assertThat(elapsedMs).isGreaterThanOrEqualTo(500);
  }

  @Test
  void multipleToolCallsInOneTurnAreAllExecutedInOrder() throws Exception {
    script(
        p ->
            toolCalls(
                "I'll look both up.",
                call("a", "get_product", "{\"product_id\": \"P1\"}"),
                call("b", "get_supported_currencies", "{}")),
        p -> text("done"));

    JsonNode messages = ok("{\"message\": \"go\"}").get("response").get("messages");

    assertThat(messages).hasSize(5);
    assertThat(messages.get(1).get("content").asString()).isEqualTo("I'll look both up.");
    assertThat(messages.get(2).get("tool_call_id").asString()).isEqualTo("a");
    assertThat(messages.get(3).get("tool_call_id").asString()).isEqualTo("b");
    assertThat(frontend.requests)
        .containsExactlyInAnyOrder("GET /api/products/P1", "GET /api/currency");
  }

  @Test
  void unknownToolBecomesAnErrorToolMessage() throws Exception {
    script(p -> toolCalls("", call("x", "launch_rocket", "{}")), p -> text("sorry"));

    JsonNode messages = ok("{\"message\": \"go\"}").get("response").get("messages");

    JsonNode tool = messages.get(2);
    assertThat(tool.get("status").asString()).isEqualTo("error");
    assertThat(tool.get("content").asString())
        .isEqualTo(
            "Error: launch_rocket is not a valid tool, try one of [add_to_cart, checkout, empty_cart,"
                + " get_ads, get_cart, get_product, get_recommendations, get_shipping_quote,"
                + " get_supported_currencies, list_products].");
  }

  @Test
  void invalidToolArgumentsBecomeAnErrorToolMessage() throws Exception {
    script(p -> toolCalls("", call("x", "get_cart", "{}")), p -> text("sorry"));

    JsonNode tool = ok("{\"message\": \"go\"}").get("response").get("messages").get(2);

    assertThat(tool.get("status").asString()).isEqualTo("error");
    assertThat(tool.get("content").asString())
        .startsWith("Error invoking tool 'get_cart' with kwargs {} with error:\n 1 validation error for call[get_cart]")
        .endsWith("\n Please fix the error and try again.");
    assertThat(frontend.requests).isEmpty();
  }

  @Test
  void toolsReturnErrorStringsInsteadOfFailingTheRequest() throws Exception {
    frontend.status = p -> 500;
    script(p -> toolCalls("", call("x", "list_products", "{}")), p -> text("shop is down"));

    JsonNode messages = ok("{\"message\": \"go\"}").get("response").get("messages");

    assertThat(messages.get(2).get("content").asString())
        .startsWith("Error while fetching product list: Server error '500 Internal Server Error'");
    assertThat(messages.get(3).get("content").asString()).isEqualTo("shop is down");
  }

  @Test
  void toolExceptionFailsTheRequestWithDetail() throws Exception {
    script(
        p ->
            toolCalls(
                "",
                call(
                    "x",
                    "get_shipping_quote",
                    "{\"items\": 5, \"currency_code\": \"USD\", \"address\": {}}")));

    MvcResult r = prompt("{\"message\": \"ship\"}");

    assertThat(r.getResponse().getStatus()).isEqualTo(500);
    assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("detail").asString())
        .isEqualTo("'int' object is not iterable");
  }

  @Test
  void llmFailureIsReportedAsHttp500WithDetail() throws Exception {
    when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("LLM unreachable"));

    MvcResult r = prompt("{\"message\": \"hi\"}");

    assertThat(r.getResponse().getStatus()).isEqualTo(500);
    assertThat(r.getResponse().getContentType()).startsWith("application/json");
    assertThat(r.getResponse().getContentAsString()).isEqualTo("{\"detail\":\"LLM unreachable\"}");
  }

  @Test
  void invalidHistoryRoleIsHttp500() throws Exception {
    MvcResult r =
        prompt("{\"message\": \"hi\", \"history\": [{\"role\": \"robot\", \"content\": \"x\"}]}");

    assertThat(r.getResponse().getStatus()).isEqualTo(500);
    assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("detail").asString())
        .startsWith("Unexpected message type: 'robot'");
  }

  @Test
  void requestValidationFollowsFastApiErrorShape() throws Exception {
    MvcResult missing = prompt("{}");
    assertThat(missing.getResponse().getStatus()).isEqualTo(422);
    JsonNode detail = JSON.readTree(missing.getResponse().getContentAsString()).get("detail");
    assertThat(detail.get(0).get("type").asString()).isEqualTo("missing");
    assertThat(detail.get(0).get("loc").get(0).asString()).isEqualTo("body");
    assertThat(detail.get(0).get("loc").get(1).asString()).isEqualTo("message");
    assertThat(detail.get(0).get("msg").asString()).isEqualTo("Field required");

    MvcResult notString = prompt("{\"message\": 5}");
    assertThat(notString.getResponse().getStatus()).isEqualTo(422);
    assertThat(JSON.readTree(notString.getResponse().getContentAsString()).get("detail").get(0).get("type").asString())
        .isEqualTo("string_type");

    MvcResult badHistory = prompt("{\"message\": \"x\", \"history\": [\"a\"]}");
    assertThat(badHistory.getResponse().getStatus()).isEqualTo(422);
    assertThat(JSON.readTree(badHistory.getResponse().getContentAsString()).get("detail").get(0).get("type").asString())
        .isEqualTo("dict_type");

    assertThat(prompt("not json").getResponse().getStatus()).isEqualTo(422);
    assertThat(prompt("[1]").getResponse().getStatus()).isEqualTo(422);
    mvc.perform(post("/prompt")).andExpect(status().isUnprocessableEntity());
    // null history is accepted
    script(p -> text("ok"));
    assertThat(prompt("{\"message\": \"x\", \"history\": null}").getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void emitsTraceloopAndGenAiSpans() throws Exception {
    script(
        p -> toolCalls("", call("call_1", "list_products", "{}")),
        p -> text("There are 2 products."));

    ok("{\"message\": \"Show all available products in the store.\"}");

    List<SpanData> finished = spans.getFinishedSpanItems();
    SpanData workflow = span(finished, "astronomy_shop_agent_workflow.workflow");
    assertThat(attr(workflow, "traceloop.span.kind")).isEqualTo("workflow");
    assertThat(attr(workflow, "traceloop.workflow.name")).isEqualTo("astronomy_shop_agent_workflow");
    assertThat(attr(workflow, "traceloop.entity.name")).isEqualTo("astronomy_shop_agent_workflow");
    assertThat(attr(workflow, "traceloop.entity.input"))
        .isEqualTo("{\"args\": [\"Show all available products in the store.\"], \"kwargs\": {}}");
    assertThat(attr(workflow, "traceloop.entity.output")).startsWith("{\"response\": {\"messages\": [");

    SpanData create = span(finished, "create_agent agent");
    assertThat(attr(create, "gen_ai.operation.name")).isEqualTo("create_agent");
    assertThat(attr(create, "gen_ai.system_instructions"))
        .isEqualTo("You are a helpful assistant. Be concise and accurate.");
    SpanData graph = span(finished, "invoke_agent LangGraph");
    assertThat(attr(graph, "gen_ai.operation.name")).isEqualTo("invoke_agent");
    assertThat(attr(graph, "gen_ai.provider.name")).isEqualTo("langgraph");
    assertThat(graph.getParentSpanId()).isEqualTo(workflow.getSpanId());

    SpanData chain = span(finished, "LangGraph.workflow");
    assertThat(chain.getParentSpanId()).isEqualTo(graph.getSpanId());
    List<SpanData> modelTasks = all(finished, "execute_task model");
    assertThat(modelTasks).hasSize(2);
    assertThat(modelTasks.get(0).getParentSpanId()).isEqualTo(chain.getSpanId());
    assertThat(attr(modelTasks.get(0), "traceloop.span.kind")).isEqualTo("task");

    List<SpanData> llm = all(finished, "ChatLLM.chat");
    assertThat(llm).hasSize(2);
    assertThat(llm.get(0).getKind()).isEqualTo(SpanKind.CLIENT);
    assertThat(attr(llm.get(0), "gen_ai.operation.name")).isEqualTo("chat");
    assertThat(attr(llm.get(0), "gen_ai.request.model")).isEqualTo("test-model");
    assertThat(attr(llm.get(1), "gen_ai.response.model")).isEqualTo("test-model-2026");
    assertThat(attr(llm.get(1), "gen_ai.input.messages")).contains("tool_call_response");
    assertThat(attr(llm.get(1), "gen_ai.output.messages"))
        .isEqualTo(
            "[{\"role\": \"assistant\", \"parts\": [{\"type\": \"text\", \"content\": \"There are 2"
                + " products.\"}], \"finish_reason\": \"stop\"}]");
    assertThat(llm.get(1).getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
        .isEqualTo(11L);
    assertThat(llm.get(1).getParentSpanId()).isEqualTo(modelTasks.get(1).getSpanId());

    SpanData toolTask = span(finished, "execute_task tools");
    SpanData tool = span(finished, "execute_tool list_products");
    assertThat(tool.getParentSpanId()).isEqualTo(toolTask.getSpanId());
    assertThat(attr(tool, "gen_ai.tool.name")).isEqualTo("list_products");
    assertThat(attr(tool, "gen_ai.operation.name")).isEqualTo("execute_tool");
    assertThat(attr(tool, "traceloop.span.kind")).isEqualTo("tool");
    assertThat(attr(tool, "gen_ai.tool.description")).isEqualTo("List all products available in the Astronomy Shop.");

    // the outbound call to the shop is a client span inside the tool span
    assertThat(finished)
        .anySatisfy(
            s -> {
              assertThat(s.getKind()).isEqualTo(SpanKind.CLIENT);
              assertThat(s.getParentSpanId()).isEqualTo(tool.getSpanId());
            });
    // everything belongs to one trace
    assertThat(workflow.getTraceId()).isEqualTo(tool.getTraceId());

    List<String> metricNames = new ArrayList<>();
    metrics.collectAllMetrics().forEach(m -> metricNames.add(m.getName()));
    assertThat(metricNames).contains("demo.tool.calls", "gen_ai.client.token.usage", "gen_ai.client.operation.duration");
    metrics.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("demo.tool.calls"))
        .forEach(
            m ->
                assertThat(m.getLongSumData().getPoints())
                    .anySatisfy(
                        (LongPointData p) ->
                            assertThat(p.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
                                .isEqualTo("list_products")));
  }

  private static SpanData span(List<SpanData> spans, String name) {
    return all(spans, name).stream()
        .findFirst()
        .orElseThrow(() -> new AssertionError("no span named " + name + " in " + spans.stream().map(SpanData::getName).toList()));
  }

  private static List<SpanData> all(List<SpanData> spans, String name) {
    return spans.stream().filter(s -> s.getName().equals(name)).toList();
  }

  private static String attr(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }
}
