/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The chat UI (replacement for the Gradio {@code ChatInterface}): {@code GET /} renders the page,
 * {@code POST /api/chat} relays one message plus the conversation history to the agent.
 *
 * <p>All URLs used by the page are relative so it also works under the frontend proxy's {@code
 * /chatbot/} prefix (envoy rewrites the prefix to {@code /}).
 */
@Controller
class ChatbotController {

  static final String TITLE = "Astronomy Shop agent";
  static final String DESCRIPTION =
      "Ask me about the astronomy shop application, I will ask for more information if needed.";
  static final List<String> EXAMPLES =
      List.of(
          "Show all available products in the store.",
          "What currencies are supported by the Astronomy Shop?",
          "What current promotions are available on binoculars?");

  private final ChatService chatService;

  ChatbotController(ChatService chatService) {
    this.chatService = chatService;
  }

  @GetMapping("/")
  String index(Model model) {
    model.addAttribute("title", TITLE);
    model.addAttribute("description", DESCRIPTION);
    model.addAttribute("examples", EXAMPLES);
    return "index";
  }

  /** Request of the chat page. {@code history} is the conversation so far (role/content dicts). */
  record ChatRequest(String message, List<Map<String, Object>> history) {}

  @PostMapping(
      value = "/api/chat",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  Map<String, Object> chat(@RequestBody ChatRequest request) {
    Map<String, Object> response = new LinkedHashMap<>();
    // respond(): a blank message is ignored (nothing is sent to the agent, nothing is counted)
    String message = request.message() == null ? "" : request.message().strip();
    if (message.isEmpty()) {
      response.put("reply", null);
      return response;
    }
    List<Map<String, Object>> history = request.history() == null ? List.of() : request.history();
    response.put("reply", chatService.chatWithAgent(message, history));
    return response;
  }
}
