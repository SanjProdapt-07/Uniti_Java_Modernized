/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs the equivalent of the Rust {@code shipping.server.started} event. */
@Component
class ServerStartedLogger {

  private static final Logger logger = LoggerFactory.getLogger(ServerStartedLogger.class);

  @EventListener
  void onStarted(ServletWebServerInitializedEvent event) {
    WebServerApplicationContext context = event.getApplicationContext();
    String address =
        context.getEnvironment().getProperty("shipping.ipv6-enabled", "false").equals("true")
            ? "[::]"
            : "0.0.0.0";
    logger
        .atInfo()
        .addKeyValue("event.name", "shipping.server.started")
        .addKeyValue("addr", address + ":" + event.getWebServer().getPort())
        .log("Shipping service is running");
  }
}
