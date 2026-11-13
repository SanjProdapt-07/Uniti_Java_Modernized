/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import dev.openfeature.sdk.Client;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.lettuce.core.api.sync.RedisCommands;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;

/**
 * The Valkey backed {@link CartStore}. A cart is the protobuf encoded {@code Cart} in the
 * {@code cart} field of a hash keyed by the user id, which expires 60 minutes after its last
 * change.
 */
public class ValkeyCartStore implements CartStore {

  /** Attempts at the initial connection, like {@code ConnectRetry} of the original. */
  public static final int REDIS_RETRY_NUMBER = 30;

  static final Duration RETRY_BACKOFF_BASE = Duration.ofSeconds(1);
  static final Duration RETRY_BACKOFF_MAX = Duration.ofSeconds(10);

  private static final Logger logger = LoggerFactory.getLogger(ValkeyCartStore.class);

  private static final String CART_FIELD_NAME = "cart";
  private static final Duration CART_TTL = Duration.ofMinutes(60);
  private static final List<Double> LATENCY_BUCKETS =
      List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0);
  private static final AttributeKey<String> TRACE_ID_ATTRIBUTE =
      AttributeKey.stringKey("demo.cart.trace_id");

  private final ValkeyLinkFactory linkFactory;
  private final String connectionString;
  private final Client featureClient;
  private final int connectRetries;
  private final Duration backoffBase;
  private final Duration backoffMax;
  private final DoubleHistogram addItemHistogram;
  private final DoubleHistogram getCartHistogram;

  private final ReentrantLock lock = new ReentrantLock();
  private volatile ValkeyLink link;
  private volatile boolean connectionOpened;

  public ValkeyCartStore(
      ValkeyLinkFactory linkFactory,
      String connectionString,
      Client featureClient,
      OpenTelemetry openTelemetry,
      int connectRetries) {
    this(
        linkFactory,
        connectionString,
        featureClient,
        openTelemetry,
        connectRetries,
        RETRY_BACKOFF_BASE,
        RETRY_BACKOFF_MAX);
  }

  ValkeyCartStore(
      ValkeyLinkFactory linkFactory,
      String connectionString,
      Client featureClient,
      OpenTelemetry openTelemetry,
      int connectRetries,
      Duration backoffBase,
      Duration backoffMax) {
    this.linkFactory = linkFactory;
    this.connectionString = connectionString;
    this.featureClient = featureClient;
    this.connectRetries = Math.max(1, connectRetries);
    this.backoffBase = backoffBase;
    this.backoffMax = backoffMax;

    var meter = openTelemetry.getMeter("OpenTelemetry.Demo.Cart");
    this.addItemHistogram =
        meter
            .histogramBuilder("demo.cart.add_item.latency")
            .setUnit("s")
            .setExplicitBucketBoundariesAdvice(LATENCY_BUCKETS)
            .build();
    this.getCartHistogram =
        meter
            .histogramBuilder("demo.cart.get_cart.latency")
            .setUnit("s")
            .setExplicitBucketBoundariesAdvice(LATENCY_BUCKETS)
            .build();
  }

  @Override
  public void initialize() {
    ensureConnected();
  }

  private ValkeyLink ensureConnected() {
    ValkeyLink current = link;
    if (connectionOpened && current != null) {
      return current;
    }

    // Connection is closed or failed - open a new one but only at the first thread
    lock.lock();
    try {
      current = link;
      if (connectionOpened && current != null) {
        return current;
      }
      if (current != null) {
        link = null;
        current.close();
      }

      CartLog.redisConnecting(logger, connectionString);

      ValkeyLink opened = connectWithRetries();
      if (opened == null || !opened.isOpen()) {
        CartLog.redisConnectionFailed(logger);
        if (opened != null) {
          opened.close();
        }

        // We weren't able to connect to Redis despite some retries with exponential backoff.
        throw new IllegalStateException("Wasn't able to connect to redis");
      }

      CartLog.redisConnected(logger);
      try {
        RedisCommands<String, byte[]> cache = opened.commands();

        CartLog.redisSmallTest(logger);
        cache.set("cart", "OK".getBytes(StandardCharsets.UTF_8));
        byte[] res = cache.get("cart");

        CartLog.redisSmallTestResult(
            logger, res == null ? null : new String(res, StandardCharsets.UTF_8));
      } catch (RuntimeException e) {
        opened.close();
        throw e;
      }

      opened.setStateListener(
          new ValkeyLink.StateListener() {
            @Override
            public void onRestored() {
              connectionOpened = true;
              CartLog.redisConnectionRestored(logger);
            }

            @Override
            public void onLost() {
              CartLog.redisConnectionLost(logger);
              connectionOpened = false;
            }

            @Override
            public void onInternalError(Throwable error) {
              CartLog.redisInternalError(logger, error);
            }
          });

      link = opened;
      connectionOpened = true;
      return opened;
    } finally {
      lock.unlock();
    }
  }

  /** Repeats the initial connection attempt with exponential backoff, like the original. */
  private ValkeyLink connectWithRetries() {
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= connectRetries; attempt++) {
      try {
        ValkeyLink opened = linkFactory.connect();
        if (opened != null && opened.isOpen()) {
          return opened;
        }
        if (opened != null) {
          opened.close();
        }
      } catch (Exception e) {
        logger.debug("Valkey connection attempt {}/{} failed: {}", attempt, connectRetries, e.toString());
        lastFailure = e instanceof RuntimeException re ? re : new IllegalStateException(e);
      }
      if (attempt < connectRetries && !backoff(attempt)) {
        break;
      }
    }
    if (lastFailure != null) {
      logger.debug("Giving up connecting to Valkey", lastFailure);
    }
    return null;
  }

  private boolean backoff(int attempt) {
    long millis =
        Math.min(
            backoffMax.toMillis(),
            backoffBase.toMillis() * (1L << Math.min(attempt - 1, 20)));
    try {
      TimeUnit.MILLISECONDS.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void addItem(String userId, String productId, int quantity) {
    long start = System.nanoTime();

    CartLog.addItem(logger, userId, productId, quantity);

    try {
      RedisCommands<String, byte[]> db = ensureConnected().commands();

      // Access the cart from the cache
      byte[] value = db.hget(userId, CART_FIELD_NAME);

      Cart cart;
      if (value == null) {
        cart =
            Cart.newBuilder()
                .setUserId(userId)
                .addItems(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
                .build();
      } else {
        cart = Cart.parseFrom(value);
        int existing = -1;
        for (int i = 0; i < cart.getItemsCount(); i++) {
          if (cart.getItems(i).getProductId().equals(productId)) {
            if (existing >= 0) {
              // Enumerable.SingleOrDefault of the original throws on duplicates
              throw new IllegalStateException("Sequence contains more than one matching element");
            }
            existing = i;
          }
        }
        if (existing < 0) {
          cart =
              cart.toBuilder()
                  .addItems(CartItem.newBuilder().setProductId(productId).setQuantity(quantity))
                  .build();
        } else {
          CartItem item = cart.getItems(existing);
          cart =
              cart.toBuilder()
                  .setItems(existing, item.toBuilder().setQuantity(item.getQuantity() + quantity))
                  .build();
        }
      }

      db.hset(userId, CART_FIELD_NAME, cart.toByteArray());
      db.expire(userId, CART_TTL);
    } catch (Exception ex) {
      throw storageUnavailable(ex);
    } finally {
      addItemHistogram.record(elapsedSeconds(start));
    }
  }

  @Override
  public void emptyCart(String userId) {
    CartLog.emptyCart(logger, userId);
    try {
      RedisCommands<String, byte[]> db = ensureConnected().commands();

      // Update the cache with empty cart for given user
      db.hset(userId, CART_FIELD_NAME, Cart.getDefaultInstance().toByteArray());
      db.expire(userId, CART_TTL);
    } catch (Exception ex) {
      throw storageUnavailable(ex);
    }
  }

  @Override
  public Cart getCart(String userId) {
    boolean overflowEnabled = featureClient.getBooleanValue("cartMetricCardinalityOverflow", false);
    SpanContext spanContext = Span.current().getSpanContext();
    long start = System.nanoTime();

    CartLog.getCart(logger, userId);

    try {
      RedisCommands<String, byte[]> db = ensureConnected().commands();

      // Access the cart from the cache
      byte[] value = db.hget(userId, CART_FIELD_NAME);

      if (value != null) {
        return Cart.parseFrom(value);
      }

      // We decided to return empty cart in cases when user wasn't in the cache before
      return Cart.getDefaultInstance();
    } catch (Exception ex) {
      throw storageUnavailable(ex);
    } finally {
      if (overflowEnabled && spanContext.isValid()) {
        // Intentionally bad practice: trace IDs as metric attributes cause high cardinality.
        // Only for demonstrating overflow. Use exemplars for metric-to-trace correlation.
        getCartHistogram.record(
            elapsedSeconds(start), Attributes.of(TRACE_ID_ATTRIBUTE, spanContext.getTraceId()));
      } else {
        getCartHistogram.record(elapsedSeconds(start));
      }
    }
  }

  @Override
  public boolean ping() {
    try {
      ValkeyLink current = link;
      if (current == null) {
        return false;
      }
      return "PONG".equals(current.commands().ping());
    } catch (Exception e) {
      return false;
    }
  }

  /** Closes the connection, if any; used at shutdown. */
  public void close() {
    lock.lock();
    try {
      ValkeyLink current = link;
      link = null;
      connectionOpened = false;
      if (current != null) {
        current.close();
      }
    } finally {
      lock.unlock();
    }
  }

  private static double elapsedSeconds(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000_000.0;
  }

  private static StatusRuntimeException storageUnavailable(Exception ex) {
    return Status.FAILED_PRECONDITION
        .withDescription("Can't access cart storage. " + ex)
        .withCause(ex)
        .asRuntimeException();
  }
}
