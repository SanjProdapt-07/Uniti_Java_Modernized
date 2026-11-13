/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The three pages of the flagd UI (basic, advanced, scheduler) and the small JSON/HTML endpoints
 * their JavaScript uses. These replace what Phoenix LiveView did over its websocket.
 */
@Controller
public class UiController {

  /** Timing presets of the scheduler page: name, label, interval, minimum, maximum (seconds). */
  public record Preset(String name, String label, int interval, int min, int max) {}

  static final List<Preset> PRESETS =
      List.of(
          new Preset("presentation", "Presentation (60s / 10-20s)", 60, 10, 20),
          new Preset("frequent", "Frequent (15m / 1-3m)", 900, 60, 180),
          new Preset("hourly", "Hourly (1h / 5-15m)", 3600, 300, 900));

  /** One flag card of the basic page. */
  public record FlagCard(
      String name, String description, List<String> options, String selected, boolean targeting) {}

  /** Values of the scheduler form. */
  public record SchedulerForm(
      String intervalSeconds,
      String minDurationSeconds,
      String maxDurationSeconds,
      String concurrency,
      String seed,
      Map<String, List<String>> selection,
      int maxConcurrency) {

    public boolean selected(String flag, String variant) {
      return selection.getOrDefault(flag, List.of()).contains(variant);
    }
  }

  private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

  private final Storage storage;
  private final Scheduler scheduler;
  private final FlagdUiProperties properties;

  public UiController(Storage storage, Scheduler scheduler, FlagdUiProperties properties) {
    this.storage = storage;
    this.scheduler = scheduler;
    this.properties = properties;
  }

  // ------------------------------------------------------------------------------------------
  // Pages

  @GetMapping("/")
  public String dashboard(Model model) {
    common(model, "basic");
    model.addAttribute("cards", cards());
    model.addAttribute("version", storage.version());
    return "dashboard";
  }

  @GetMapping("/advanced")
  public String advanced(Model model) {
    common(model, "advanced");
    model.addAttribute("content", FlagJson.encodePretty(storage.read()));
    return "advanced";
  }

  @GetMapping("/scheduler")
  public String scheduler(Model model) {
    common(model, "scheduler");
    Scheduler.State state = scheduler.state();
    List<Scheduler.SchedulableFlag> available = availableFlags();
    model.addAttribute("available", available);
    model.addAttribute("form", formFrom(state, available));
    model.addAttribute("presets", PRESETS);
    schedulerStatus(model, state);
    return "scheduler";
  }

  private void common(Model model, String mode) {
    String prefix = properties.linkPrefix();
    model.addAttribute("prefix", prefix);
    model.addAttribute("home", prefix.isEmpty() ? "/" : prefix);
    model.addAttribute("rootPath", properties.rootPath());
    model.addAttribute("mode", mode);
  }

  // ------------------------------------------------------------------------------------------
  // Basic page endpoints

  @GetMapping(value = "/ui/flags/version", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public Map<String, Object> flagsVersion() {
    return Map.of("version", storage.version());
  }

  @GetMapping("/ui/flags/grid")
  public String flagsGrid(Model model) {
    common(model, "basic");
    model.addAttribute("cards", cards());
    model.addAttribute("version", storage.version());
    return "dashboard :: grid";
  }

  public record FlagChange(String flag, String variant) {}

  @PostMapping(value = "/ui/flags", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public Map<String, Object> changeFlag(@RequestBody FlagChange change) {
    storage.write(change.flag(), change.variant());
    return Map.of("message", "Saved: " + change.flag());
  }

  @SuppressWarnings("unchecked")
  List<FlagCard> cards() {
    List<FlagCard> cards = new ArrayList<>();
    if (!(storage.read() instanceof Map<?, ?> root) || !(root.get("flags") instanceof Map<?, ?> flags)) {
      return cards;
    }
    for (Map.Entry<?, ?> entry : flags.entrySet()) {
      String name = String.valueOf(entry.getKey());
      Map<String, Object> data =
          entry.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
      List<String> options = new ArrayList<>();
      if (data.get("variants") instanceof Map<?, ?> variants) {
        variants.keySet().forEach(key -> options.add(String.valueOf(key)));
      }
      Object selected = data.get("defaultVariant");
      List<Object> rule = Storage.targetingIf(data);
      if (rule != null && rule.size() >= 2) {
        selected = rule.get(1);
      }
      Object description = data.get("description");
      cards.add(
          new FlagCard(
              name,
              description == null ? "" : String.valueOf(description),
              options,
              selected == null ? null : String.valueOf(selected),
              data.containsKey("targeting")));
    }
    return cards;
  }

  // ------------------------------------------------------------------------------------------
  // Advanced page endpoint

  public record SaveRequest(String content) {}

  @PostMapping(value = "/ui/advanced/save", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public Map<String, Object> saveAdvanced(@RequestBody SaveRequest request) {
    String content = request.content() == null ? "" : request.content();
    try {
      FlagJson.decode(content);
    } catch (FlagJson.InvalidJsonException e) {
      return Map.of("ok", false, "message", "Invalid JSON");
    }
    String trimmed = content.strip();
    storage.replace(trimmed);
    return Map.of("ok", true, "message", "Saved!", "content", trimmed);
  }

  // ------------------------------------------------------------------------------------------
  // Scheduler page endpoints

  @GetMapping("/ui/scheduler/status")
  public String schedulerStatusFragment(Model model) {
    common(model, "scheduler");
    schedulerStatus(model, scheduler.state());
    return "scheduler-status :: poll";
  }

  @PostMapping(value = "/ui/scheduler/start", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public Map<String, Object> startScheduler(@RequestBody Map<String, Object> params) {
    Object built = buildConfig(params, selectionFrom(params.get("variants")));
    if (built instanceof String message) {
      return Map.of("ok", false, "error", message);
    }
    Optional<String> error = scheduler.startSchedule((Scheduler.Config) built);
    if (error.isPresent()) {
      return Map.of("ok", false, "error", error.get());
    }
    return Map.of("ok", true, "message", "Scheduler started");
  }

  @PostMapping(value = "/ui/scheduler/stop", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public Map<String, Object> stopScheduler() {
    scheduler.stopSchedule();
    return Map.of("ok", true, "message", "Scheduler stopped");
  }

  private void schedulerStatus(Model model, Scheduler.State state) {
    model.addAttribute("scheduler", SchedulerView.of(state, Scheduler.monotonicMillis()));
  }

  private List<Scheduler.SchedulableFlag> availableFlags() {
    return Scheduler.schedulableFlags(storage.read());
  }

  private SchedulerForm formFrom(Scheduler.State state, List<Scheduler.SchedulableFlag> available) {
    Scheduler.Config config = state.config();
    Map<String, List<String>> selected = new TreeMap<>();
    if (config.flags() == null) {
      available.forEach(flag -> selected.put(flag.name(), flag.variants()));
    } else {
      selected.putAll(config.flags());
    }
    return new SchedulerForm(
        String.valueOf(config.intervalMs() / 1000),
        String.valueOf(config.minDurationMs() / 1000),
        String.valueOf(config.maxDurationMs() / 1000),
        String.valueOf(config.concurrency()),
        config.seed() == null ? "" : String.valueOf(config.seed()),
        selected,
        Math.max(available.size(), 1));
  }

  /**
   * Builds the scheduler configuration from the submitted form values. Returns either a {@link
   * Scheduler.Config} or, for unusable input, the error message as a {@link String}.
   */
  private Object buildConfig(
      Map<String, Object> form, Map<String, List<String>> selection) {
    try {
      long interval =
          parsePositive(
              form.get("interval_seconds"),
              "Interval must be a whole number of seconds greater than zero");
      long minDuration =
          parsePositive(
              form.get("min_duration_seconds"),
              "Minimum duration must be a whole number of seconds greater than zero");
      long maxDuration =
          parsePositive(
              form.get("max_duration_seconds"),
              "Maximum duration must be a whole number of seconds greater than zero");
      long concurrency =
          parsePositive(
              form.get("concurrency"),
              "Flags at once must be a whole number greater than zero");
      Long seed = parseSeed(form.get("seed"));
      return new Scheduler.Config(
              Math.multiplyExact(interval, 1000L),
              Math.multiplyExact(minDuration, 1000L),
              Math.multiplyExact(maxDuration, 1000L),
              (int) Math.min(concurrency, Integer.MAX_VALUE),
              seed,
              selection);
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    } catch (ArithmeticException e) {
      return "Interval must be a whole number of seconds greater than zero";
    }
  }

  private static long parsePositive(Object value, String message) {
    String text = value instanceof Number n ? String.valueOf(n) : value instanceof String s ? s : null;
    if (text == null) {
      throw new IllegalArgumentException(message);
    }
    text = text.strip();
    if (!INTEGER.matcher(text).matches()) {
      throw new IllegalArgumentException(message);
    }
    BigInteger parsed = new BigInteger(text.startsWith("+") ? text.substring(1) : text);
    if (parsed.signum() <= 0 || parsed.bitLength() > 62) {
      throw new IllegalArgumentException(message);
    }
    return parsed.longValue();
  }

  private static Long parseSeed(Object value) {
    if (value == null) {
      return null;
    }
    String text = value instanceof Number n ? String.valueOf(n) : value instanceof String s ? s : null;
    if (text == null) {
      throw new IllegalArgumentException("Seed must be a whole number");
    }
    text = text.strip();
    if (text.isEmpty()) {
      return null;
    }
    if (!INTEGER.matcher(text).matches()) {
      throw new IllegalArgumentException("Seed must be a whole number");
    }
    try {
      return Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Seed must be a whole number");
    }
  }

  private static Map<String, List<String>> selectionFrom(Object variants) {
    Map<String, List<String>> selection = new LinkedHashMap<>();
    if (variants instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        List<String> chosen = new ArrayList<>();
        if (entry.getValue() instanceof List<?> list) {
          list.forEach(item -> chosen.add(String.valueOf(item)));
        }
        selection.put(String.valueOf(entry.getKey()), chosen);
      }
    }
    return selection;
  }
}
