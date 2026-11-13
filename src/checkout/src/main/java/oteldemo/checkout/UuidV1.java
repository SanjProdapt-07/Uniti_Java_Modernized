/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Time-based (version 1) UUIDs. The Go service creates order ids with {@code uuid.NewUUID()},
 * which is a version 1 UUID, and the JDK only ships version 3 and 4 generators.
 */
final class UuidV1 {

  /** 100ns intervals between 1582-10-15 (Gregorian epoch) and 1970-01-01. */
  private static final long GREGORIAN_OFFSET = 122_192_928_000_000_000L;

  private static final SecureRandom RANDOM = new SecureRandom();
  // Random node id with the multicast bit set (RFC 4122 section 4.5), like google/uuid does
  // when no hardware address is available.
  private static final long NODE = (RANDOM.nextLong() & 0x0000_FFFF_FFFF_FFFFL) | 0x0000_0100_0000_0000L;
  private static final long CLOCK_SEQUENCE = RANDOM.nextInt(0x4000);

  private static long lastTimestamp;

  private UuidV1() {}

  static synchronized UUID next() {
    Instant now = Instant.now();
    long timestamp = now.getEpochSecond() * 10_000_000L + now.getNano() / 100 + GREGORIAN_OFFSET;
    if (timestamp <= lastTimestamp) {
      timestamp = lastTimestamp + 1;
    }
    lastTimestamp = timestamp;

    long timeLow = timestamp & 0xFFFF_FFFFL;
    long timeMid = (timestamp >>> 32) & 0xFFFFL;
    long timeHigh = (timestamp >>> 48) & 0x0FFFL;
    long mostSignificant = (timeLow << 32) | (timeMid << 16) | 0x1000L | timeHigh;
    long leastSignificant = ((0x8000L | CLOCK_SEQUENCE) << 48) | NODE;
    return new UUID(mostSignificant, leastSignificant);
  }
}
