/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Holds the flagd configuration and persists it to the shared JSON file. All reads and writes are
 * serialized (the Elixir version used a GenServer for the same purpose) so concurrent callers
 * cannot cause last-writer-wins conflicts.
 */
@Service
public class Storage {

  private static final Logger LOGGER = LoggerFactory.getLogger(Storage.class);

  private final Path filePath;
  private Object state;
  private long version;

  @Autowired
  public Storage(FlagdUiProperties properties) {
    this(Path.of(properties.storageFilePath()));
  }

  public Storage(Path filePath) {
    this.filePath = filePath;
    this.state = load(filePath);
    LOGGER.info("Read new state from file");
  }

  private static Object load(Path path) {
    try {
      String content = Files.readString(path, StandardCharsets.UTF_8);
      if (content.isEmpty()) {
        return new TreeMap<String, Object>();
      }
      return FlagJson.decode(content);
    } catch (IOException | FlagJson.InvalidJsonException e) {
      return new TreeMap<String, Object>();
    }
  }

  /** The current configuration. The returned tree must be treated as read-only. */
  public synchronized Object read() {
    return state;
  }

  /** Incremented after every change, lets the UI poll cheaply. */
  public synchronized long version() {
    return version;
  }

  /**
   * Replaces the whole configuration with the given JSON text, which is written to the file
   * verbatim. Invalid JSON is ignored and the previous state and file content are kept.
   *
   * @return whether the text was accepted
   */
  public synchronized boolean replace(String jsonString) {
    Object newState;
    try {
      newState = FlagJson.decode(jsonString);
    } catch (FlagJson.InvalidJsonException e) {
      LOGGER.warn("Ignoring replace with invalid JSON");
      return false;
    }
    writeState(jsonString);
    state = newState;
    version++;
    return true;
  }

  /**
   * Sets a flag to the given variant. A flag whose {@code targeting.if} is a three element list
   * (condition, on-value, off-value) is switched through that rule, any other flag through its
   * {@code defaultVariant}.
   */
  public synchronized void write(String flagName, String flagValue) {
    Map<String, Object> newState = new TreeMap<>();
    if (state instanceof Map<?, ?> current) {
      current.forEach((k, v) -> newState.put(String.valueOf(k), v));
    } else {
      throw new IllegalStateException("flagd configuration is not a JSON object");
    }
    Object flags = newState.get("flags");
    if (!newState.containsKey("flags")) {
      newState.put("flags", new TreeMap<String, Object>());
    } else if (flags instanceof Map<?, ?> flagMap) {
      newState.put("flags", updateFlag(flagMap, flagName, flagValue));
    } else {
      throw new IllegalStateException("flagd configuration flags is not a JSON object");
    }

    writeState(FlagJson.encodePretty(newState));
    state = newState;
    version++;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> updateFlag(Map<?, ?> flags, String flagName, String value) {
    Map<String, Object> updated = new TreeMap<>();
    for (Map.Entry<?, ?> entry : flags.entrySet()) {
      String flag = String.valueOf(entry.getKey());
      Object data = entry.getValue();
      if (flag.equals(flagName) && data instanceof Map<?, ?> dataMap) {
        Map<String, Object> newData = new TreeMap<>((Map<String, Object>) dataMap);
        List<Object> rule = targetingIf(newData);
        if (rule != null && rule.size() == 3) {
          Map<String, Object> targeting = new TreeMap<>((Map<String, Object>) newData.get("targeting"));
          List<Object> newRule = new ArrayList<>(rule);
          newRule.set(1, value);
          targeting.put("if", newRule);
          newData.put("targeting", targeting);
        } else if (newData.containsKey("defaultVariant")) {
          // Map.replace/3 only replaces an existing key
          newData.put("defaultVariant", value);
        }
        updated.put(flag, newData);
      } else {
        updated.put(flag, data);
      }
    }
    return updated;
  }

  /** {@code get_in(data, ["targeting", "if"])} when it is a list, otherwise null. */
  @SuppressWarnings("unchecked")
  static List<Object> targetingIf(Map<String, Object> data) {
    if (data.get("targeting") instanceof Map<?, ?> targeting
        && ((Map<String, Object>) targeting).get("if") instanceof List<?> list) {
      return (List<Object>) list;
    }
    return null;
  }

  /** Write-then-rename so concurrent readers (e.g. flagd) never observe a truncated file. */
  private void writeState(String jsonString) {
    Path tmpPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
    try {
      Files.writeString(tmpPath, jsonString, StandardCharsets.UTF_8);
      try {
        Files.move(
            tmpPath,
            filePath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write " + filePath, e);
    }
    LOGGER.info("Wrote new state to file");
  }
}
