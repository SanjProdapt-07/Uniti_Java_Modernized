/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import oteldemo.Demo.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The expected units / nanos below were derived by running the algorithm of the original
 * {@code server.cpp} (getDouble, rate division, getUnitsAndNanos) step by step with IEEE-754 doubles
 * in an independent implementation, not by calling the Java code under test.
 */
class CurrencyConverterTests {

  private final CurrencyConverter converter = new CurrencyConverter();

  private static Money money(String code, long units, int nanos) {
    return Money.newBuilder().setCurrencyCode(code).setUnits(units).setNanos(nanos).build();
  }

  // ---- supported currencies -------------------------------------------------------------

  @Test
  void supportsTheThirtyThreeCurrenciesOfTheRateTable() {
    List<String> codes = converter.supportedCurrencies();

    assertThat(codes)
        .hasSize(33)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrder(
            "EUR", "USD", "JPY", "BGN", "CZK", "DKK", "GBP", "HUF", "PLN", "RON", "SEK", "CHF", "ISK",
            "NOK", "HRK", "RUB", "TRY", "AUD", "BRL", "CAD", "CNY", "HKD", "IDR", "ILS", "INR", "KRW",
            "MXN", "MYR", "NZD", "PHP", "SGD", "THB", "ZAR");
  }

  @Test
  void ratesMatchTheOriginalTable() {
    assertThat(CurrencyConverter.rate("EUR")).isEqualTo(1.0);
    assertThat(CurrencyConverter.rate("USD")).isEqualTo(1.1305);
    assertThat(CurrencyConverter.rate("JPY")).isEqualTo(126.40);
    assertThat(CurrencyConverter.rate("GBP")).isEqualTo(0.85970);
    assertThat(CurrencyConverter.rate("IDR")).isEqualTo(15999.40);
    assertThat(CurrencyConverter.rate("ZAR")).isEqualTo(16.0583);
  }

  @Test
  void unknownCurrencyReadsAsRateZeroLikeCppOperatorBrackets() {
    assertThat(CurrencyConverter.rate("XXX")).isEqualTo(0.0);
    assertThat(CurrencyConverter.rate("")).isEqualTo(0.0);
    assertThat(CurrencyConverter.rate("usd")).isEqualTo(0.0); // lookups are case sensitive
  }

  // ---- getDouble ------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
    "0, 0, 0.0",
    "10, 500000000, 10.5",
    "1, 990000000, 1.99",
    // The digit walk does not left-pad: 5 nanos is read as 0.5, 50_000_000 nanos as 0.5.
    "0, 5, 0.5",
    "0, 50000000, 0.5",
    "7, 999999999, 7.999999999",
    "-5, 0, -5.0",
    "-5, -500000000, -5.5",
    "-5, 500000000, -4.5",
    "0, 2147483647, 0.2147483647",
    "0, -2147483648, -0.2147483648",
  })
  void toDoubleWalksTheNanoDigitsLikeTheOriginal(long units, int nanos, double expected) {
    assertThat(CurrencyConverter.toDouble(money("USD", units, nanos))).isEqualTo(expected);
  }

  // ---- Convert --------------------------------------------------------------------------

  @ParameterizedTest(name = "{1}.{2} {0} -> {3} = {4} units {5} nanos")
  @CsvSource({
    // same currency
    "EUR, 10, 500000000, EUR, 10, 500000000",
    "EUR, 0, 50000000, EUR, 0, 500000000",
    "EUR, 1, 999999999, EUR, 1, 999999998",
    "USD, 19, 990000000, USD, 19, 989999999",
    "EUR, 0, 2147483647, EUR, 0, 214748364",
    "EUR, 0, -2147483648, EUR, 0, -214748364",
    // regular conversions
    "EUR, 1, 0, USD, 1, 130500000",
    "USD, 1, 0, EUR, 0, 884564352",
    "USD, 100, 0, JPY, 11180, 893409995",
    "USD, 1, 990000000, CAD, 2, 662956214",
    "GBP, 0, 5, EUR, 0, 581598231",
    "EUR, 3, 100000000, JPY, 391, 840000000",
    "HUF, 12345, 670000000, CNY, 296, 822759719",
    "CHF, 7, 999999999, INR, 559, 380281620",
    "USD, 123456789, 123456789, IDR, 1747222071562, 878662109",
    // zero
    "EUR, 0, 0, USD, 0, 0",
    // negative values: truncation is toward zero, remainder keeps the sign
    "USD, -5, 0, EUR, -4, -422821760",
    "USD, -5, -500000000, EUR, -4, -865103936",
    "USD, -5, 500000000, EUR, -3, -980539584",
  })
  void convertMatchesTheOriginalArithmetic(
      String from, long units, int nanos, String to, long expectedUnits, int expectedNanos) {
    Money result = converter.convert(money(from, units, nanos), to);

    assertThat(result.getCurrencyCode()).isEqualTo(to);
    assertThat(result.getUnits()).isEqualTo(expectedUnits);
    assertThat(result.getNanos()).isEqualTo(expectedNanos);
  }

  @Test
  void nanosAreTruncatedNotRoundedAndNeverCarryIntoUnits() {
    // 1.999999999 EUR -> EUR: the double is 1.99999999899999991..., times 1e9 truncates to
    // 999999998 nanos. A rounding implementation would give 999999999 or carry into 2 units.
    Money result = converter.convert(money("EUR", 1, 999999999), "EUR");

    assertThat(result.getUnits()).isEqualTo(1);
    assertThat(result.getNanos()).isEqualTo(999999998);
  }

  @Test
  void nanosStayWithinPlusMinusOneBillion() {
    for (String to : converter.supportedCurrencies()) {
      Money result = converter.convert(money("USD", 12345, 670000000), to);
      assertThat(Math.abs(result.getNanos())).isLessThan(1_000_000_000);
      assertThat(result.getCurrencyCode()).isEqualTo(to);
    }
  }

  @Test
  void convertingToTheTargetCurrencyEchoesTheRequestedCodeUnvalidated() {
    // The response currency code is the requested one even when it is not in the table.
    Money result = converter.convert(money("EUR", 1, 0), "XXX");

    assertThat(result.getCurrencyCode()).isEqualTo("XXX");
  }

  // ---- unsupported currencies: no error status in the original, rate 0.0 semantics -----------

  @Test
  void unknownTargetCurrencyYieldsZero() {
    Money result = converter.convert(money("EUR", 1, 0), "XXX");

    assertThat(result.getUnits()).isZero();
    assertThat(result.getNanos()).isZero();
  }

  @Test
  void unknownSourceCurrencyDividesByZeroAndNarrowsLikeX86() {
    // 1.0 / 0.0 = +inf, inf * 1.0 = inf; (long) inf == LONG_MIN on x86-64, remainder is inf, whose
    // nano value narrows to LONG_MIN and then to 0 as int32.
    Money result = converter.convert(money("XXX", 1, 0), "EUR");

    assertThat(result.getCurrencyCode()).isEqualTo("EUR");
    assertThat(result.getUnits()).isEqualTo(Long.MIN_VALUE);
    assertThat(result.getNanos()).isZero();
  }

  @Test
  void unknownSourceAndTargetGiveNaNNarrowedToLongMin() {
    Money result = converter.convert(money("XXX", 1, 0), "XXX");

    assertThat(result.getUnits()).isEqualTo(Long.MIN_VALUE);
    assertThat(result.getNanos()).isZero();
  }

  @Test
  void zeroAmountOfUnknownSourceCurrencyIsNaN() {
    Money result = converter.convert(money("XXX", 0, 0), "EUR");

    assertThat(result.getUnits()).isEqualTo(Long.MIN_VALUE);
    assertThat(result.getNanos()).isZero();
  }

  @Test
  void resultBeyondTheInt64RangeNarrowsLikeX86() {
    // 9223372036854775807 USD in KRW is about 1.04e22, outside the int64 range.
    Money result = converter.convert(money("USD", Long.MAX_VALUE, 0), "KRW");

    assertThat(result.getUnits()).isEqualTo(Long.MIN_VALUE);
    assertThat(result.getNanos()).isZero();
  }

  // ---- (long) cast emulation --------------------------------------------------------------

  @Test
  void cppLongTruncatesTowardZeroAndUsesTheIndefiniteValueOutOfRange() {
    assertThat(CurrencyConverter.cppLong(2.9)).isEqualTo(2L);
    assertThat(CurrencyConverter.cppLong(-2.9)).isEqualTo(-2L);
    assertThat(CurrencyConverter.cppLong(Double.NaN)).isEqualTo(Long.MIN_VALUE);
    assertThat(CurrencyConverter.cppLong(Double.POSITIVE_INFINITY)).isEqualTo(Long.MIN_VALUE);
    assertThat(CurrencyConverter.cppLong(Double.NEGATIVE_INFINITY)).isEqualTo(Long.MIN_VALUE);
    assertThat(CurrencyConverter.cppLong(0x1p63)).isEqualTo(Long.MIN_VALUE);
    assertThat(CurrencyConverter.cppLong(-0x1p63)).isEqualTo(Long.MIN_VALUE);
    assertThat(CurrencyConverter.cppLong(0x1p62)).isEqualTo(1L << 62);
  }
}
