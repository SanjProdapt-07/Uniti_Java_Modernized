/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.quote;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * The few PHP runtime semantics the quote calculation depends on: {@code intval()}, {@code
 * round($x, 2)} and {@code json_encode()} of a float. They are reproduced so that the HTTP
 * responses stay identical to the PHP service.
 */
final class PhpCompat {

  private static final double LONG_UPPER_BOUND = 9.223372036854775807E18; // 2^63

  private PhpCompat() {}

  /**
   * PHP {@code intval($value)} for the values a decoded request body can contain: null, boolean,
   * long, double, numeric-or-not string, list or map (array).
   */
  static long intval(Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Long l) {
      return l;
    }
    if (value instanceof Integer i) {
      return i;
    }
    if (value instanceof Boolean b) {
      return b ? 1L : 0L;
    }
    if (value instanceof BigInteger big) {
      // json_decode() turns integers that overflow int64 into floats.
      return doubleToLong(big.doubleValue());
    }
    if (value instanceof Double d) {
      return doubleToLong(d);
    }
    if (value instanceof String s) {
      return stringToLong(s);
    }
    if (value instanceof List<?> list) {
      return list.isEmpty() ? 0L : 1L;
    }
    if (value instanceof Map<?, ?> map) {
      return map.isEmpty() ? 0L : 1L;
    }
    return 1L;
  }

  /** zend_dval_to_lval: NaN/Infinity give 0, out of range values wrap modulo 2^64. */
  static long doubleToLong(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return 0L;
    }
    if (d >= -LONG_UPPER_BOUND && d < LONG_UPPER_BOUND) {
      return (long) d;
    }
    BigInteger modulus = BigInteger.ONE.shiftLeft(64);
    return new BigDecimal(d).toBigInteger().mod(modulus).longValue();
  }

  /**
   * PHP converts a string with its leading numeric prefix (after optional leading whitespace). An
   * integer-looking prefix saturates at the int64 range, a float-looking one (decimal point or
   * exponent) goes through the float conversion.
   */
  static long stringToLong(String s) {
    int i = 0;
    int n = s.length();
    while (i < n && isPhpWhitespace(s.charAt(i))) {
      i++;
    }
    int start = i;
    if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
      i++;
    }
    int digitsStart = i;
    while (i < n && isDigit(s.charAt(i))) {
      i++;
    }
    int intDigits = i - digitsStart;
    boolean isFloat = false;
    int end = i;
    if (i < n && s.charAt(i) == '.') {
      int j = i + 1;
      while (j < n && isDigit(s.charAt(j))) {
        j++;
      }
      if (intDigits > 0 || j > i + 1) {
        isFloat = true;
        end = j;
        i = j;
      }
    }
    if (intDigits == 0 && !isFloat) {
      return 0L;
    }
    if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      int j = i + 1;
      if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
        j++;
      }
      int expStart = j;
      while (j < n && isDigit(s.charAt(j))) {
        j++;
      }
      if (j > expStart) {
        isFloat = true;
        end = j;
      }
    }
    String number = s.substring(start, end);
    if (isFloat) {
      double d = Double.parseDouble(number);
      if (Double.isNaN(d)) {
        return 0L;
      }
      if (d >= LONG_UPPER_BOUND) {
        return Long.MAX_VALUE;
      }
      if (d <= -LONG_UPPER_BOUND) {
        return Long.MIN_VALUE;
      }
      return (long) d;
    }
    BigInteger big = new BigInteger(number.startsWith("+") ? number.substring(1) : number);
    if (big.bitLength() > 63) {
      return big.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
    return big.longValue();
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isPhpWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == '\f';
  }

  /** PHP {@code round($value, $places)} with the default PHP_ROUND_HALF_UP mode. */
  static double round(double value, int places) {
    if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) {
      return value;
    }
    double factor = Math.pow(10, Math.abs(places));
    double scaled = places >= 0 ? value * factor : value / factor;
    if (Double.isInfinite(scaled) || Math.abs(scaled) >= 1e15) {
      // beyond double precision, rounding is pointless
      return value;
    }
    double rounded = scaled >= 0.0 ? Math.floor(scaled + 0.5) : Math.ceil(scaled - 0.5);
    return places >= 0 ? rounded / factor : rounded * factor;
  }

  /**
   * PHP {@code json_encode($float)} with the default {@code serialize_precision = -1}: the shortest
   * round-trip digits, no ".0" suffix for whole numbers, exponent notation (for example {@code
   * 1.0e+25}) from 1e17 upwards.
   */
  static String jsonEncode(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("Inf and NaN cannot be JSON encoded");
    }
    if (d == 0.0) {
      return (1 / d < 0) ? "-0" : "0";
    }
    StringBuilder out = new StringBuilder();
    if (d < 0) {
      out.append('-');
    }
    // Java's Double.toString yields the shortest digits that round-trip (JDK 19+).
    BigDecimal decimal = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
    String digits = decimal.unscaledValue().toString();
    int decpt = digits.length() - decimal.scale();

    if (decpt < 0 ? decpt < -3 : decpt > 17) {
      int exponent = decpt - 1;
      out.append(digits.charAt(0)).append('.');
      out.append(digits.length() == 1 ? "0" : digits.substring(1));
      out.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
    } else if (decpt <= 0) {
      out.append("0.");
      out.append("0".repeat(-decpt));
      out.append(digits);
    } else if (digits.length() <= decpt) {
      out.append(digits);
      out.append("0".repeat(decpt - digits.length()));
    } else {
      out.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length());
    }
    return out.toString();
  }
}
