/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs the resolved downstream addresses at startup, like the "service config" line of the Go service. */
@Component
class ServiceConfigLogger {

  private static final Logger logger = LoggerFactory.getLogger(ServiceConfigLogger.class);

  private final String summary;

  ServiceConfigLogger(
      @Value("${spring.grpc.client.channel.product-catalog.target}") String productCatalogAddr,
      @Value("${spring.grpc.client.channel.cart.target}") String cartAddr,
      @Value("${spring.grpc.client.channel.currency.target}") String currencyAddr,
      @Value("${checkout.shipping-addr}") String shippingAddr,
      @Value("${checkout.email-addr}") String emailAddr,
      @Value("${spring.grpc.client.channel.payment.target}") String paymentAddr,
      @Value("${checkout.kafka.addr:}") String kafkaAddr) {
    this.summary =
        "service config: productCatalogSvcAddr="
            + productCatalogAddr
            + " cartSvcAddr="
            + cartAddr
            + " currencySvcAddr="
            + currencyAddr
            + " shippingSvcAddr="
            + shippingAddr
            + " emailSvcAddr="
            + emailAddr
            + " paymentSvcAddr="
            + paymentAddr
            + " kafkaBrokerSvcAddr="
            + kafkaAddr;
  }

  @EventListener(ApplicationReadyEvent.class)
  void logConfig() {
    logger.info(summary);
  }
}
