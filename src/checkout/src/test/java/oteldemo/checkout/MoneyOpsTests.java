/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import oteldemo.Demo.Money;
import oteldemo.checkout.MoneyOps.MoneyException;

/** Port of money/money_test.go, plus MultiplySlow and the unsigned multiplier. */
class MoneyOpsTests {

  private static Money mmc(long units, int nanos, String currency) {
    return Money.newBuilder().setUnits(units).setNanos(nanos).setCurrencyCode(currency).build();
  }

  private static Money mm(long units, int nanos) {
    return mmc(units, nanos, "");
  }

  static Stream<Arguments> isValidCases() {
    return Stream.of(
        Arguments.of("valid -/-", mm(-981273891273L, -999999999), true),
        Arguments.of("invalid -/+", mm(-981273891273L, +999999999), false),
        Arguments.of("valid +/+", mm(981273891273L, 999999999), true),
        Arguments.of("invalid +/-", mm(981273891273L, -999999999), false),
        Arguments.of("invalid +/+overflow", mm(3, 1000000000), false),
        Arguments.of("invalid +/-overflow", mm(3, -1000000000), false),
        Arguments.of("invalid -/+overflow", mm(-3, 1000000000), false),
        Arguments.of("invalid -/-overflow", mm(-3, -1000000000), false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("isValidCases")
  void isValid(String name, Money in, boolean want) {
    assertThat(MoneyOps.isValid(in)).isEqualTo(want);
  }

  static Stream<Arguments> isZeroCases() {
    return Stream.of(
        Arguments.of("zero", mm(0, 0), true),
        Arguments.of("not-zero (-/+)", mm(-1, +1), false),
        Arguments.of("not-zero (-/-)", mm(-1, -1), false),
        Arguments.of("not-zero (+/+)", mm(+1, +1), false),
        Arguments.of("not-zero (+/-)", mm(+1, -1), false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("isZeroCases")
  void isZero(String name, Money in, boolean want) {
    assertThat(MoneyOps.isZero(in)).isEqualTo(want);
  }

  static Stream<Arguments> isPositiveCases() {
    return Stream.of(
        Arguments.of("zero", mm(0, 0), false),
        Arguments.of("positive (+/+)", mm(+1, +1), true),
        Arguments.of("invalid (-/+)", mm(-1, +1), false),
        Arguments.of("negative (-/-)", mm(-1, -1), false),
        Arguments.of("invalid (+/-)", mm(+1, -1), false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("isPositiveCases")
  void isPositive(String name, Money in, boolean want) {
    assertThat(MoneyOps.isPositive(in)).isEqualTo(want);
  }

  static Stream<Arguments> isNegativeCases() {
    return Stream.of(
        Arguments.of("zero", mm(0, 0), false),
        Arguments.of("positive (+/+)", mm(+1, +1), false),
        Arguments.of("invalid (-/+)", mm(-1, +1), false),
        Arguments.of("negative (-/-)", mm(-1, -1), true),
        Arguments.of("invalid (+/-)", mm(+1, -1), false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("isNegativeCases")
  void isNegative(String name, Money in, boolean want) {
    assertThat(MoneyOps.isNegative(in)).isEqualTo(want);
  }

  static Stream<Arguments> areSameCurrencyCases() {
    return Stream.of(
        Arguments.of("both empty currency", mmc(1, 0, ""), mmc(2, 0, ""), false),
        Arguments.of("left empty currency", mmc(1, 0, ""), mmc(2, 0, "USD"), false),
        Arguments.of("right empty currency", mmc(1, 0, "USD"), mmc(2, 0, ""), false),
        Arguments.of("mismatching", mmc(1, 0, "USD"), mmc(2, 0, "CAD"), false),
        Arguments.of("matching", mmc(1, 0, "USD"), mmc(2, 0, "USD"), true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("areSameCurrencyCases")
  void areSameCurrency(String name, Money l, Money r, boolean want) {
    assertThat(MoneyOps.areSameCurrency(l, r)).isEqualTo(want);
  }

  static Stream<Arguments> areEqualsCases() {
    return Stream.of(
        Arguments.of("equals", mmc(1, 2, "USD"), mmc(1, 2, "USD"), true),
        Arguments.of("mismatching currency", mmc(1, 2, "USD"), mmc(1, 2, "CAD"), false),
        Arguments.of("mismatching units", mmc(10, 20, "USD"), mmc(1, 20, "USD"), false),
        Arguments.of("mismatching nanos", mmc(1, 2, "USD"), mmc(1, 20, "USD"), false),
        Arguments.of("negated", mmc(1, 2, "USD"), mmc(-1, -2, "USD"), false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("areEqualsCases")
  void areEquals(String name, Money l, Money r, boolean want) {
    assertThat(MoneyOps.areEquals(l, r)).isEqualTo(want);
  }

  static Stream<Arguments> negateCases() {
    return Stream.of(
        Arguments.of("zero", mm(0, 0), mm(0, 0)),
        Arguments.of("negative", mm(-1, -200), mm(1, 200)),
        Arguments.of("positive", mm(1, 200), mm(-1, -200)),
        Arguments.of("carries currency code", mmc(0, 0, "XXX"), mmc(0, 0, "XXX")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("negateCases")
  void negate(String name, Money in, Money want) {
    assertThat(MoneyOps.areEquals(MoneyOps.negate(in), want)).isTrue();
  }

  @Test
  void mustPass() {
    Money v = MoneyOps.must(mm(2, 3), null);
    assertThat(MoneyOps.areEquals(v, mm(2, 3))).as("returned the wrong value: %s", v).isTrue();
  }

  @Test
  void mustPanic() {
    assertThatThrownBy(() -> MoneyOps.must(mm(2, 3), new MoneyException("some error")))
        .isInstanceOf(MoneyException.class)
        .hasMessage("some error");
  }

  static Stream<Arguments> sumCases() {
    return Stream.of(
        Arguments.of("0+0=0", mm(0, 0), mm(0, 0), mm(0, 0), null),
        Arguments.of(
            "Error: currency code on left",
            mmc(0, 0, "XXX"),
            mm(0, 0),
            null,
            MoneyOps.ERR_MISMATCHING_CURRENCY),
        Arguments.of(
            "Error: currency code on right",
            mm(0, 0),
            mmc(0, 0, "YYY"),
            null,
            MoneyOps.ERR_MISMATCHING_CURRENCY),
        Arguments.of(
            "Error: currency code mismatch",
            mmc(0, 0, "AAA"),
            mmc(0, 0, "BBB"),
            null,
            MoneyOps.ERR_MISMATCHING_CURRENCY),
        Arguments.of("Error: invalid +/-", mm(+1, -1), mm(0, 0), null, MoneyOps.ERR_INVALID_VALUE),
        Arguments.of("Error: invalid -/+", mm(0, 0), mm(-1, +2), null, MoneyOps.ERR_INVALID_VALUE),
        Arguments.of(
            "Error: invalid nanos", mm(0, 1000000000), mm(1, 0), null, MoneyOps.ERR_INVALID_VALUE),
        Arguments.of(
            "both positive (no carry)", mm(2, 200000000), mm(2, 200000000), mm(4, 400000000), null),
        Arguments.of(
            "both positive (nanos=max)",
            mm(2, 111111111),
            mm(2, 888888888),
            mm(4, 999999999),
            null),
        Arguments.of(
            "both positive (carry)", mm(2, 200000000), mm(2, 900000000), mm(5, 100000000), null),
        Arguments.of(
            "both negative (no carry)",
            mm(-2, -200000000),
            mm(-2, -200000000),
            mm(-4, -400000000),
            null),
        Arguments.of(
            "both negative (carry)",
            mm(-2, -200000000),
            mm(-2, -900000000),
            mm(-5, -100000000),
            null),
        Arguments.of("mixed (larger positive, just decimals)", mm(11, 0), mm(-2, 0), mm(9, 0), null),
        Arguments.of("mixed (larger negative, just decimals)", mm(-11, 0), mm(2, 0), mm(-9, 0), null),
        Arguments.of(
            "mixed (larger positive, no borrow)",
            mm(11, 100000000),
            mm(-2, -100000000),
            mm(9, 0),
            null),
        Arguments.of(
            "mixed (larger positive, with borrow)",
            mm(11, 100000000),
            mm(-2, -9000000 /*.09*/),
            mm(9, 91000000 /*.091*/),
            null),
        Arguments.of(
            "mixed (larger negative, no borrow)",
            mm(-11, -100000000),
            mm(2, 100000000),
            mm(-9, 0),
            null),
        Arguments.of(
            "mixed (larger negative, with borrow)",
            mm(-11, -100000000),
            mm(2, 9000000 /*.09*/),
            mm(-9, -91000000 /*.091*/),
            null),
        Arguments.of("0+negative", mm(0, 0), mm(-2, -100000000), mm(-2, -100000000), null),
        Arguments.of("negative+0", mm(-2, -100000000), mm(0, 0), mm(-2, -100000000), null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sumCases")
  void sum(String name, Money l, Money r, Money want, String wantError) {
    if (wantError != null) {
      assertThatThrownBy(() -> MoneyOps.sum(l, r))
          .isInstanceOf(MoneyException.class)
          .hasMessage(wantError);
    } else {
      assertThat(MoneyOps.sum(l, r)).isEqualTo(want);
    }
  }

  @Test
  void multiplySlowAddsTheValueToItselfNMinusOneTimes() {
    Money price = mmc(19, 990000000, "USD");

    assertThat(MoneyOps.multiplySlow(price, 0)).isEqualTo(price);
    assertThat(MoneyOps.multiplySlow(price, 1)).isEqualTo(price);
    assertThat(MoneyOps.multiplySlow(price, 3)).isEqualTo(mmc(59, 970000000, "USD"));
  }

  @Test
  void multiplySlowFailsOnInvalidValues() {
    assertThatThrownBy(() -> MoneyOps.multiplySlow(mm(1, -1), 2)).isInstanceOf(MoneyException.class);
  }
}
