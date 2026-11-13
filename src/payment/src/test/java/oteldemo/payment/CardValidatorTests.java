/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import oteldemo.payment.CardValidator.CardDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CardValidatorTests {

  @ParameterizedTest
  @CsvSource({
    "4111111111111111, visa",
    "4242424242424242, visa",
    "5555555555554444, mastercard",
    "5105105105105100, mastercard",
    "378282246310005, amex",
    "30569309025904, diners_club_carte_blanche",
    "38520000023237, diners_club_international",
    "3530111333300000, jcb",
    "4026000000000002, visa_electron",
    "6011111111111117, discover",
    "6759649826438453, maestro",
    "6304000000000000, laser",
  })
  void detectsTheCardTypeOfAValidNumber(String number, String expectedType) {
    assertThat(CardValidator.getCardDetails(number)).isEqualTo(new CardDetails(expectedType, true));
  }

  @Test
  void detectsRupayByBinRange() {
    // 508500 is the first BIN of the rupay range; 16 digits, valid checksum
    CardDetails details = CardValidator.getCardDetails("5085000000000009");
    assertThat(details.cardType()).isEqualTo("rupay");
  }

  @Test
  void ignoresSpacesAndDashes() {
    assertThat(CardValidator.getCardDetails("4111-1111 1111-1111"))
        .isEqualTo(new CardDetails("visa", true));
  }

  @Test
  void rejectsABadChecksum() {
    assertThat(CardValidator.getCardDetails("4111111111111112"))
        .isEqualTo(new CardDetails("visa", false));
  }

  @Test
  void rejectsAWrongLengthForTheType() {
    // valid checksum, but a visa needs 16 digits and an amex 15
    assertThat(CardValidator.getCardDetails("4222222222222"))
        .isEqualTo(new CardDetails("visa", false));
    assertThat(CardValidator.getCardDetails("37828224631000"))
        .isEqualTo(new CardDetails("amex", false));
  }

  @Test
  void reportsUnknownForUnrecognisedPrefixes() {
    assertThat(CardValidator.getCardDetails("1234567812345670"))
        .isEqualTo(new CardDetails("unknown", false));
    assertThat(CardValidator.getCardDetails("abcdefghijklmnop"))
        .isEqualTo(new CardDetails("unknown", false));
  }

  @Test
  void anAlphabeticBinStopsTheSearchBeforeDiscover() {
    // the library gives up as soon as the rupay BIN is not a number
    assertThat(CardValidator.getCardDetails("6011abcdefghijkl").cardType()).isEqualTo("unknown");
  }

  @Test
  void rejectsAnEmptyNumber() {
    assertThatThrownBy(() -> CardValidator.getCardDetails(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid cardNumber property set");
  }
}
