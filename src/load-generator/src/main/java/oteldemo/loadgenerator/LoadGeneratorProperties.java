/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration, fed from the same environment variables the Locust based service honored (see
 * application.yaml). Locust's own CLI/env options that the demo uses are mirrored here.
 *
 * @param host {@code LOCUST_HOST}: base URL of the site under test
 * @param users {@code LOCUST_USERS}: number of simulated users for headless / autostart
 * @param spawnRate {@code LOCUST_SPAWN_RATE}: users started per second
 * @param runTime {@code LOCUST_RUN_TIME}: e.g. {@code 30s}, {@code 10m}, {@code 1h30m}; empty = unlimited
 * @param headless {@code LOCUST_HEADLESS}: start immediately, no UI interaction needed
 * @param autostart {@code LOCUST_AUTOSTART}: start swarming on startup although the web UI is available
 * @param autoquit {@code LOCUST_AUTOQUIT}: seconds to wait after the run ended before exiting (-1 = never)
 * @param browserTrafficEnabled {@code LOCUST_BROWSER_TRAFFIC_ENABLED}: true / yes / on enables browser users
 * @param httpUserWeight {@code LOCUST_HTTP_USER_WEIGHT}
 * @param browserUserWeight {@code LOCUST_BROWSER_USER_WEIGHT}
 * @param agentEndpoint {@code AGENT_ENDPOINT}
 * @param agentPort {@code AGENT_PORT}
 * @param userAgent value of the User-Agent header (python-requests default of the original)
 */
@ConfigurationProperties(prefix = "load-generator")
public record LoadGeneratorProperties(
    String host,
    int users,
    double spawnRate,
    String runTime,
    boolean headless,
    boolean autostart,
    int autoquit,
    String browserTrafficEnabled,
    int httpUserWeight,
    int browserUserWeight,
    String agentEndpoint,
    String agentPort,
    String userAgent) {

  private static final Set<String> TRUTHY = Set.of("true", "yes", "on");

  /** Same parsing as the original: {@code .lower() in ("true", "yes", "on")}. */
  public boolean isBrowserTrafficEnabled() {
    return browserTrafficEnabled != null && TRUTHY.contains(browserTrafficEnabled.toLowerCase(Locale.ROOT));
  }

  public Duration parsedRunTime() {
    return parseDuration(runTime);
  }

  /** Locust time span syntax: {@code 300}, {@code 20s}, {@code 3m}, {@code 2h}, {@code 1h30m10s}. */
  static Duration parseDuration(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String value = text.trim().toLowerCase(Locale.ROOT);
    if (value.matches("\\d+")) {
      return Duration.ofSeconds(Long.parseLong(value));
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matcher(value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid time span: " + text);
    }
    long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
    long minutes = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
    long seconds = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
    return Duration.ofSeconds(hours * 3600 + minutes * 60 + seconds);
  }
}
