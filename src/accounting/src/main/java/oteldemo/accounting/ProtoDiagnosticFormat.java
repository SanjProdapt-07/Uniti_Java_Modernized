/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

/**
 * Renders a protobuf message the way {@code Google.Protobuf}'s {@code ToString()} (the .NET
 * "diagnostic string") does: single line JSON, with a space inside braces/brackets and after
 * colons and commas, for example {@code { "orderId": "abc", "items": [ { "cost": { ... } } ] }}.
 */
final class ProtoDiagnosticFormat {

  private ProtoDiagnosticFormat() {}

  static String toDiagnosticString(MessageOrBuilder message) {
    String compact;
    try {
      compact = JsonFormat.printer().omittingInsignificantWhitespace().print(message);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
    return spaced(compact);
  }

  /** Adds the diagnostic format's spacing to compact JSON, leaving string contents untouched. */
  static String spaced(String compact) {
    StringBuilder out = new StringBuilder(compact.length() + compact.length() / 4);
    boolean inString = false;
    for (int i = 0; i < compact.length(); i++) {
      char c = compact.charAt(i);
      if (inString) {
        out.append(c);
        if (c == '\\' && i + 1 < compact.length()) {
          out.append(compact.charAt(++i));
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }
      switch (c) {
        case '"' -> {
          inString = true;
          out.append(c);
        }
        case '{', '[' -> {
          out.append(c).append(' ');
        }
        case '}', ']' -> {
          // "{}" and "[]" become "{ }" and "[ ]"
          if (out.length() == 0 || out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
          }
          out.append(c);
        }
        case ':' -> out.append(": ");
        case ',' -> out.append(", ");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
