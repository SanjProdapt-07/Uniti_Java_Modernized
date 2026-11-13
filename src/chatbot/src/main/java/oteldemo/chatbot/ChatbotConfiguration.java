/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot;

import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ChatbotConfiguration {

  @Bean
  ChatService chatService(
      RestClient.Builder builder,
      OpenTelemetry openTelemetry,
      @Value("${agent.endpoint:0.0.0.0}") String agentEndpoint,
      @Value("${agent.port:8010}") String agentPort,
      @Value("${agent.chat-interface-timeout:300}") int timeoutSeconds) {
    Duration timeout = Duration.ofSeconds(timeoutSeconds);
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(timeout);
    RestClient client = builder.requestFactory(factory).build();
    String agentUrl = "http://" + agentEndpoint + ":" + agentPort + "/prompt";
    return new ChatService(client, agentUrl, timeoutSeconds, openTelemetry);
  }
}
