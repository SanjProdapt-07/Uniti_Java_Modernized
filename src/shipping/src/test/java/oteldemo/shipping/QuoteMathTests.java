/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import oteldemo.shipping.ShippingTypes.Quote;

/** Ports of the unit tests in the Rust quote.rs plus the edge cases of its float handling. */
class QuoteMathTests {

  @Test
  void createQuoteFromFloat() {
    assertQuote(QuoteClient.createQuoteFromFloat(10.99), 10, 99);
    assertQuote(QuoteClient.createQuoteFromFloat(8.99), 8, 99);
    assertQuote(QuoteClient.createQuoteFromFloat(35.96), 35, 96);
    assertQuote(QuoteClient.createQuoteFromFloat(71.92), 71, 92);
    assertQuote(QuoteClient.createQuoteFromFloat(0.01), 0, 1);
    assertQuote(QuoteClient.createQuoteFromFloat(100.00), 100, 0);
  }

  @Test
  void quoteDisplay() {
    assertThat(new Quote(BigInteger.valueOf(10), 99).toString()).isEqualTo("10.99");
    assertThat(new Quote(BigInteger.ZERO, 1).toString()).isEqualTo("0.01");
    assertThat(new Quote(BigInteger.valueOf(10), 5).toString()).isEqualTo("10.05");
    assertThat(new Quote(BigInteger.valueOf(100), 0).toString()).isEqualTo("100.00");
  }

  @Test
  void roundsHalfAwayFromZeroLikeRust() {
    // 0.125 * 100 = 12.5 exactly -> 13
    assertQuote(QuoteClient.createQuoteFromFloat(0.125), 0, 13);
    assertQuote(QuoteClient.createQuoteFromFloat(0.004), 0, 0);
  }

  @Test
  void castToU64Saturates() {
    assertQuote(QuoteClient.createQuoteFromFloat(-5.0), 0, 0);
    assertQuote(QuoteClient.createQuoteFromFloat(Double.NaN), 0, 0);
    // u64::MAX = 18446744073709551615 cents
    Quote huge = QuoteClient.createQuoteFromFloat(Double.POSITIVE_INFINITY);
    assertThat(huge.dollars()).isEqualTo(new BigInteger("184467440737095516"));
    assertThat(huge.cents()).isEqualTo(15);
  }

  @ParameterizedTest
  @ValueSource(strings = {"10.99", "0", "5", "+5.5", "-1.5", ".5", "5.", "1e3", "1E-2", "inf", "-Infinity", "NaN"})
  void parsesWhatRustParses(String text) {
    // must not throw
    QuoteClient.parseF64(text);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " 1.0", "1.0 ", "1.0\n", "abc", "1,5", "0x10", "1d", "1f", "{\"a\":1}", "--1", "e5"})
  void rejectsWhatRustRejects(String text) {
    assertThatThrownBy(() -> QuoteClient.parseF64(text))
        .hasMessage("Failed to parse quote value as f64");
  }

  private static void assertQuote(Quote quote, long dollars, int cents) {
    assertThat(quote.dollars()).isEqualTo(BigInteger.valueOf(dollars));
    assertThat(quote.cents()).isEqualTo(cents);
  }
}
