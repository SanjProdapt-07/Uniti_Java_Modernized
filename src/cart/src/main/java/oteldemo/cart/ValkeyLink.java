/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import io.lettuce.core.api.sync.RedisCommands;

/** One open connection to Valkey, with the connection state notifications the store reacts to. */
public interface ValkeyLink extends AutoCloseable {

  boolean isOpen();

  RedisCommands<String, byte[]> commands();

  void setStateListener(StateListener listener);

  @Override
  void close();

  interface StateListener {
    void onRestored();

    void onLost();

    void onInternalError(Throwable error);
  }
}
