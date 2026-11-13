/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import oteldemo.Demo.Money;

/** Port of the Go {@code money} package: arithmetic on the units/nanos {@link Money} message. */
public final class MoneyOps {

  private static final int NANOS_MIN = -999_999_999;
  private static final int NANOS_MAX = 999_999_999;
  private static final int NANOS_MOD = 1_000_000_000;

  public static final String ERR_INVALID_VALUE = "one of the specified money values is invalid";
  public static final String ERR_MISMATCHING_CURRENCY = "mismatching currency codes";

  private MoneyOps() {}

  /** Failure of a money operation (ErrInvalidValue / ErrMismatchingCurrency in the Go package). */
  public static final class MoneyException extends RuntimeException {
    public MoneyException(String message) {
      super(message);
    }
  }

  /** Checks if the value has valid units/nanos signs and ranges. */
  public static boolean isValid(Money m) {
    return signMatches(m) && validNanos(m.getNanos());
  }

  private static boolean signMatches(Money m) {
    return m.getNanos() == 0
        || m.getUnits() == 0
        || (m.getNanos() < 0) == (m.getUnits() < 0);
  }

  private static boolean validNanos(int nanos) {
    return NANOS_MIN <= nanos && nanos <= NANOS_MAX;
  }

  /** True if the value is equal to zero. */
  public static boolean isZero(Money m) {
    return m.getUnits() == 0 && m.getNanos() == 0;
  }

  /** True if the value is valid and positive (same operator precedence as the Go original). */
  public static boolean isPositive(Money m) {
    return isValid(m) && m.getUnits() > 0 || (m.getUnits() == 0 && m.getNanos() > 0);
  }

  /** True if the value is valid and negative (same operator precedence as the Go original). */
  public static boolean isNegative(Money m) {
    return isValid(m) && m.getUnits() < 0 || (m.getUnits() == 0 && m.getNanos() < 0);
  }

  /** True if both values have a currency code and it is the same one. */
  public static boolean areSameCurrency(Money l, Money r) {
    return l.getCurrencyCode().equals(r.getCurrencyCode()) && !l.getCurrencyCode().isEmpty();
  }

  /** True if both values are equal, including the currency. Validity is not checked. */
  public static boolean areEquals(Money l, Money r) {
    return l.getCurrencyCode().equals(r.getCurrencyCode())
        && l.getUnits() == r.getUnits()
        && l.getNanos() == r.getNanos();
  }

  /** Same amount with the sign negated. */
  public static Money negate(Money m) {
    return Money.newBuilder()
        .setUnits(-m.getUnits())
        .setNanos(-m.getNanos())
        .setCurrencyCode(m.getCurrencyCode())
        .build();
  }

  /** Throws if the error is not null; the Go {@code Must(Sum(a, b))} idiom (Go panics). */
  public static Money must(Money value, MoneyException error) {
    if (error != null) {
      throw error;
    }
    return value;
  }

  /**
   * Adds two values. Throws {@link MoneyException} if one of the values is invalid or the
   * currency codes do not match (unless the currency code is unspecified for both). This is the
   * counterpart of {@code money.Must(money.Sum(l, r))} in the original.
   */
  public static Money sum(Money l, Money r) {
    if (!isValid(l) || !isValid(r)) {
      throw new MoneyException(ERR_INVALID_VALUE);
    } else if (!l.getCurrencyCode().equals(r.getCurrencyCode())) {
      throw new MoneyException(ERR_MISMATCHING_CURRENCY);
    }
    long units = l.getUnits() + r.getUnits();
    int nanos = l.getNanos() + r.getNanos();

    if ((units == 0 && nanos == 0) || (units > 0 && nanos >= 0) || (units < 0 && nanos <= 0)) {
      // same sign <units, nanos>
      units += nanos / NANOS_MOD;
      nanos = nanos % NANOS_MOD;
    } else {
      // different sign. nanos guaranteed to not to go over the limit
      if (units > 0) {
        units--;
        nanos += NANOS_MOD;
      } else {
        units++;
        nanos -= NANOS_MOD;
      }
    }

    return Money.newBuilder()
        .setUnits(units)
        .setNanos(nanos)
        .setCurrencyCode(l.getCurrencyCode())
        .build();
  }

  /**
   * Multiplication done through adding the value to itself n-1 times. {@code n} is the unsigned
   * 32-bit count of the Go original ({@code uint32}).
   */
  public static Money multiplySlow(Money m, int n) {
    Money out = m;
    long remaining = Integer.toUnsignedLong(n);
    while (remaining > 1) {
      out = sum(out, m);
      remaining--;
    }
    return out;
  }
}
