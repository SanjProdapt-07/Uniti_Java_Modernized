/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import oteldemo.Demo.Cart;

/** Storage behind the cart service (the {@code ICartStore} of the original .NET service). */
public interface CartStore {

  void initialize();

  /**
   * Adds {@code quantity} of the product to the user's cart.
   *
   * @throws io.grpc.StatusRuntimeException with {@code FAILED_PRECONDITION} when the storage
   *     can't be accessed
   */
  void addItem(String userId, String productId, int quantity);

  /** Replaces the user's cart with an empty one. Same error contract as {@link #addItem}. */
  void emptyCart(String userId);

  /**
   * Returns the user's cart, or an empty cart for a user that is not in the store. Same error
   * contract as {@link #addItem}.
   */
  Cart getCart(String userId);

  boolean ping();
}
