/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ChatAgentUI.chat_with_agent}: forwards a chat message and the conversation history to the
 * Agent service ({@code POST http://AGENT_ENDPOINT:AGENT_PORT/prompt}) and returns the content of
 * the last message of the agent's answer. Failures are returned as {@code "Error: ..."} text, never
 * thrown.
 */
class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final RestClient client;
  private final String agentUrl;
  private final int timeoutSeconds;
  private final LongCounter requestCounter;

  ChatService(
      RestClient client, String agentUrl, int timeoutSeconds, OpenTelemetry openTelemetry) {
    this.client = client;
    this.agentUrl = agentUrl;
    this.timeoutSeconds = timeoutSeconds;
    this.requestCounter =
        openTelemetry
            .getMeter("oteldemo.chatbot")
            .counterBuilder("demo.chatbot.requests")
            .setUnit("{request}")
            .setDescription("Number of chat messages sent from the chatbot UI to the agent")
            .build();
  }

  String chatWithAgent(String message, List<Map<String, Object>> history) {
    requestCounter.add(1);
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("message", message);
      payload.put("history", history);
      log.info("Sending request {} to Agent", pythonRepr(payload));
      String body =
          client
              .post()
              .uri(URI.create(agentUrl))
              .contentType(MediaType.APPLICATION_JSON)
              .body(MAPPER.writeValueAsString(payload))
              .exchange(
                  (req, res) -> {
                    int code = res.getStatusCode().value();
                    if (code >= 400) {
                      HttpStatus hs = HttpStatus.resolve(code);
                      throw new AgentHttpException(
                          code
                              + (code < 500 ? " Client Error: " : " Server Error: ")
                              + (hs == null ? "" : hs.getReasonPhrase())
                              + " for url: "
                              + agentUrl);
                    }
                    return new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                  });

      JsonNode root = MAPPER.readTree(body);
      JsonNode agentData = root.has("response") ? root.get("response") : MAPPER.createObjectNode();
      if (agentData == null || !agentData.isObject()) {
        throw new IllegalStateException(
            "'" + (agentData == null || agentData.isNull() ? "NoneType" : "list") + "' object has no attribute 'get'");
      }
      JsonNode messages = agentData.has("messages") ? agentData.get("messages") : MAPPER.createArrayNode();
      if (messages.isArray() && !messages.isEmpty()) {
        JsonNode last = messages.get(messages.size() - 1);
        if (!last.isObject()) {
          throw new IllegalStateException(
              "'" + (last.isString() ? "str" : "list") + "' object has no attribute 'get'");
        }
        if (!last.has("content")) {
          return "Agent returned an empty message body.";
        }
        return contentText(last.get("content"));
      }
      return "Error: Received an unexpected response format from the agent.";
    } catch (Exception e) {
      log.error("Error : {}", describe(e));
      return "Error: " + describe(e);
    }
  }

  private static String contentText(JsonNode content) {
    if (content.isString()) {
      return content.asString();
    }
    if (content.isNull()) {
      return "None";
    }
    if (content.isArray()) {
      StringBuilder sb = new StringBuilder();
      boolean allText = true;
      for (JsonNode block : content) {
        if (block.isObject() && "text".equals(block.path("type").asString(""))) {
          sb.append(block.path("text").asString(""));
        } else if (block.isString()) {
          sb.append(block.asString());
        } else {
          allText = false;
        }
      }
      if (allText) {
        return sb.toString();
      }
    }
    return content.toString();
  }

  /** Approximates {@code str(exception)} of the requests exceptions the original reported. */
  private String describe(Throwable e) {
    if (e instanceof AgentHttpException) {
      return e.getMessage();
    }
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    URI uri = URI.create(agentUrl);
    String pool = "HTTPConnectionPool(host='" + uri.getHost() + "', port=" + uri.getPort() + "): ";
    if (root instanceof HttpConnectTimeoutException) {
      return pool + "Max retries exceeded with url: " + uri.getPath()
          + " (Caused by ConnectTimeoutError: Connection to " + uri.getHost() + " timed out. (connect timeout="
          + timeoutSeconds + "))";
    }
    if (root instanceof HttpTimeoutException) {
      return pool + "Read timed out. (read timeout=" + timeoutSeconds + ")";
    }
    if (root instanceof ConnectException || root instanceof java.nio.channels.UnresolvedAddressException
        || root instanceof java.net.UnknownHostException) {
      return pool + "Max retries exceeded with url: " + uri.getPath()
          + " (Caused by NewConnectionError: Failed to establish a new connection: "
          + (root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage()) + ")";
    }
    if (root instanceof IOException && !(root instanceof tools.jackson.core.JacksonException)) {
      return "('Connection aborted.', " + root.getClass().getSimpleName() + "('"
          + (root.getMessage() == null ? "" : root.getMessage()) + "'))";
    }
    if (e instanceof tools.jackson.core.JacksonException) {
      return "Expecting value: line 1 column 1 (char 0)";
    }
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  /** Python {@code repr()} of the payload dict (used only for the log line). */
  static String pythonRepr(Object value) {
    if (value == null) {
      return "None";
    }
    if (value instanceof String s) {
      String quote = s.contains("'") && !s.contains("\"") ? "\"" : "'";
      String escaped = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
      if (quote.equals("'")) {
        escaped = escaped.replace("'", "\\'");
      }
      return quote + escaped + quote;
    }
    if (value instanceof Boolean b) {
      return b ? "True" : "False";
    }
    if (value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Map<?, ?> m) {
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) {
          sb.append(", ");
        }
        first = false;
        sb.append(pythonRepr(e.getKey())).append(": ").append(pythonRepr(e.getValue()));
      }
      return sb.append('}').toString();
    }
    if (value instanceof List<?> l) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(pythonRepr(l.get(i)));
      }
      return sb.append(']').toString();
    }
    return value.toString();
  }

  /** HTTP error status from the agent ({@code response.raise_for_status()}). */
  static final class AgentHttpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AgentHttpException(String message) {
      super(message);
    }
  }
}
