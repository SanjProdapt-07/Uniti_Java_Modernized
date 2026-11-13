/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HistoryConverterTests {

  @Test
  void convertsRolesLikeLangChain() {
    assertThat(HistoryConverter.convert(Map.of("role", "user", "content", "hi")).type()).isEqualTo("human");
    assertThat(HistoryConverter.convert(Map.of("role", "assistant", "content", "yo")).type()).isEqualTo("ai");
    assertThat(HistoryConverter.convert(Map.of("role", "system", "content", "s")).type()).isEqualTo("system");
    assertThat(HistoryConverter.convert(Map.of("role", "developer", "content", "s")).type()).isEqualTo("system");
    assertThat(HistoryConverter.convert(Map.of("type", "human", "content", "x")).type()).isEqualTo("human");
  }

  @Test
  void keepsExtraKeysFromGradioStyleHistory() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("role", "assistant");
    raw.put("content", "Answer");
    raw.put("metadata", null);
    raw.put("options", null);

    Map<String, Object> json = HistoryConverter.convert(raw).toJson();

    assertThat(json).containsEntry("type", "ai").containsEntry("content", "Answer");
    assertThat(json).containsKeys("metadata", "options", "tool_calls", "additional_kwargs", "response_metadata");
  }

  @Test
  void listContentIsKeptAndTextIsExtracted() {
    List<Map<String, Object>> blocks = List.of(Map.of("type", "text", "text", "hello "), Map.of("type", "text", "text", "world"));
    AgentMessage m = HistoryConverter.convert(Map.of("role", "user", "content", blocks));
    assertThat(m.content()).isEqualTo(blocks);
    assertThat(m.text()).isEqualTo("hello world");
  }

  @Test
  void nullContentBecomesEmptyString() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("role", "user");
    raw.put("content", null);
    assertThat(HistoryConverter.convert(raw).content()).isEqualTo("");
  }

  @Test
  void unknownRoleIsRejectedWithLangChainMessage() {
    assertThatThrownBy(() -> HistoryConverter.convert(Map.of("role", "robot", "content", "x")))
        .hasMessage(
            "Unexpected message type: 'robot'. Use one of 'human', 'user', 'ai', 'assistant',"
                + " 'function', 'tool', 'system', or 'developer'.");
  }

  @Test
  void missingKeysAreRejected() {
    assertThatThrownBy(() -> HistoryConverter.convert(Map.of("content", "x")))
        .hasMessage("Message dict must contain 'role' and 'content' keys, got {'content': 'x'}");
  }
}
