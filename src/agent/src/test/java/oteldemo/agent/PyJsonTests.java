/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Expected strings are what CPython's json.dumps / repr produce. */
class PyJsonTests {

  @Test
  void dumpsUsesPythonSeparatorsAndKeepsOrder() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("b", List.of(1, 2.0, true, "x"));
    m.put("a", Map.of("k", "v"));
    m.put("n", null);
    assertThat(PyJson.dumps(m, true))
        .isEqualTo("{\"b\": [1, 2.0, true, \"x\"], \"a\": {\"k\": \"v\"}, \"n\": null}");
  }

  @Test
  void dumpsSortsKeysOnRequest() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("z", 1);
    m.put("a", 2);
    assertThat(PyJson.dumps(m, true, true)).isEqualTo("{\"a\": 2, \"z\": 1}");
  }

  @Test
  void ensureAsciiEscapesNonAscii() {
    assertThat(PyJson.dumps("é€\n\"\\", true)).isEqualTo("\"\\u00e9\\u20ac\\n\\\"\\\\\"");
    assertThat(PyJson.dumps("é€", false)).isEqualTo("\"é€\"");
    assertThat(PyJson.dumps("😀", true)).isEqualTo("\"\\ud83d\\ude00\"");
  }

  @Test
  void floatsUsePythonRepr() {
    assertThat(PyJson.pyFloat(1.0)).isEqualTo("1.0");
    assertThat(PyJson.pyFloat(0.1)).isEqualTo("0.1");
    assertThat(PyJson.pyFloat(1e16)).isEqualTo("1e+16");
    assertThat(PyJson.pyFloat(1.5e-5)).isEqualTo("1.5e-05");
    assertThat(PyJson.pyFloat(-2.5)).isEqualTo("-2.5");
    assertThat(PyJson.pyFloat(123456789.125)).isEqualTo("123456789.125");
  }

  @Test
  void reprMatchesPython() {
    assertThat(PyJson.repr("abc")).isEqualTo("'abc'");
    assertThat(PyJson.repr("it's")).isEqualTo("\"it's\"");
    assertThat(PyJson.repr(null)).isEqualTo("None");
    assertThat(PyJson.repr(true)).isEqualTo("True");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("a", List.of(1, "x"));
    m.put("b", false);
    assertThat(PyJson.repr(m)).isEqualTo("{'a': [1, 'x'], 'b': False}");
  }
}
