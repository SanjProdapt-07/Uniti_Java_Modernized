/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class AgentConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AgentConfiguration.class);

  /** httpx.Timeout(10.0): connect/read/write/pool all 10 seconds. */
  private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(10);

  @Bean
  RestClient shopRestClient(RestClient.Builder builder) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(TOOL_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(TOOL_TIMEOUT);
    return builder.requestFactory(factory).build();
  }

  @Bean
  ShopTools shopTools(
      RestClient shopRestClient,
      @Value("${shop.application-endpoint}") String applicationEndpoint,
      OpenTelemetry openTelemetry) {
    return new ShopTools(shopRestClient, applicationEndpoint, openTelemetry);
  }

  /**
   * MCP tools when {@code MCP_ENABLED} is exactly {@code True} (connected at startup, like the
   * FastAPI lifespan hook; a failed connection aborts startup), otherwise the built-in tools.
   */
  @Bean
  AgentTool.Source toolSource(
      ShopTools shopTools,
      @Value("${agent.mcp.enabled:False}") String mcpEnabled,
      @Value("${agent.mcp.endpoint:0.0.0.0}") String mcpEndpoint,
      @Value("${agent.mcp.port:8011}") String mcpPort) {
    if (!"True".equals(mcpEnabled)) {
      return new BuiltinToolSource(shopTools);
    }
    log.info("MCP tools enabled");
    String url = "http://" + mcpEndpoint + ":" + mcpPort;
    McpSyncClient client =
        McpClient.sync(HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp").build())
            .requestTimeout(Duration.ofSeconds(300))
            .build();
    client.initialize();
    return new McpToolSource(client);
  }

  /** The OpenAI-compatible chat model (LLM_BASE_URL / LLM_MODEL / API_KEY / LLM_TLS_VERIFY). */
  @Bean
  OpenAiChatModel openAiChatModel(
      @Value("${agent.llm.base-url:}") String baseUrl,
      @Value("${agent.llm.model:default}") String model,
      @Value("${agent.llm.api-key:}") String apiKey,
      @Value("${agent.llm.tls-verify:True}") String tlsVerify) {
    OpenAiChatOptions.Builder options =
        OpenAiChatOptions.builder()
            .model(model)
            .apiKey(apiKey == null || apiKey.isEmpty() ? "sk-dummy" : apiKey);
    if (baseUrl != null && !baseUrl.isEmpty()) {
      options.baseUrl(baseUrl);
    }
    OpenAiChatModel.Builder builder = OpenAiChatModel.builder().options(options.build());
    if (!"true".equalsIgnoreCase(tlsVerify)) {
      builder.httpClientBuilderCustomizer(
          http -> {
            try {
              X509TrustManager trustAll =
                  new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                      return new X509Certificate[0];
                    }
                  };
              SSLContext ctx = SSLContext.getInstance("TLS");
              ctx.init(null, new TrustManager[] {trustAll}, new SecureRandom());
              http.sslSocketFactory(ctx.getSocketFactory())
                  .trustManager(trustAll)
                  .hostnameVerifier((hostname, session) -> true);
            } catch (GeneralSecurityException e) {
              throw new IllegalStateException("Cannot disable TLS verification", e);
            }
          });
    }
    return builder.build();
  }

  @Bean
  ChatLlm chatLlm(
      ChatModel chatModel,
      FlagService flags,
      @Value("${agent.llm.model:default}") String model,
      @Value("${agent.llm.use-vcr:True}") String useVcr,
      @Value("${agent.llm.vcr-match-threshold:0.85}") double threshold,
      @Value("${agent.llm.cassette-dir:fixtures/vcr_cassettes}") String cassetteDir) {
    CassetteStore cassettes =
        "true".equalsIgnoreCase(useVcr) ? new CassetteStore(Path.of(cassetteDir), threshold) : null;
    return new ChatLlm(toolAdvertisingChatClient(chatModel), flags, model, cassettes);
  }

  /**
   * Spring AI 2.x runs tool calls in a {@link ToolCallingAdvisor}; the agent loop does that itself
   * (LangGraph's tools node in the original), so the advisor is told that no response is ever
   * eligible for tool execution: the client only advertises the tools and returns the model's
   * tool calls.
   */
  static ChatClient toolAdvertisingChatClient(ChatModel chatModel) {
    ToolExecutionEligibilityChecker never = response -> false;
    return ChatClient.builder(
            chatModel,
            ObservationRegistry.NOOP,
            null,
            null,
            ToolCallingAdvisor.builder().toolExecutionEligibilityChecker(never))
        .build();
  }

  @Bean
  AgentRunner agentRunner(
      OpenTelemetry openTelemetry,
      ChatLlm chatLlm,
      AgentTool.Source toolSource,
      FlagService flags,
      @Value("${agent.recursion-limit:25}") int recursionLimit,
      @Value("${traceloop.trace-content:true}") boolean traceContent) {
    return new AgentRunner(openTelemetry, chatLlm, toolSource, flags, recursionLimit, traceContent);
  }
}
