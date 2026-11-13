/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The part of Locust's web UI API that the demo (Envoy {@code /loadgen/} route, people and scripts) relies on:
 * {@code POST /swarm}, {@code GET /stop}, {@code GET /stats/requests}, {@code GET /stats/reset}, CSV exports,
 * {@code /exceptions} and {@code /tasks}. Envoy rewrites {@code /loadgen/...} to {@code /...}, and the web page
 * only uses relative URLs, so it works under that prefix.
 */
@RestController
class LocustController {

  private static final String VERSION = "java-1.0";

  private final LoadRunner runner;
  private final RequestStats stats;
  private final LoadGeneratorProperties properties;

  LocustController(LoadRunner runner, RequestStats stats, LoadGeneratorProperties properties) {
    this.runner = runner;
    this.stats = stats;
    this.properties = properties;
  }

  @PostMapping(path = "/swarm", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> swarm(
      @RequestParam(name = "user_count", required = false) String userCount,
      @RequestParam(name = "spawn_rate", required = false) String spawnRate,
      @RequestParam(name = "host", required = false) String host,
      @RequestParam(name = "run_time", required = false) String runTime) {
    Map<String, Object> result = new LinkedHashMap<>();
    int users;
    double rate;
    Duration duration;
    try {
      users = userCount == null || userCount.isBlank() ? properties.users() : Integer.parseInt(userCount.trim());
      rate = spawnRate == null || spawnRate.isBlank() ? properties.spawnRate() : Double.parseDouble(spawnRate.trim());
      duration = runTime == null || runTime.isBlank() ? properties.parsedRunTime() : LoadGeneratorProperties.parseDuration(runTime);
    } catch (IllegalArgumentException e) {
      result.put("success", false);
      result.put("message", "Invalid swarm parameters: " + e.getMessage());
      return result;
    }
    if (users < 0 || rate <= 0) {
      result.put("success", false);
      result.put("message", "user_count must be >= 0 and spawn_rate must be > 0");
      return result;
    }
    if (host != null && !host.isBlank()) {
      runner.setHost(host.trim());
    }
    runner.start(users, rate, duration);
    result.put("success", true);
    result.put("message", "Swarming started");
    result.put("host", runner.host());
    return result;
  }

  @GetMapping(path = "/stop", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> stop() {
    runner.stop();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", true);
    result.put("message", "Test stopped");
    return result;
  }

  @GetMapping(path = "/stats/reset", produces = MediaType.TEXT_PLAIN_VALUE)
  String reset() {
    stats.reset();
    return "ok";
  }

  @GetMapping(path = "/stats/requests", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> requests() {
    long now = stats.clock().millis();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (RequestStats.Entry entry : stats.entries()) {
      rows.add(row(entry, now));
    }
    rows.add(row(stats.total(), now));

    List<Map<String, Object>> errors = new ArrayList<>();
    for (RequestStats.ErrorEntry error : stats.errors()) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("occurrences", error.occurrences());
      e.put("name", error.name());
      e.put("method", error.method());
      e.put("error", error.error());
      errors.add(e);
    }

    RequestStats.Entry total = stats.total();
    Map<String, Object> percentiles = new LinkedHashMap<>();
    percentiles.put("response_time_percentile_0.5", total.median());
    percentiles.put("response_time_percentile_0.95", total.percentile(0.95));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("stats", rows);
    body.put("errors", errors);
    body.put("total_rps", total.currentRps(now));
    body.put("total_fail_per_sec", total.currentFailPerSec(now));
    body.put("fail_ratio", total.numRequests() == 0 ? 0.0 : (double) total.numFailures() / total.numRequests());
    body.put("current_response_time_percentiles", percentiles);
    body.put("state", runner.state().locustName());
    body.put("user_count", runner.userCount());
    body.put("workers", List.of());
    body.put("worker_count", 0);
    body.put("last_request_timestamp", stats.lastRequestTimestampMillis() / 1000.0);
    body.put("version", VERSION);
    return body;
  }

  @GetMapping(path = "/stats/requests/csv", produces = "text/csv")
  ResponseEntity<String> requestsCsv() {
    StringBuilder csv = new StringBuilder();
    csv.append("\"Type\",\"Name\",\"Request Count\",\"Failure Count\",\"Median Response Time\",")
        .append("\"Average Response Time\",\"Min Response Time\",\"Max Response Time\",")
        .append("\"Average Content Size\",\"Requests/s\",\"Failures/s\",\"95%\",\"99%\"\n");
    long now = stats.clock().millis();
    List<RequestStats.Entry> all = new ArrayList<>(stats.entries());
    all.add(stats.total());
    for (RequestStats.Entry e : all) {
      csv.append(e.method() == null ? "" : e.method()).append(',')
          .append(quote(e.name())).append(',')
          .append(e.numRequests()).append(',')
          .append(e.numFailures()).append(',')
          .append(e.median()).append(',')
          .append(e.avgResponseTime()).append(',')
          .append(e.minResponseTime()).append(',')
          .append(e.maxResponseTime()).append(',')
          .append(e.avgContentLength()).append(',')
          .append(e.currentRps(now)).append(',')
          .append(e.currentFailPerSec(now)).append(',')
          .append(e.percentile(0.95)).append(',')
          .append(e.percentile(0.99)).append('\n');
    }
    return csv(csv.toString(), "requests.csv");
  }

  @GetMapping(path = "/stats/failures/csv", produces = "text/csv")
  ResponseEntity<String> failuresCsv() {
    StringBuilder csv = new StringBuilder("\"Method\",\"Name\",\"Error\",\"Occurrences\"\n");
    for (RequestStats.ErrorEntry e : stats.errors()) {
      csv.append(e.method()).append(',').append(quote(e.name())).append(',').append(quote(e.error())).append(',')
          .append(e.occurrences()).append('\n');
    }
    return csv(csv.toString(), "failures.csv");
  }

  @GetMapping(path = "/exceptions", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> exceptions() {
    List<Map<String, Object>> list = new ArrayList<>();
    for (RequestStats.ErrorEntry e : stats.errors()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("count", e.occurrences());
      item.put("msg", e.error());
      item.put("traceback", "");
      item.put("nodes", "local");
      list.add(item);
    }
    return Map.of("exceptions", list);
  }

  @GetMapping(path = "/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> tasks() {
    Map<String, Object> perClass = new LinkedHashMap<>();
    Map<String, Object> ratios = new LinkedHashMap<>();
    int totalWeight = runner.userClasses().stream().mapToInt(LoadRunner.UserClass::weight).sum();
    for (LoadRunner.UserClass c : runner.userClasses()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("weight", c.weight());
      entry.put("ratio", totalWeight == 0 ? 0 : (double) c.weight() / totalWeight);
      perClass.put(c.name(), entry);
      ratios.put(c.name(), entry.get("ratio"));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("per_class", perClass);
    body.put("ratios", ratios);
    return body;
  }

  private static Map<String, Object> row(RequestStats.Entry e, long now) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("method", e.method());
    row.put("name", e.name());
    row.put("safe_name", e.name());
    row.put("num_requests", e.numRequests());
    row.put("num_failures", e.numFailures());
    row.put("min_response_time", e.minResponseTime());
    row.put("max_response_time", e.maxResponseTime());
    row.put("current_rps", e.currentRps(now));
    row.put("current_fail_per_sec", e.currentFailPerSec(now));
    row.put("median_response_time", e.median());
    row.put("avg_response_time", e.avgResponseTime());
    row.put("avg_content_length", e.avgContentLength());
    row.put("response_time_percentile_0.95", e.percentile(0.95));
    row.put("response_time_percentile_0.99", e.percentile(0.99));
    return row;
  }

  private static String quote(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static ResponseEntity<String> csv(String body, String filename) {
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment;filename=" + filename)
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }
}
