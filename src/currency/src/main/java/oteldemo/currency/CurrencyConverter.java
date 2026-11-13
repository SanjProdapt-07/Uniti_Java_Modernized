/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import oteldemo.Demo.Money;
import org.springframework.stereotype.Component;

/**
 * Currency rates (relative to EUR) and the conversion arithmetic of the original C++ service
 * ({@code server.cpp}). The arithmetic is intentionally reproduced as-is, quirks included, so the
 * results match the original value for value:
 *
 * <ul>
 *   <li>{@link #toDouble(Money)} rebuilds the fraction by walking the decimal digits of
 *       {@code nanos} from the least significant digit, so it does NOT left-pad: nanos = 5 is read
 *       as 0.5, nanos = 50_000_000 is read as 0.5 too. Negative nanos give negative digits.
 *   <li>{@link #toUnitsAndNanos(double)} truncates toward zero, there is no carry handling and no
 *       rounding; the fraction is multiplied by 1e9 and truncated.
 *   <li>A currency code that is not in the table has rate 0.0 (C++ {@code operator[]} default),
 *       there is no error status. The resulting infinities / NaN are narrowed the way x86-64 does
 *       it, see {@link #cppLong(double)}.
 * </ul>
 */
@Component
public class CurrencyConverter {

  // Insertion order follows server.cpp; std::unordered_map had no defined order.
  private static final Map<String, Double> RATES = rates();

  private static Map<String, Double> rates() {
    Map<String, Double> rates = new LinkedHashMap<>();
    rates.put("EUR", 1.0);
    rates.put("USD", 1.1305);
    rates.put("JPY", 126.40);
    rates.put("BGN", 1.9558);
    rates.put("CZK", 25.592);
    rates.put("DKK", 7.4609);
    rates.put("GBP", 0.85970);
    rates.put("HUF", 315.51);
    rates.put("PLN", 4.2996);
    rates.put("RON", 4.7463);
    rates.put("SEK", 10.5375);
    rates.put("CHF", 1.1360);
    rates.put("ISK", 136.80);
    rates.put("NOK", 9.8040);
    rates.put("HRK", 7.4210);
    rates.put("RUB", 74.4208);
    rates.put("TRY", 6.1247);
    rates.put("AUD", 1.6072);
    rates.put("BRL", 4.2682);
    rates.put("CAD", 1.5128);
    rates.put("CNY", 7.5857);
    rates.put("HKD", 8.8743);
    rates.put("IDR", 15999.40);
    rates.put("ILS", 4.0875);
    rates.put("INR", 79.4320);
    rates.put("KRW", 1275.05);
    rates.put("MXN", 21.7999);
    rates.put("MYR", 4.6289);
    rates.put("NZD", 1.6679);
    rates.put("PHP", 59.083);
    rates.put("SGD", 1.5349);
    rates.put("THB", 36.012);
    rates.put("ZAR", 16.0583);
    return Collections.unmodifiableMap(rates);
  }

  public List<String> supportedCurrencies() {
    return List.copyOf(RATES.keySet());
  }

  /** Converts {@code from} into {@code toCode}; mirrors {@code CurrencyService::Convert}. */
  public Money convert(Money from, String toCode) {
    double rate = rate(from.getCurrencyCode());
    double oneEuro = toDouble(from) / rate;
    double toRate = rate(toCode);
    double result = oneEuro * toRate;
    return toMoney(result, toCode);
  }

  /** {@code currency_conversion[code]}: unknown codes read as 0.0. */
  static double rate(String code) {
    return RATES.getOrDefault(code, 0.0);
  }

  /** Mirrors {@code getDouble(Money&)}. */
  static double toDouble(Money money) {
    long units = money.getUnits();
    int nanos = money.getNanos();

    double decimal = 0.0;
    while (nanos != 0) {
      double t = (double) (nanos % 10) / 10;
      nanos = nanos / 10;
      decimal = decimal / 10 + t;
    }

    return (double) units + decimal;
  }

  /** Mirrors {@code getUnitsAndNanos(Money&, double)} plus {@code set_currency_code}. */
  static Money toMoney(double value, String currencyCode) {
    long unit = cppLong(value);
    double rem = value - unit;
    long nano = cppLong(rem * 1e9);
    return Money.newBuilder()
        .setCurrencyCode(currencyCode)
        .setUnits(unit)
        // set_nanos(int32): C++ narrows long to int by keeping the low 32 bits, as Java does.
        .setNanos((int) nano)
        .build();
  }

  /**
   * C++ {@code (long) double}. Truncates toward zero like Java, but for NaN and values outside the
   * range of a 64-bit integer x86-64 (cvttsd2si) yields the "integer indefinite" value
   * {@code Long.MIN_VALUE}, where Java saturates / yields 0.
   */
  static long cppLong(double value) {
    if (Double.isNaN(value) || value >= 0x1p63 || value < -0x1p63) {
      return Long.MIN_VALUE;
    }
    return (long) value;
  }
}
