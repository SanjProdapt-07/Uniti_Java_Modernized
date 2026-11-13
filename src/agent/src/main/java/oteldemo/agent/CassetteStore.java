/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Replay/record of LLM responses in the VCR cassettes of the Python agent ({@code
 * fixtures/vcr_cassettes/<model>_cassette.yaml}, {@code record_mode="new_episodes"}).
 *
 * <p>Matching follows {@code patch_vcr.py}: only the {@code messages} of the request are compared;
 * requests with a different number of messages never match; tool-result contents are ignored; the
 * newest message is weighted 0.6 and the whole conversation 0.4 in a difflib similarity; the closest
 * recorded interaction at or above {@code VCR_MATCH_THRESHOLD} is replayed. A request without a
 * match is sent to the real LLM and appended to the cassette.
 */
final class CassetteStore {

  private static final Logger log = LoggerFactory.getLogger(CassetteStore.class);

  private static final String HEADER =
      "# Copyright The OpenTelemetry Authors\n# SPDX-License-Identifier: Apache-2.0\n\n";

  private record Interaction(
      Map<String, Object> raw, List<Map<String, Object>> messages, String responseBody) {}

  private record Loaded(long lastModified, long size, List<Interaction> interactions) {}

  private final Path directory;
  private final double threshold;
  private final Map<String, Loaded> cache = new LinkedHashMap<>();

  CassetteStore(Path directory, double threshold) {
    this.directory = directory;
    this.threshold = threshold;
  }

  static String cassetteName(String model) {
    return model.replace('/', '_') + "_cassette.yaml";
  }

  /** Returns the recorded response body (a chat-completion JSON string) for the closest match. */
  synchronized Optional<String> replay(String cassetteName, List<Map<String, Object>> liveMessages) {
    Path file = directory.resolve(cassetteName);
    List<Interaction> interactions;
    try {
      interactions = load(file);
    } catch (IOException | RuntimeException e) {
      log.warn("VCR: could not read cassette {}: {}", file, e.toString());
      return Optional.empty();
    }
    int bestIdx = -1;
    double bestScore = -1.0;
    for (int i = 0; i < interactions.size(); i++) {
      Interaction it = interactions.get(i);
      if (it.messages() == null) {
        continue;
      }
      double score = bodySimilarity(it.messages(), liveMessages);
      if (score >= threshold && score > bestScore) {
        bestScore = score;
        bestIdx = i;
      }
    }
    if (bestIdx < 0) {
      return Optional.empty();
    }
    log.info("VCR best-match playback: entry {} (score={})", bestIdx, String.format("%.4f", bestScore));
    return Optional.of(interactions.get(bestIdx).responseBody());
  }

  /** Appends a new episode (request normalised like {@code normalize_body}) to the cassette. */
  synchronized void record(String cassetteName, String requestBodyJson, String responseBodyJson) {
    Path file = directory.resolve(cassetteName);
    try {
      List<Map<String, Object>> raws = new ArrayList<>();
      if (Files.exists(file)) {
        for (Interaction it : load(file)) {
          raws.add(it.raw());
        }
      }
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("body", requestBodyJson);
      request.put("headers", new LinkedHashMap<>());
      request.put("method", "POST");
      request.put("uri", "https://vcr.local/");
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("string", responseBodyJson);
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("code", 200);
      status.put("message", "OK");
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("body", body);
      response.put("headers", new LinkedHashMap<>());
      response.put("status", status);
      Map<String, Object> interaction = new LinkedHashMap<>();
      interaction.put("request", request);
      interaction.put("response", response);
      raws.add(interaction);

      Map<String, Object> root = new LinkedHashMap<>();
      root.put("interactions", raws);
      Files.createDirectories(directory);
      DumperOptions options = new DumperOptions();
      options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      options.setSplitLines(false);
      options.setWidth(Integer.MAX_VALUE);
      Path tmp = Files.createTempFile(directory, "cassette", ".tmp");
      try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        w.write(HEADER);
        new Yaml(options).dump(root, w);
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      cache.remove(cassetteName);
    } catch (IOException | RuntimeException e) {
      log.error("VCR: could not record to cassette {}: {}", file, e.toString());
    }
  }

  // ----------------------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private List<Interaction> load(Path file) throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    long modified = Files.getLastModifiedTime(file).toMillis();
    long size = Files.size(file);
    Loaded cached = cache.get(file.getFileName().toString());
    if (cached != null && cached.lastModified() == modified && cached.size() == size) {
      return cached.interactions();
    }
    LoaderOptions loader = new LoaderOptions();
    loader.setCodePointLimit(256 * 1024 * 1024);
    loader.setMaxAliasesForCollections(1000);
    Object parsed;
    try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      parsed = new Yaml(new SafeConstructor(loader)).load(r);
    }
    List<Interaction> out = new ArrayList<>();
    if (parsed instanceof Map<?, ?> root && root.get("interactions") instanceof List<?> list) {
      for (Object o : list) {
        if (!(o instanceof Map<?, ?> m)) {
          continue;
        }
        Map<String, Object> raw = (Map<String, Object>) m;
        out.add(
            new Interaction(
                raw, requestMessages(raw.get("request")), responseBody(raw.get("response"))));
      }
    }
    cache.put(file.getFileName().toString(), new Loaded(modified, size, out));
    return out;
  }

  private static List<Map<String, Object>> requestMessages(Object request) {
    if (!(request instanceof Map<?, ?> r) || !(r.get("body") instanceof String body)) {
      return null; // binary or missing bodies (e.g. gzip telemetry) are not LLM requests
    }
    try {
      JsonNode node = PyJson.MAPPER.readTree(body);
      JsonNode messages = node.get("messages");
      if (messages == null || !messages.isArray()) {
        return null;
      }
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> list = PyJson.MAPPER.convertValue(messages, List.class);
      return list;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static String responseBody(Object response) {
    if (response instanceof Map<?, ?> r
        && r.get("body") instanceof Map<?, ?> b
        && b.get("string") instanceof String s) {
      return s;
    }
    return null;
  }

  // ---- similarity (patch_vcr.py: body_similarity) ----------------------------------------------

  static double bodySimilarity(List<Map<String, Object>> recorded, List<Map<String, Object>> live) {
    if (recorded.size() != live.size()) {
      return 0.0;
    }
    if (recorded.isEmpty()) {
      return 1.0;
    }
    List<Object> n1 = normalize(recorded);
    List<Object> n2 = normalize(live);
    double full = SequenceMatcher.ratio(stringify(n1), stringify(n2));
    double last =
        SequenceMatcher.ratio(stringify(n1.get(n1.size() - 1)), stringify(n2.get(n2.size() - 1)));
    return 0.4 * full + 0.6 * last;
  }

  private static List<Object> normalize(List<Map<String, Object>> messages) {
    List<Object> out = new ArrayList<>();
    for (Map<String, Object> m : messages) {
      if ("tool".equals(m.get("role"))) {
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.put("content", "<tool-result>");
        out.add(copy);
      } else {
        out.add(m);
      }
    }
    return out;
  }

  private static String stringify(Object value) {
    return PyJson.dumps(value, true, true);
  }
}
