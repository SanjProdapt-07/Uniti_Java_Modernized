/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.payment;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Port of the card number checks of {@code simple-card-validator} 1.1.0 (the library the Node.js
 * payment service used): card type detection by number prefix, Luhn checksum and per-type length.
 * Card types are tried in the library's order and the first match wins.
 */
final class CardValidator {

  /** Result of {@link #getCardDetails(String)}. */
  record CardDetails(String cardType, boolean valid) {}

  private record CardType(String name, Pattern pattern, int[][] ranges, List<Integer> validLengths) {}

  private static final List<CardType> CARD_TYPES =
      List.of(
          pattern("amex", "^3[47]", 15),
          pattern("diners_club_carte_blanche", "^30[0-5]", 14),
          pattern("diners_club_international", "^3([689]|09)", 14),
          pattern("jcb", "^35(2[89]|[3-8][0-9])", 16),
          pattern("laser", "^(6304|670[69]|6771)", 16, 17, 18, 19),
          pattern("visa_electron", "^(4026|417500|4508|4844|491(3|7))", 16),
          pattern("visa", "^4", 16),
          pattern("mastercard", "^5[1-5]", 16),
          pattern(
              "maestro",
              "^(5018|5081|5044|5020|5038|603845|6304|6759|676[1-3]|6220|504834|504817|504645)",
              12, 13, 14, 15, 16, 17, 18, 19),
          new CardType(
              "rupay",
              null,
              new int[][] {
                {508500, 508999},
                {606985, 607384},
                {607385, 607484},
                {607485, 607984},
                {608001, 608100},
                {608101, 608200},
                {608201, 608300},
                {608301, 608350},
                {608351, 608500},
                {652150, 652849},
                {652850, 653049},
                {653050, 653149},
                {817200, 819899},
                {819900, 820199}
              },
              List.of(16)),
          pattern(
              "discover",
              "^(6011|622(12[6-9]|1[3-9][0-9]|[2-8][0-9]{2}|9[0-1][0-9]|92[0-5]|64[4-9])|65)",
              16));

  private static final Pattern JS_NUMBER =
      Pattern.compile(
          "^[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)$|^0[xX][0-9a-fA-F]+$|^0[bB][01]+$|^0[oO][0-7]+$");
  private static final Pattern LEADING_INTEGER = Pattern.compile("^[+-]?\\d+");

  private CardValidator() {}

  private static CardType pattern(String name, String regex, Integer... lengths) {
    return new CardType(name, Pattern.compile(regex), null, List.of(lengths));
  }

  /** Equivalent of {@code cardValidator(number).getCardDetails()} for a non-empty number. */
  static CardDetails getCardDetails(String cardNumber) {
    if (cardNumber == null || cardNumber.isEmpty()) {
      throw new IllegalArgumentException("Invalid cardNumber property set");
    }
    String number = cardNumber.replaceAll("[ -]", "");
    CardType card = getCardType(number);
    if (card == null) {
      return new CardDetails("unknown", false);
    }
    return new CardDetails(
        card.name(), isValidLuhn(number) && card.validLengths().contains(number.length()));
  }

  private static CardType getCardType(String number) {
    for (CardType type : CARD_TYPES) {
      if (type.pattern() != null) {
        if (type.pattern().matcher(number).find()) {
          return type;
        }
      } else {
        String binText = number.length() > 6 ? number.substring(0, 6) : number;
        if (jsIsNaN(binText)) {
          // the library gives up on every remaining card type here
          return null;
        }
        long bin = jsParseInt(binText);
        for (int[] range : type.ranges()) {
          if (range[0] <= bin && bin <= range[1]) {
            return type;
          }
        }
      }
    }
    return null;
  }

  private static boolean isValidLuhn(String number) {
    long sum = 0;
    for (int i = 0; i < number.length(); i++) {
      char c = number.charAt(number.length() - 1 - i);
      int digit;
      if (c >= '0' && c <= '9') {
        digit = c - '0';
      } else if (Character.isWhitespace(c)) {
        digit = 0; // JavaScript's unary plus turns a whitespace-only string into 0
      } else {
        return false; // NaN poisons the sum
      }
      if (i % 2 == 1) {
        digit *= 2;
        sum += digit < 10 ? digit : digit - 9;
      } else {
        sum += digit;
      }
    }
    return sum % 10 == 0;
  }

  /** JavaScript {@code isNaN(string)} for the strings a six character BIN can be. */
  private static boolean jsIsNaN(String text) {
    String trimmed = text.strip();
    return !trimmed.isEmpty() && !JS_NUMBER.matcher(trimmed).matches();
  }

  /** JavaScript {@code Number.parseInt(string)} for the same strings; NaN compares false. */
  private static long jsParseInt(String text) {
    String trimmed = text.strip();
    if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
      try {
        return Long.parseLong(trimmed.substring(2), 16);
      } catch (NumberFormatException e) {
        return Long.MIN_VALUE;
      }
    }
    var matcher = LEADING_INTEGER.matcher(trimmed);
    return matcher.find() ? Long.parseLong(matcher.group()) : Long.MIN_VALUE;
  }
}
