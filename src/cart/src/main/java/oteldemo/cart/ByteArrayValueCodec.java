/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import io.lettuce.core.codec.RedisCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** UTF-8 string keys and raw byte array values, the protobuf encoded carts. */
final class ByteArrayValueCodec implements RedisCodec<String, byte[]> {

  static final ByteArrayValueCodec INSTANCE = new ByteArrayValueCodec();

  private static final byte[] EMPTY = new byte[0];

  private ByteArrayValueCodec() {}

  @Override
  public String decodeKey(ByteBuffer bytes) {
    return StandardCharsets.UTF_8.decode(bytes).toString();
  }

  @Override
  public byte[] decodeValue(ByteBuffer bytes) {
    byte[] value = new byte[bytes.remaining()];
    bytes.get(value);
    return value;
  }

  @Override
  public ByteBuffer encodeKey(String key) {
    return key == null ? ByteBuffer.wrap(EMPTY) : StandardCharsets.UTF_8.encode(key);
  }

  @Override
  public ByteBuffer encodeValue(byte[] value) {
    return ByteBuffer.wrap(value == null ? EMPTY : value);
  }
}
