/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Start / stop log events of {@code RunServer()} in the original {@code server.cpp}. */
@Component
class ServerLifecycleLogger {

  private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");

  private final CurrencyEvents events;
  private final boolean ipv6Enabled;
  private final int port;

  ServerLifecycleLogger(
      CurrencyEvents events,
      @Value("${currency.ipv6-enabled:false}") boolean ipv6Enabled,
      @Value("${spring.grpc.server.port}") int port) {
    this.events = events;
    this.ipv6Enabled = ipv6Enabled;
    this.port = port;
  }

  @EventListener
  void onReady(ApplicationReadyEvent event) {
    boolean ipv6 = ipv6Enabled;
    String ip = ipv6 ? "[::]" : "0.0.0.0";
    if (ipv6) {
      events.info(
          "currency.server.ip_overwrite",
          "Overwriting Localhost IP",
          Attributes.of(SERVER_ADDRESS, ip));
    }
    events.info(
        "currency.server.started",
        "Currency Server started",
        Attributes.of(SERVER_ADDRESS, ip + ":" + port));
  }

  @EventListener
  void onClose(ContextClosedEvent event) {
    String message = "Shutting down Currency Server";
    System.out.println(message);
    events.info("currency.server.stopping", message);
  }
}
