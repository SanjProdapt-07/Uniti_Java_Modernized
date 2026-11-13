/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

/** Renders failures the way the original Go service did with the "%+v" and "%q" verbs. */
final class GoErrors {

  private GoErrors() {}

  /** Mirrors grpc-go's status error text: {@code rpc error: code = Unavailable desc = ...}. */
  static String describe(Throwable error) {
    Status status = null;
    if (error instanceof StatusRuntimeException sre) {
      status = sre.getStatus();
    } else if (error instanceof StatusException se) {
      status = se.getStatus();
    }
    if (status == null) {
      return String.valueOf(error.getMessage() != null ? error.getMessage() : error);
    }
    String description = status.getDescription() == null ? "" : status.getDescription();
    return "rpc error: code = " + goCodeName(status.getCode()) + " desc = " + description;
  }

  /** google.golang.org/grpc/codes.Code.String(). */
  static String goCodeName(Status.Code code) {
    return switch (code) {
      case OK -> "OK";
      case CANCELLED -> "Canceled";
      case UNKNOWN -> "Unknown";
      case INVALID_ARGUMENT -> "InvalidArgument";
      case DEADLINE_EXCEEDED -> "DeadlineExceeded";
      case NOT_FOUND -> "NotFound";
      case ALREADY_EXISTS -> "AlreadyExists";
      case PERMISSION_DENIED -> "PermissionDenied";
      case RESOURCE_EXHAUSTED -> "ResourceExhausted";
      case FAILED_PRECONDITION -> "FailedPrecondition";
      case ABORTED -> "Aborted";
      case OUT_OF_RANGE -> "OutOfRange";
      case UNIMPLEMENTED -> "Unimplemented";
      case INTERNAL -> "Internal";
      case UNAVAILABLE -> "Unavailable";
      case DATA_LOSS -> "DataLoss";
      case UNAUTHENTICATED -> "Unauthenticated";
    };
  }

  /** Go's "%q" for a string (strconv.Quote for the ASCII range; other runes pass through). */
  static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == 0x7f) {
            out.append(String.format("\\x%02x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
