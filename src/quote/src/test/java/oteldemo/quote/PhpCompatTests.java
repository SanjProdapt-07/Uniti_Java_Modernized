/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PhpCompatTests {

  @ParameterizedTest
  @CsvSource({
    "26.97, 26.97",
    "8.99, 8.99",
    "0, 0",
    "-8.99, -8.99",
    "100.0, 100",
    "0.0001, 0.0001",
    "1.0E-5, 1.0e-5",
    "1.0E16, 10000000000000000",
    "1.0E25, 1.0e+25",
    "8.3E19, 8.3e+19",
    "123456789.125, 123456789.125"
  })
  void jsonEncodesFloatsLikePhp(double value, String expected) {
    assertThat(PhpCompat.jsonEncode(value)).isEqualTo(expected);
  }

  @Test
  void jsonEncodeSwitchesToExponentNotationAtOneE17() {
    assertThat(PhpCompat.jsonEncode(1.0E16)).isEqualTo("10000000000000000");
    assertThat(PhpCompat.jsonEncode(1.0E17)).isEqualTo("1.0e+17");
  }

  @Test
  void jsonEncodeRejectsNanAndInfinity() {
    assertThatThrownBy(() -> PhpCompat.jsonEncode(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PhpCompat.jsonEncode(Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundsHalfAwayFromZeroToTwoPlaces() {
    assertThat(PhpCompat.round(8.99 * 3, 2)).isEqualTo(26.97);
    assertThat(PhpCompat.round(0.125, 2)).isEqualTo(0.13);
    assertThat(PhpCompat.round(-0.125, 2)).isEqualTo(-0.13);
    assertThat(PhpCompat.round(0.0, 2)).isEqualTo(0.0);
    assertThat(PhpCompat.round(1.0E20, 2)).isEqualTo(1.0E20);
  }

  @Test
  void intvalFollowsPhpConversionRules() {
    assertThat(PhpCompat.intval(3L)).isEqualTo(3L);
    assertThat(PhpCompat.intval(3.99)).isEqualTo(3L);
    assertThat(PhpCompat.intval(-3.99)).isEqualTo(-3L);
    assertThat(PhpCompat.intval(true)).isEqualTo(1L);
    assertThat(PhpCompat.intval(false)).isEqualTo(0L);
    assertThat(PhpCompat.intval(null)).isEqualTo(0L);
    assertThat(PhpCompat.intval(List.of())).isEqualTo(0L);
    assertThat(PhpCompat.intval(List.of(1, 2))).isEqualTo(1L);
    assertThat(PhpCompat.intval(Map.of())).isEqualTo(0L);
    assertThat(PhpCompat.intval(Map.of("a", 1))).isEqualTo(1L);
    assertThat(PhpCompat.intval(Double.NaN)).isEqualTo(0L);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "3|3",
        "  12|12",
        "12abc|12",
        "abc|0",
        "|0",
        "-7|-7",
        "+7|7",
        "3.7|3",
        "1e3|1000",
        "1e|1",
        ".5|0",
        "99999999999999999999|9223372036854775807",
        "-99999999999999999999|-9223372036854775808"
      })
  void intvalOfStrings(String input, long expected) {
    assertThat(PhpCompat.intval(input == null ? "" : input)).isEqualTo(expected);
  }

  @Test
  void intvalOfIntegersBeyondInt64GoesThroughFloat() {
    // json_decode() yields a float for such integers; the cast wraps modulo 2^64.
    BigInteger twoTo64Plus5 = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(5));
    assertThat(PhpCompat.intval(twoTo64Plus5)).isEqualTo(0L);
    assertThat(PhpCompat.doubleToLong(1.0E19)).isEqualTo(-8446744073709551616L);
  }
}
