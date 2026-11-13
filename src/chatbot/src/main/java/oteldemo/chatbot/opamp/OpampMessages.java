/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot.opamp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;

/**
 * OpAMP {@code AgentToServer} messages, hand-encoded in protobuf wire format (field numbers from
 * opamp.proto). Only the fields the original Python client ({@code HealthReportingOpAMPClient}) sent
 * are produced: instance_uid, sequence_num, agent_description, capabilities, health and
 * agent_disconnect.
 */
public final class OpampMessages {

  /** ReportsStatus | ReportsHealth | ReportsHeartbeat (opamp.proto AgentCapabilities bits). */
  public static final long CAPABILITIES = 0x1L | 0x800L | 0x2000L;

  /** ServerToAgentFlags_ReportFullState. */
  public static final long FLAG_REPORT_FULL_STATE = 1L;

  private static final SecureRandom RANDOM = new SecureRandom();

  private OpampMessages() {}

  /** A 16 byte UUIDv7 (48-bit unix millis, version 7, random remainder). */
  public static byte[] uuid7(long unixMillis) {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    b[0] = (byte) (unixMillis >>> 40);
    b[1] = (byte) (unixMillis >>> 32);
    b[2] = (byte) (unixMillis >>> 24);
    b[3] = (byte) (unixMillis >>> 16);
    b[4] = (byte) (unixMillis >>> 8);
    b[5] = (byte) unixMillis;
    b[6] = (byte) ((b[6] & 0x0f) | 0x70);
    b[8] = (byte) ((b[8] & 0x3f) | 0x80);
    return b;
  }

  /** {@code AgentDescription} with identifying and non-identifying attributes. */
  public static byte[] agentDescription(
      Map<String, Object> identifying, Map<String, Object> nonIdentifying) {
    Writer w = new Writer();
    identifying.forEach((k, v) -> w.message(1, keyValue(k, v)));
    nonIdentifying.forEach((k, v) -> w.message(2, keyValue(k, v)));
    return w.bytes();
  }

  private static byte[] keyValue(String key, Object value) {
    Writer w = new Writer();
    w.string(1, key);
    w.message(2, anyValue(value));
    return w.bytes();
  }

  private static byte[] anyValue(Object value) {
    Writer w = new Writer();
    if (value == null) {
      return w.bytes();
    }
    if (value instanceof Boolean b) {
      w.tag(2, 0);
      w.varint(b ? 1 : 0);
    } else if (value instanceof Double || value instanceof Float) {
      w.tag(4, 1);
      w.fixed64(Double.doubleToRawLongBits(((Number) value).doubleValue()));
    } else if (value instanceof Number n) {
      w.tag(3, 0);
      w.varint(n.longValue());
    } else if (value instanceof byte[] bytes) {
      w.tag(7, 2);
      w.varint(bytes.length);
      w.raw(bytes);
    } else {
      // oneof members are serialized even when empty
      byte[] s = value.toString().getBytes(StandardCharsets.UTF_8);
      w.tag(1, 2);
      w.varint(s.length);
      w.raw(s);
    }
    return w.bytes();
  }

  /** {@code ComponentHealth}: healthy=true with start time and "OK" status. */
  public static byte[] health(long startTimeUnixNano, long statusTimeUnixNano) {
    Writer w = new Writer();
    w.tag(1, 0); // healthy
    w.varint(1);
    w.tag(2, 1); // start_time_unix_nano (fixed64)
    w.fixed64(startTimeUnixNano);
    w.string(4, "OK"); // status
    w.tag(5, 1); // status_time_unix_nano (fixed64)
    w.fixed64(statusTimeUnixNano);
    return w.bytes();
  }

  /** Full state report: sent when connecting and when the server sets ReportFullState. */
  public static byte[] fullState(
      byte[] instanceUid, long sequenceNum, byte[] agentDescription, byte[] health) {
    Writer w = new Writer();
    w.bytes(1, instanceUid);
    w.uint64(2, sequenceNum);
    w.message(3, agentDescription);
    w.uint64(4, CAPABILITIES);
    w.message(5, health);
    return w.bytes();
  }

  /** Heartbeat sent every 30 seconds. */
  public static byte[] heartbeat(byte[] instanceUid, long sequenceNum, byte[] health) {
    Writer w = new Writer();
    w.bytes(1, instanceUid);
    w.uint64(2, sequenceNum);
    w.uint64(4, CAPABILITIES);
    w.message(5, health);
    return w.bytes();
  }

  /** Sent on shutdown so the server can free the agent's resources. */
  public static byte[] disconnect(byte[] instanceUid, long sequenceNum) {
    Writer w = new Writer();
    w.bytes(1, instanceUid);
    w.uint64(2, sequenceNum);
    w.uint64(4, CAPABILITIES);
    w.message(9, new byte[0]); // agent_disconnect
    return w.bytes();
  }

  /** The parts of {@code ServerToAgent} the agent reacts to. */
  public record ServerToAgent(boolean hasErrorResponse, long flags) {}

  /** Parses {@code ServerToAgent}, keeping error_response presence (field 2) and flags (field 6). */
  public static ServerToAgent parseServerToAgent(byte[] data) {
    boolean error = false;
    long flags = 0;
    int pos = 0;
    while (pos < data.length) {
      long[] tagRead = readVarint(data, pos);
      long tag = tagRead[0];
      pos = (int) tagRead[1];
      int field = (int) (tag >>> 3);
      int wire = (int) (tag & 7);
      switch (wire) {
        case 0 -> {
          long[] v = readVarint(data, pos);
          pos = (int) v[1];
          if (field == 6) {
            flags = v[0];
          }
        }
        case 1 -> pos += 8;
        case 2 -> {
          long[] len = readVarint(data, pos);
          pos = (int) len[1];
          if (field == 2) {
            error = true;
          }
          pos += (int) len[0];
        }
        case 5 -> pos += 4;
        default -> throw new IllegalArgumentException("Unsupported protobuf wire type " + wire);
      }
    }
    return new ServerToAgent(error, flags);
  }

  private static long[] readVarint(byte[] data, int pos) {
    long result = 0;
    int shift = 0;
    while (true) {
      if (pos >= data.length) {
        throw new IllegalArgumentException("Truncated protobuf varint");
      }
      byte b = data[pos++];
      result |= (long) (b & 0x7f) << shift;
      if ((b & 0x80) == 0) {
        return new long[] {result, pos};
      }
      shift += 7;
    }
  }

  /** Minimal protobuf writer; proto3 scalars equal to their default are omitted by the callers. */
  private static final class Writer {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    byte[] bytes() {
      return out.toByteArray();
    }

    void raw(byte[] b) {
      out.write(b, 0, b.length);
    }

    void tag(int field, int wire) {
      varint(((long) field << 3) | wire);
    }

    void varint(long v) {
      while ((v & ~0x7fL) != 0) {
        out.write((int) ((v & 0x7f) | 0x80));
        v >>>= 7;
      }
      out.write((int) v);
    }

    void fixed64(long v) {
      for (int i = 0; i < 8; i++) {
        out.write((int) (v & 0xff));
        v >>>= 8;
      }
    }

    void uint64(int field, long v) {
      if (v != 0) {
        tag(field, 0);
        varint(v);
      }
    }

    void bytes(int field, byte[] b) {
      if (b.length > 0) {
        tag(field, 2);
        varint(b.length);
        raw(b);
      }
    }

    void string(int field, String s) {
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      if (b.length > 0) {
        tag(field, 2);
        varint(b.length);
        raw(b);
      }
    }

    /** Embedded message: written even when empty (presence matters). */
    void message(int field, byte[] b) {
      tag(field, 2);
      varint(b.length);
      raw(b);
    }
  }
}
