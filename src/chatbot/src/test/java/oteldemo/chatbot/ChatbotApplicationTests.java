/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The chat page and its relay to the agent, against a fake agent service. */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none"
    })
@AutoConfigureMockMvc
class ChatbotApplicationTests {

  private static HttpServer agent;
  private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
  private static volatile Function<String, String> reply = body -> "{\"response\":{\"messages\":[{\"type\":\"human\",\"content\":\"q\"},{\"type\":\"ai\",\"content\":\"Hello from the agent\"}]}}";
  private static volatile int status = 200;

  @Autowired MockMvc mvc;
  @Autowired InMemoryMetricReader metrics;

  @BeforeAll
  static void startAgent() throws IOException {
    agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    agent.createContext(
        "/prompt",
        exchange -> {
          String body;
          try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          REQUESTS.add(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Content-Type") + " " + body);
          byte[] bytes = reply.apply(body).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    agent.start();
  }

  @AfterAll
  static void stopAgent() {
    agent.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("agent.endpoint", () -> "127.0.0.1");
    registry.add("agent.port", () -> agent.getAddress().getPort());
  }

  @BeforeEach
  void reset() {
    REQUESTS.clear();
    status = 200;
    reply = body -> "{\"response\":{\"messages\":[{\"type\":\"human\",\"content\":\"q\"},{\"type\":\"ai\",\"content\":\"Hello from the agent\"}]}}";
  }

  @Test
  void pageShowsTitleDescriptionAndSampleQuestions() throws Exception {
    String html =
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("<title>Astronomy Shop agent</title>")
        .contains("<h1>Astronomy Shop agent</h1>")
        .contains(
            "Ask me about the astronomy shop application, I will ask for more information if needed.")
        .contains("placeholder=\"Type a message...\"")
        .contains("autofocus")
        .contains("Sample questions:")
        .contains("Show all available products in the store.")
        .contains("What currencies are supported by the Astronomy Shop?")
        .contains("What current promotions are available on binoculars?");
    // relative URLs so the page works under the frontend proxy's /chatbot/ prefix
    assertThat(html).contains("href=\"chat.css\"").contains("src=\"chat.js\"");
    assertThat(html).doesNotContain("href=\"/").doesNotContain("src=\"/");
  }

  @Test
  void staticAssetsAreServed() throws Exception {
    mvc.perform(get("/chat.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("api/chat")));
    mvc.perform(get("/chat.css")).andExpect(status().isOk());
  }

  @Test
  void chatRelaysMessageAndHistoryToTheAgentAndReturnsTheLastMessage() throws Exception {
    mvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"message\": \"  List products  \", \"history\": ["
                        + "{\"role\": \"user\", \"content\": \"hi\"},"
                        + "{\"role\": \"assistant\", \"content\": \"hello\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("Hello from the agent"));

    assertThat(REQUESTS).hasSize(1);
    assertThat(REQUESTS.get(0))
        .isEqualTo(
            "POST application/json {\"message\":\"List products\",\"history\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"hello\"}]}");
  }

  @Test
  void blankMessagesAreIgnoredAndNotCounted() throws Exception {
    long before = requestCount();

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"   \", \"history\": []}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value((Object) null));

    assertThat(REQUESTS).isEmpty();
    assertThat(requestCount()).isEqualTo(before);
  }

  @Test
  void everyForwardedMessageIsCountedInDemoChatbotRequests() throws Exception {
    long before = requestCount();
    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"a\"}"));
    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"b\", \"history\": []}"));
    assertThat(requestCount()).isEqualTo(before + 2);
    metrics.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("demo.chatbot.requests"))
        .forEach(
            m -> {
              assertThat(m.getUnit()).isEqualTo("{request}");
              assertThat(m.getDescription())
                  .isEqualTo("Number of chat messages sent from the chatbot UI to the agent");
            });
  }

  @Test
  void agentHttpErrorsAreShownAsErrorText() throws Exception {
    status = 500;
    reply = body -> "{\"detail\":\"boom\"}";

    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"x\"}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.reply")
                .value(
                    org.hamcrest.Matchers.matchesPattern(
                        "Error: 500 Server Error: Internal Server Error for url: http://127\\.0\\.0\\.1:\\d+/prompt")));
  }

  @Test
  void unexpectedAgentResponseShapes() throws Exception {
    reply = body -> "{\"response\":{\"messages\":[]}}";
    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"x\"}"))
        .andExpect(jsonPath("$.reply").value("Error: Received an unexpected response format from the agent."));

    reply = body -> "{}";
    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"x\"}"))
        .andExpect(jsonPath("$.reply").value("Error: Received an unexpected response format from the agent."));

    reply = body -> "{\"response\":{\"messages\":[{\"type\":\"ai\"}]}}";
    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"x\"}"))
        .andExpect(jsonPath("$.reply").value("Agent returned an empty message body."));

    reply = body -> "not json";
    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"x\"}"))
        .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.startsWith("Error: ")));
  }

  @Test
  void blockContentOfTheLastMessageIsFlattenedToText() throws Exception {
    reply = body -> "{\"response\":{\"messages\":[{\"type\":\"ai\",\"content\":[{\"type\":\"text\",\"text\":\"A\"},{\"type\":\"text\",\"text\":\"B\"}]}]}}";
    mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"x\"}"))
        .andExpect(jsonPath("$.reply").value("AB"));
  }

  private long requestCount() {
    return metrics.collectAllMetrics().stream()
        .filter(m -> m.getName().equals("demo.chatbot.requests"))
        .flatMap(m -> m.getLongSumData().getPoints().stream())
        .mapToLong(LongPointData::getValue)
        .sum();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    InMemoryMetricReader inMemoryMetricReader() {
      return InMemoryMetricReader.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(InMemoryMetricReader metricReader) {
      return customizer ->
          customizer.addMeterProviderCustomizer(
              (meterProvider, config) -> meterProvider.registerMetricReader(metricReader));
    }
  }

  @SuppressWarnings("unused")
  private static final AttributeKey<String> UNUSED = AttributeKey.stringKey("unused");
}
