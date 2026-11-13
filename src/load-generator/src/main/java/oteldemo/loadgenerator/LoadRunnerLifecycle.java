/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Locust's startup behavior: {@code LOCUST_HEADLESS=true} or {@code LOCUST_AUTOSTART=true} start swarming with
 * {@code LOCUST_USERS} users at {@code LOCUST_SPAWN_RATE} as soon as the application is up; otherwise the runner
 * waits for a start request on the web UI.
 */
@Component
class LoadRunnerLifecycle implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(LoadRunnerLifecycle.class);

  private final LoadRunner runner;
  private final LoadGeneratorProperties properties;

  LoadRunnerLifecycle(LoadRunner runner, LoadGeneratorProperties properties) {
    this.runner = runner;
    this.properties = properties;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (properties.headless() || properties.autostart()) {
      log.info(
          "Starting {} users at {}/s against '{}' ({})",
          properties.users(),
          properties.spawnRate(),
          properties.host(),
          properties.headless() ? "headless" : "autostart");
      runner.start(properties.users(), properties.spawnRate(), properties.parsedRunTime());
    }
  }

  /** Called when {@code LOCUST_RUN_TIME} elapsed. Headless runs exit (code 1 if requests failed). */
  static void onRunTimeFinished(LoadGeneratorProperties properties, RequestStats stats, ExitHandler exit) {
    if (properties.headless()) {
      int code = stats.total().numFailures() > 0 ? 1 : 0;
      log.info("Headless run finished, exiting with code {}", code);
      exit.exit(code);
    } else if (properties.autoquit() >= 0) {
      try {
        Thread.sleep(properties.autoquit() * 1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      exit.exit(stats.total().numFailures() > 0 ? 1 : 0);
    }
  }
}
