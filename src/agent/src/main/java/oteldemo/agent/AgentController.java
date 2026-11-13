/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /prompt}: request {@code {"message": str, "history": [dict] | null}}, response
 * {@code {"response": {"messages": [...]}}}. Request validation errors use FastAPI's 422 body shape,
 * agent errors use HTTP 500 with {@code {"detail": "<message>"}}.
 */
@RestController
class AgentController {

  private static final Logger log = LoggerFactory.getLogger(AgentController.class);

  private final AgentRunner runner;

  AgentController(AgentRunner runner) {
    this.runner = runner;
  }

  @PostMapping(value = "/prompt", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> prompt(@RequestBody(required = false) byte[] rawBody) {
    JsonNode body;
    if (rawBody == null || rawBody.length == 0) {
      return validation(List.of(error("missing", List.of("body"), "Field required", null)));
    }
    try {
      body = PyJson.MAPPER.readTree(rawBody);
    } catch (JacksonException e) {
      Map<String, Object> err =
          error("json_invalid", List.of("body", 0), "JSON decode error", Map.of());
      err.put("ctx", Map.of("error", "Expecting value"));
      return validation(List.of(err));
    }
    if (body == null || !body.isObject()) {
      return validation(
          List.of(
              error(
                  "model_attributes_type",
                  List.of("body"),
                  "Input should be a valid dictionary or object to extract fields from",
                  body)));
    }

    List<Map<String, Object>> errors = new ArrayList<>();
    JsonNode message = body.get("message");
    if (message == null) {
      errors.add(error("missing", List.of("body", "message"), "Field required", body));
    } else if (!message.isString()) {
      errors.add(
          error("string_type", List.of("body", "message"), "Input should be a valid string", message));
    }
    List<Map<String, Object>> history = null;
    JsonNode h = body.get("history");
    if (h != null && !h.isNull()) {
      if (!h.isArray()) {
        errors.add(error("list_type", List.of("body", "history"), "Input should be a valid list", h));
      } else {
        history = new ArrayList<>();
        int i = 0;
        for (JsonNode item : h) {
          if (!item.isObject()) {
            errors.add(
                error(
                    "dict_type",
                    List.of("body", "history", i),
                    "Input should be a valid dictionary",
                    item));
          } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = PyJson.MAPPER.convertValue(item, LinkedHashMap.class);
            history.add(m);
          }
          i++;
        }
      }
    }
    if (!errors.isEmpty()) {
      return validation(errors);
    }

    try {
      Map<String, Object> result = runner.run(message.asString(), history);
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("response", result);
      return json(HttpStatus.OK, response);
    } catch (AgentRunner.AgentFailure e) {
      log.error("Agent request failed: {}", e.getMessage(), e.getCause());
      return json(HttpStatus.INTERNAL_SERVER_ERROR, Map.of("detail", e.getMessage()));
    } catch (RuntimeException e) {
      log.error("Unhandled error in /prompt", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .contentType(MediaType.TEXT_PLAIN)
          .body("Internal Server Error");
    }
  }

  private static Map<String, Object> error(String type, List<?> loc, String msg, Object input) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("type", type);
    e.put("loc", loc);
    e.put("msg", msg);
    e.put("input", input);
    return e;
  }

  private static ResponseEntity<String> validation(List<Map<String, Object>> errors) {
    return json(HttpStatus.UNPROCESSABLE_ENTITY, Map.of("detail", errors));
  }

  private static ResponseEntity<String> json(HttpStatus status, Object body) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(PyJson.MAPPER.writeValueAsString(body));
  }
}
