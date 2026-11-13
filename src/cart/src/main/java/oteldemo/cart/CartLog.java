/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * The log statements of the service, one per event of the original {@code Log.cs}. Each carries
 * the original event name as the {@code event.name} key/value pair and its parameters as
 * key/value pairs, so they come out as log record attributes when the OpenTelemetry logback
 * appender captures key/value pairs.
 */
final class CartLog {

  private CartLog() {}

  static void redisConnecting(Logger logger, String connectionString) {
    logger
        .atLevel(Level.DEBUG)
        .addKeyValue("event.name", "cart.redis.connecting")
        .addKeyValue("connectionString", connectionString)
        .log("Connecting to Redis: {}", connectionString);
  }

  static void redisConnectionFailed(Logger logger) {
    logger
        .atError()
        .addKeyValue("event.name", "cart.redis.connection_failed")
        .log("Wasn't able to connect to redis");
  }

  static void redisConnected(Logger logger) {
    logger
        .atInfo()
        .addKeyValue("event.name", "cart.redis.connected")
        .log("Successfully connected to Redis");
  }

  static void redisSmallTest(Logger logger) {
    logger
        .atDebug()
        .addKeyValue("event.name", "cart.redis.small_test")
        .log("Performing small test");
  }

  static void redisSmallTestResult(Logger logger, String result) {
    logger
        .atDebug()
        .addKeyValue("event.name", "cart.redis.small_test_result")
        .addKeyValue("result", result)
        .log("Small test result: {}", result);
  }

  static void redisConnectionRestored(Logger logger) {
    logger
        .atInfo()
        .addKeyValue("event.name", "cart.redis.connection_restored")
        .log("Connection to redis was restored successfully.");
  }

  static void redisConnectionLost(Logger logger) {
    logger
        .atInfo()
        .addKeyValue("event.name", "cart.redis.connection_lost")
        .log("Connection failed. Disposing the object");
  }

  static void redisInternalError(Logger logger, Throwable error) {
    logger
        .atError()
        .addKeyValue("event.name", "cart.redis.internal_error")
        .setCause(error)
        .log("Redis internal error");
  }

  static void addItem(Logger logger, String userId, String productId, int quantity) {
    logger
        .atInfo()
        .addKeyValue("event.name", "cart.add_item")
        .addKeyValue("userId", userId)
        .addKeyValue("productId", productId)
        .addKeyValue("quantity", quantity)
        .log(
            "AddItemAsync called with userId={}, productId={}, quantity={}",
            userId,
            productId,
            quantity);
  }

  static void emptyCart(Logger logger, String userId) {
    logger
        .atInfo()
        .addKeyValue("event.name", "cart.empty")
        .addKeyValue("userId", userId)
        .log("EmptyCartAsync called with userId={}", userId);
  }

  static void getCart(Logger logger, String userId) {
    logger
        .atInfo()
        .addKeyValue("event.name", "cart.get")
        .addKeyValue("userId", userId)
        .log("GetCartAsync called with userId={}", userId);
  }

  static void healthCheckRequest(Logger logger, String service) {
    logger
        .atInfo()
        .addKeyValue("event.name", "cart.health_check.request")
        .addKeyValue("service", service)
        .log("Received health check request for service: {}", service);
  }

  static void healthWatchRequest(Logger logger, String service) {
    logger
        .atInfo()
        .addKeyValue("event.name", "cart.health_watch.request")
        .addKeyValue("service", service)
        .log("Received health watch request for service: {}", service);
  }
}
