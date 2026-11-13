/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The expected ratios were computed with CPython's {@code difflib.SequenceMatcher(None, a, b).ratio()}. */
class SequenceMatcherTests {

  @Test
  void ratiosMatchPythonDifflib() throws Exception {
    JsonNode cases =
        JsonMapper.builder().build().readTree(getClass().getResourceAsStream("/difflib-ratios.json"));
    assertThat(cases.size()).isGreaterThanOrEqualTo(8);
    for (JsonNode c : cases) {
      double actual = SequenceMatcher.ratio(c.get("a").asString(), c.get("b").asString());
      assertThat(actual)
          .as("ratio of %s... / %s...", abbreviate(c.get("a").asString()), abbreviate(c.get("b").asString()))
          .isCloseTo(c.get("ratio").asDouble(), within(1e-12));
    }
  }

  @Test
  void emptyStringsAreIdentical() {
    assertThat(SequenceMatcher.ratio("", "")).isEqualTo(1.0);
  }

  private static String abbreviate(String s) {
    return s.length() > 30 ? s.substring(0, 30) : s;
  }
}
