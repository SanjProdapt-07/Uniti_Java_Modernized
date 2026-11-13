/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;

/** The in-memory {@link CartStore} of the original xunit tests. */
class InMemoryCartStore implements CartStore {

  private final ConcurrentMap<String, Cart> carts = new ConcurrentHashMap<>();

  /** While true every operation fails like an unreachable storage does. */
  volatile boolean down;

  private void checkUp() {
    if (down) {
      throw io.grpc.Status.FAILED_PRECONDITION
          .withDescription("Can't access cart storage. storage down")
          .asRuntimeException();
    }
  }

  @Override
  public void initialize() {}

  @Override
  public void addItem(String userId, String productId, int quantity) {
    checkUp();
    carts.compute(
        userId,
        (id, cart) -> {
          if (cart == null) {
            return Cart.newBuilder()
                .setUserId(userId)
                .addItems(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
                .build();
          }
          for (int i = 0; i < cart.getItemsCount(); i++) {
            CartItem item = cart.getItems(i);
            if (item.getProductId().equals(productId)) {
              return cart.toBuilder()
                  .setItems(i, item.toBuilder().setQuantity(item.getQuantity() + quantity))
                  .build();
            }
          }
          return cart.toBuilder()
              .addItems(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
              .build();
        });
  }

  @Override
  public void emptyCart(String userId) {
    checkUp();
    carts.put(userId, Cart.getDefaultInstance());
  }

  @Override
  public Cart getCart(String userId) {
    checkUp();
    return carts.getOrDefault(userId, Cart.getDefaultInstance());
  }

  @Override
  public boolean ping() {
    return true;
  }
}
