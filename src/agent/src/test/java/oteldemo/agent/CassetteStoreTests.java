/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CassetteStoreTests {

  private static final String SYSTEM = "You are a helpful assistant. Be concise and accurate.";
  private static final Path FIXTURES = Path.of("fixtures", "vcr_cassettes");

  private static List<Map<String, Object>> conversation(String prompt) {
    return ChatLlm.wireMessages(
        List.of(
            AgentMessage.builder("system").content(SYSTEM).build(), AgentMessage.human(prompt)));
  }

  @Test
  void cassetteNameReplacesSlashes() {
    assertThat(CassetteStore.cassetteName("azure/gpt-5.5")).isEqualTo("azure_gpt-5.5_cassette.yaml");
    assertThat(CassetteStore.cassetteName("claude-opus-4-7"))
        .isEqualTo("claude-opus-4-7_cassette.yaml");
  }

  @Test
  void replaysTheBundledCassetteForTheSamplePrompts() {
    CassetteStore store = new CassetteStore(FIXTURES, 0.85);

    for (String[] sample :
        new String[][] {
          {"Show all available products in the store.", "list_products"},
          {"What currencies are supported by the Astronomy Shop?", "get_supported_currencies"},
          {"What current promotions are available on binoculars?", "get_ads"}
        }) {
      Optional<String> response =
          store.replay("claude-opus-4-7_cassette.yaml", conversation(sample[0]));
      assertThat(response).as(sample[0]).isPresent();
      ChatLlm.Completion c = ChatLlm.parseResponse(response.get());
      assertThat(c.toolCalls()).hasSize(1);
      assertThat(c.toolCalls().get(0).name()).isEqualTo(sample[1]);
      assertThat(c.finishReason()).isEqualTo("tool_calls");
    }
  }

  @Test
  void replaysTheFinalAnswerAfterTheToolResult() {
    CassetteStore store = new CassetteStore(FIXTURES, 0.85);
    List<AgentMessage> messages =
        List.of(
            AgentMessage.builder("system").content(SYSTEM).build(),
            AgentMessage.human("What currencies are supported by the Astronomy Shop?"),
            AgentMessage.builder("ai")
                .content("")
                .toolCalls(
                    List.of(new AgentMessage.ToolCall("tooluse_x", "get_supported_currencies", Map.of())))
                .build(),
            AgentMessage.builder("tool")
                .content("[\"EUR\", \"USD\"]")
                .name("get_supported_currencies")
                .toolCallId("tooluse_x")
                .build());

    Optional<String> response =
        store.replay("claude-opus-4-7_cassette.yaml", ChatLlm.wireMessages(messages));

    assertThat(response).isPresent();
    ChatLlm.Completion c = ChatLlm.parseResponse(response.get());
    assertThat(c.toolCalls()).isEmpty();
    assertThat(c.finishReason()).isEqualTo("stop");
    assertThat(c.content()).isNotBlank();
  }

  @Test
  void aDifferentPromptDoesNotMatch() {
    CassetteStore store = new CassetteStore(FIXTURES, 0.85);

    assertThat(
            store.replay(
                "claude-opus-4-7_cassette.yaml", conversation("Please tell me a joke about comets.")))
        .isEmpty();
  }

  @Test
  void aDifferentNumberOfMessagesNeverMatches() {
    List<Map<String, Object>> two = conversation("Show all available products in the store.");
    assertThat(CassetteStore.bodySimilarity(two, two)).isEqualTo(1.0);
    assertThat(CassetteStore.bodySimilarity(two, two.subList(0, 1))).isEqualTo(0.0);
  }

  @Test
  void toolResultContentIsIgnoredWhenComparing() {
    Map<String, Object> a = Map.of("role", "tool", "content", "first", "tool_call_id", "1");
    Map<String, Object> b = Map.of("role", "tool", "content", "completely different", "tool_call_id", "1");
    assertThat(CassetteStore.bodySimilarity(List.of(a), List.of(b))).isEqualTo(1.0);
  }

  @Test
  void missingCassetteMeansNoReplay(@TempDir Path dir) {
    assertThat(new CassetteStore(dir, 0.85).replay("nope.yaml", conversation("hi"))).isEmpty();
  }

  @Test
  void recordedEpisodesCanBeReplayed(@TempDir Path dir) throws Exception {
    CassetteStore store = new CassetteStore(dir, 0.85);
    List<Map<String, Object>> request = conversation("Tell me about Saturn rings.");
    String response =
        "{\"choices\": [{\"finish_reason\": \"stop\", \"index\": 0, \"message\": {\"content\":"
            + " \"Rings of ice.\", \"role\": \"assistant\"}}], \"model\": \"m\", \"object\":"
            + " \"chat.completion\"}";

    store.record("m_cassette.yaml", PyJson.dumps(Map.of("messages", request), true, true), response);

    assertThat(Files.readString(dir.resolve("m_cassette.yaml")))
        .startsWith("# Copyright The OpenTelemetry Authors")
        .contains("interactions:")
        .contains("https://vcr.local/");
    // a fresh store (cache cold) replays what was written
    Optional<String> replayed =
        new CassetteStore(dir, 0.85).replay("m_cassette.yaml", conversation("Tell me about Saturn rings."));
    assertThat(replayed).hasValue(response);
    // and recording again keeps the earlier episode
    store.record("m_cassette.yaml", PyJson.dumps(Map.of("messages", conversation("Other?")), true, true), response);
    assertThat(
            new CassetteStore(dir, 0.85)
                .replay("m_cassette.yaml", conversation("Tell me about Saturn rings.")))
        .isPresent();
  }
}
