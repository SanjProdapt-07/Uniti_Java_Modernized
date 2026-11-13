/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static oteldemo.agent.AgentTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
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

/** USE_VCR=True: the bundled cassettes answer the sample prompts without any LLM. */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "agent.llm.use-vcr=True",
      "agent.llm.model=claude-opus-4-7"
    })
@AutoConfigureMockMvc
@Import(AgentTestSupport.Beans.class)
class AgentVcrTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static AgentTestSupport.Frontend frontend;
  private static Path scratchCassettes;

  @Autowired MockMvc mvc;
  @Autowired ChatModel chatModel;
  @Autowired InMemoryProvider featureFlags;

  @BeforeAll
  static void start() throws Exception {
    frontend = new AgentTestSupport.Frontend();
    scratchCassettes = Files.createTempDirectory("vcr");
  }

  @AfterAll
  static void stop() throws IOException {
    frontend.stop();
    try (var files = Files.walk(scratchCassettes)) {
      files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    if (frontend == null) {
      frontend = new AgentTestSupport.Frontend();
    }
    if (scratchCassettes == null) {
      scratchCassettes = Files.createTempDirectory("vcr");
    }
    registry.add("shop.application-endpoint", () -> frontend.endpoint());
    // read-write copy of the bundled cassette, so recording never touches the checked in fixture
    registry.add("agent.llm.cassette-dir", () -> prepareCopy().toString());
  }

  private static synchronized Path prepareCopy() {
    try {
      Files.copy(
          Path.of("fixtures", "vcr_cassettes", "claude-opus-4-7_cassette.yaml"),
          scratchCassettes.resolve("claude-opus-4-7_cassette.yaml"),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return scratchCassettes;
  }

  @BeforeEach
  void setUp() {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    featureFlags.updateFlags(Map.of("aiRunawayAgent", Flag.<Integer>builder().variant("v", 0).defaultVariant("v").build()));
    Mockito.reset(chatModel);
    AgentTestSupport.stubOptions(chatModel);
    frontend.requests.clear();
    frontend.body = p -> p.startsWith("/api/currency") ? "[\"EUR\", \"USD\"]" : "[{\"id\": \"OLJCESPC7Z\"}]";
  }

  private JsonNode ask(String message) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/prompt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\": " + JSON.writeValueAsString(message) + "}"))
            .andReturn();
    assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    return JSON.readTree(r.getResponse().getContentAsString()).get("response").get("messages");
  }

  @Test
  void samplePromptsAreAnsweredFromTheCassetteWithoutCallingTheModel() throws Exception {
    JsonNode products = ask("Show all available products in the store.");
    assertThat(products).hasSize(4);
    assertThat(products.get(1).get("tool_calls").get(0).get("name").asString()).isEqualTo("list_products");
    assertThat(products.get(1).get("tool_calls").get(0).get("id").asString()).startsWith("tooluse_");
    assertThat(products.get(3).get("content").asString()).isNotBlank();

    JsonNode currencies = ask("What currencies are supported by the Astronomy Shop?");
    assertThat(currencies.get(1).get("tool_calls").get(0).get("name").asString())
        .isEqualTo("get_supported_currencies");

    JsonNode ads = ask("What current promotions are available on binoculars?");
    assertThat(ads.get(1).get("tool_calls").get(0).get("name").asString()).isEqualTo("get_ads");
    assertThat(ads.get(1).get("tool_calls").get(0).get("args").get("category").asString())
        .isEqualTo("binoculars");

    verify(chatModel, never()).call(any(Prompt.class));
    assertThat(frontend.requests)
        .containsExactly(
            "GET /api/products", "GET /api/currency", "GET /api/data?contextKeys=binoculars");
  }

  @Test
  void runawayAgentLoopReplaysAndEndsInTheRecursionLimit() throws Exception {
    featureFlags.updateFlags(
        Map.of("aiRunawayAgent", Flag.<Integer>builder().variant("on", 20).defaultVariant("on").build()));

    MvcResult r =
        mvc.perform(
                post("/prompt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\": \"Show all available products in the store.\"}"))
            .andReturn();

    // the recorded loop has 13 model turns (25 graph steps); the 13th still asks for a tool
    assertThat(r.getResponse().getStatus()).isEqualTo(500);
    assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("detail").asString())
        .startsWith("Recursion limit of 25 reached without hitting a stop condition.");
    verify(chatModel, never()).call(any(Prompt.class));
    assertThat(frontend.requests).hasSize(12);
  }

  @Test
  void unknownPromptFallsThroughToTheModelAndIsRecorded() throws Exception {
    when(chatModel.call(any(Prompt.class))).thenReturn(text("Saturn's rings are made of ice."));
    Path cassette = scratchCassettes.resolve("claude-opus-4-7_cassette.yaml");
    long before = Files.size(cassette);

    JsonNode first = ask("Why does Saturn have rings?");
    assertThat(first.get(1).get("content").asString()).isEqualTo("Saturn's rings are made of ice.");
    verify(chatModel, Mockito.times(1)).call(any(Prompt.class));
    assertThat(Files.size(cassette)).isGreaterThan(before);

    // the second time the recorded episode is replayed
    JsonNode second = ask("Why does Saturn have rings?");
    assertThat(second.get(1).get("content").asString()).isEqualTo("Saturn's rings are made of ice.");
    verify(chatModel, Mockito.times(1)).call(any(Prompt.class));
  }
}
