/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import io.grpc.Status;
import java.util.concurrent.atomic.AtomicInteger;
import oteldemo.Demo.Cart;

/** A store whose storage is never reachable, like the one behind {@code badhost:1234}. */
class FailingCartStore implements CartStore {

  final AtomicInteger calls = new AtomicInteger();

  @Override
  public void initialize() {}

  @Override
  public void addItem(String userId, String productId, int quantity) {
    throw failure();
  }

  @Override
  public void emptyCart(String userId) {
    throw failure();
  }

  @Override
  public Cart getCart(String userId) {
    throw failure();
  }

  @Override
  public boolean ping() {
    return false;
  }

  private io.grpc.StatusRuntimeException failure() {
    calls.incrementAndGet();
    return Status.FAILED_PRECONDITION
        .withDescription("Can't access cart storage. test failure")
        .asRuntimeException();
  }
}
