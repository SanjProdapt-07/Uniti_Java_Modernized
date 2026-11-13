/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import com.sun.net.httpserver.HttpServer;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Shared helpers: fake shop frontend, scripted ChatModel responses and in-memory telemetry. */
final class AgentTestSupport {

  private AgentTestSupport() {}

  /** A fake shop frontend; requests are recorded as {@code METHOD uri [body]}. */
  static final class Frontend {
    final HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    volatile Function<String, String> body = path -> "[{\"id\":\"OLJCESPC7Z\",\"name\":\"Lens\"},{\"id\":\"66VCHSJNUP\",\"name\":\"Scope\"}]";
    volatile Function<String, Integer> status = path -> 200;

    Frontend() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            String in;
            try (InputStream is = exchange.getRequestBody()) {
              in = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            String uri = exchange.getRequestURI().toString();
            requests.add(exchange.getRequestMethod() + " " + uri + (in.isEmpty() ? "" : " " + in));
            byte[] bytes = body.apply(uri).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.apply(uri), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    String endpoint() {
      return "127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }
  }

  static ChatResponse text(String content) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content(content).build(),
                ChatGenerationMetadata.builder().finishReason("STOP").build())),
        ChatResponseMetadata.builder()
            .id("chatcmpl-1")
            .model("test-model-2026")
            .usage(new DefaultUsage(11, 7))
            .build());
  }

  static ChatResponse toolCalls(String content, AssistantMessage.ToolCall... calls) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content(content).toolCalls(List.of(calls)).build(),
                ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build())),
        ChatResponseMetadata.builder().id("chatcmpl-2").model("test-model-2026").build());
  }

  static AssistantMessage.ToolCall call(String id, String name, String argsJson) {
    return new AssistantMessage.ToolCall(id, "function", name, argsJson);
  }

  /** A ChatModel mock whose real default methods stay active (only {@code call(Prompt)} is stubbed). */
  static ChatModel mockChatModel() {
    ChatModel model = Mockito.mock(ChatModel.class, Mockito.CALLS_REAL_METHODS);
    stubOptions(model);
    return model;
  }

  /** Like the real OpenAI model, the chat client starts from tool-calling capable options. */
  static void stubOptions(ChatModel model) {
    Mockito.doReturn(OpenAiChatOptions.builder().model("test-model").build()).when(model).getOptions();
  }

  /** In-memory exporters, flag provider and the mocked chat model for {@code @SpringBootTest}. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Beans {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    InMemoryMetricReader inMemoryMetricReader() {
      return InMemoryMetricReader.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(
        InMemorySpanExporter spanExporter, InMemoryMetricReader metricReader) {
      return customizer ->
          customizer
              .addTracerProviderCustomizer(
                  (tracerProvider, config) ->
                      tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addMeterProviderCustomizer(
                  (meterProvider, config) -> meterProvider.registerMetricReader(metricReader));
    }

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }

    @Bean
    @Primary
    ChatModel testChatModel() {
      return mockChatModel();
    }
  }

  static Map<String, Object> mapOf(Object... kv) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
