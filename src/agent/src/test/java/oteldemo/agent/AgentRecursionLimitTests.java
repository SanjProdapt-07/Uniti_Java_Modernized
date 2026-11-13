/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static oteldemo.agent.AgentTestSupport.call;
import static oteldemo.agent.AgentTestSupport.toolCalls;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;

/** GRAPH_RECURSION_LIMIT: a model that never stops calling tools ends in HTTP 500. */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "agent.llm.use-vcr=false",
      "agent.recursion-limit=5"
    })
@AutoConfigureMockMvc
@Import(AgentTestSupport.Beans.class)
class AgentRecursionLimitTests {

  private static AgentTestSupport.Frontend frontend;

  @Autowired MockMvc mvc;
  @Autowired ChatModel chatModel;
  @Autowired InMemoryProvider featureFlags;

  @BeforeAll
  static void start() throws Exception {
    frontend = new AgentTestSupport.Frontend();
  }

  @AfterAll
  static void stop() {
    frontend.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    if (frontend == null) {
      frontend = new AgentTestSupport.Frontend();
    }
    registry.add("shop.application-endpoint", () -> frontend.endpoint());
  }

  @Test
  void endlessToolCallsHitTheRecursionLimit() throws Exception {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    AtomicInteger modelCalls = new AtomicInteger();
    when(chatModel.call(any(Prompt.class)))
        .thenAnswer(
            inv -> {
              modelCalls.incrementAndGet();
              return toolCalls("", call("id" + modelCalls.get(), "get_recommendations", "{\"product_id\": \"P1\"}"));
            });

    MvcResult r =
        mvc.perform(
                post("/prompt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\": \"loop\"}"))
            .andReturn();

    assertThat(r.getResponse().getStatus()).isEqualTo(500);
    assertThat(JsonMapper.builder().build().readTree(r.getResponse().getContentAsString()).get("detail").asString())
        .isEqualTo(
            "Recursion limit of 5 reached without hitting a stop condition. You can increase the"
                + " limit by setting the `recursion_limit` config key.\n"
                + "For troubleshooting, visit:"
                + " https://docs.langchain.com/oss/python/langgraph/errors/GRAPH_RECURSION_LIMIT");
    // model, tools, model, tools, model -> the next tools step would be the 6th
    assertThat(modelCalls).hasValue(3);
    assertThat(frontend.requests).hasSize(2);
  }
}
