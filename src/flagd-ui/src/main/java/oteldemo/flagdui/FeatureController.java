/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Programmatic API for reading and replacing the whole flagd configuration. {@code /read-file} and
 * {@code /write-to-file} are kept for compatibility with the old version of the flagd UI.
 */
@RestController
@RequestMapping("/api")
public class FeatureController {

  private final Storage storage;

  public FeatureController(Storage storage) {
    this.storage = storage;
  }

  @GetMapping({"/read", "/read-file"})
  public ResponseEntity<String> read() {
    if (storage.read() instanceof Map<?, ?> state && state.containsKey("flags")) {
      return json(HttpStatus.OK, FlagJson.encode(Map.of("flags", state.get("flags"))));
    }
    // The Phoenix controller crashed on a MatchError here, which Phoenix renders as a 500.
    return ErrorPages.json(HttpStatus.INTERNAL_SERVER_ERROR.value());
  }

  @PostMapping({"/write", "/write-to-file"})
  public ResponseEntity<String> write(@RequestBody(required = false) String body) {
    JsonNode request;
    try {
      request = body == null || body.isBlank() ? null : FlagJson.readTree(body);
    } catch (FlagJson.InvalidJsonException e) {
      return ErrorPages.json(HttpStatus.BAD_REQUEST.value());
    }
    if (request == null || !request.isObject() || !request.has("data")) {
      return ErrorPages.json(HttpStatus.BAD_REQUEST.value());
    }

    storage.replace(FlagJson.encode(FlagJson.convert(request.get("data"))));
    return json(HttpStatus.OK, "{}");
  }

  private static ResponseEntity<String> json(HttpStatus status, String body) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
