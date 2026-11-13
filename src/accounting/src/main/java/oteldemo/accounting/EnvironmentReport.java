/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Prints the environment variables relevant to the service at startup, sorted by name, with the
 * values of secret-looking variables masked (port of the {@code Helpers} class of the .NET
 * service).
 */
final class EnvironmentReport {

  // The .NET service listed DOTNET_ / CORECLR_ as well; those do not exist for a JVM service.
  private static final List<String> RELEVANT_PREFIXES = List.of("OTEL_", "KAFKA_");

  // Values of matching variables are masked when printed, e.g. OTEL_EXPORTER_OTLP_HEADERS (API
  // keys, bearer tokens). Same pattern the OpenTelemetry .NET auto-instrumentation uses to hide
  // secrets when it logs environment variables.
  private static final Pattern SECRETS =
      Pattern.compile(
          "(?:^|_)(API|TOKEN|SECRET|KEY|PASSWORD|PASS|PWD|HEADERS?|CREDENTIALS)(?:_|=|$)",
          Pattern.CASE_INSENSITIVE);

  private EnvironmentReport() {}

  static void printRelevant(Map<String, String> environment) {
    print(environment, System.out);
  }

  static void print(Map<String, String> environment, PrintStream out) {
    for (Map.Entry<String, String> entry : filterRelevant(environment).entrySet()) {
      String value = isSensitive(entry.getKey()) ? "[REDACTED]" : entry.getValue();
      out.println("[" + entry.getKey() + ", " + value + "]");
    }
  }

  /** Relevant variables, ordered by name (ordinal comparison). */
  static Map<String, String> filterRelevant(Map<String, String> environment) {
    Map<String, String> relevant = new TreeMap<>();
    for (Map.Entry<String, String> entry : environment.entrySet()) {
      String key = entry.getKey();
      for (String prefix : RELEVANT_PREFIXES) {
        if (key.toUpperCase(Locale.ROOT).startsWith(prefix)) {
          relevant.put(key, entry.getValue());
          break;
        }
      }
    }
    return relevant;
  }

  static boolean isSensitive(String key) {
    return key != null && SECRETS.matcher(key).find();
  }
}
