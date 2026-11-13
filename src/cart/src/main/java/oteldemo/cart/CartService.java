/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import dev.openfeature.sdk.Client;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import oteldemo.CartServiceGrpc;
import oteldemo.Demo.AddItemRequest;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.Empty;
import oteldemo.Demo.EmptyCartRequest;
import oteldemo.Demo.GetCartRequest;

@Service
public class CartService extends CartServiceGrpc.CartServiceImplBase {

  private static final Empty EMPTY = Empty.getDefaultInstance();

  private final CartStore cartStore;
  private final CartStore badCartStore;
  private final Client featureFlags;

  public CartService(
      @Qualifier("cartStore") CartStore cartStore,
      @Qualifier("badCartStore") CartStore badCartStore,
      Client featureFlags) {
    this.cartStore = cartStore;
    this.badCartStore = badCartStore;
    this.featureFlags = featureFlags;
  }

  @Override
  public void addItem(AddItemRequest request, StreamObserver<Empty> responseObserver) {
    Span span = Span.current();
    span.setAttribute("user.id", request.getUserId());
    span.setAttribute("demo.product.id", request.getItem().getProductId());
    span.setAttribute("demo.product.quantity", (long) request.getItem().getQuantity());

    try {
      cartStore.addItem(
          request.getUserId(), request.getItem().getProductId(), request.getItem().getQuantity());

      responseObserver.onNext(EMPTY);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException ex) {
      recordFailure(span, ex);
      responseObserver.onError(ex);
    }
  }

  @Override
  public void getCart(GetCartRequest request, StreamObserver<Cart> responseObserver) {
    Span span = Span.current();
    span.setAttribute("user.id", request.getUserId());
    span.addEvent("Fetch cart");

    try {
      Cart cart = cartStore.getCart(request.getUserId());
      int totalCart = 0;
      for (CartItem item : cart.getItemsList()) {
        totalCart += item.getQuantity();
      }
      span.setAttribute("demo.cart.items.count", (long) totalCart);

      responseObserver.onNext(cart);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException ex) {
      recordFailure(span, ex);
      responseObserver.onError(ex);
    }
  }

  @Override
  public void emptyCart(EmptyCartRequest request, StreamObserver<Empty> responseObserver) {
    Span span = Span.current();
    span.setAttribute("user.id", request.getUserId());
    span.addEvent("Empty cart");

    try {
      double cartFailureRate = featureFlags.getDoubleValue("cartFailure", 0.0);
      if (cartFailureRate > 0 && ThreadLocalRandom.current().nextDouble() < cartFailureRate) {
        badCartStore.emptyCart(request.getUserId());
      } else {
        cartStore.emptyCart(request.getUserId());
      }

      responseObserver.onNext(EMPTY);
      responseObserver.onCompleted();
    } catch (StatusRuntimeException ex) {
      recordFailure(span, ex);
      responseObserver.onError(ex);
    }
  }

  private static void recordFailure(Span span, StatusRuntimeException ex) {
    span.recordException(ex);
    span.setStatus(StatusCode.ERROR, ex.getMessage());
  }
}
