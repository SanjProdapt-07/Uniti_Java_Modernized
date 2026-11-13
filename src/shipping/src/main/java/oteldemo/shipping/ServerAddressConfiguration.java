/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds to 0.0.0.0, or to [::] when IPV6_ENABLED=true, exactly like the Rust service. */
@Configuration(proxyBeanMethods = false)
class ServerAddressConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(ServerAddressConfiguration.class);

  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> shippingBindAddress(
      @Value("${shipping.ipv6-enabled:false}") String ipv6Enabled) throws UnknownHostException {
    boolean ipv6 = "true".equals(ipv6Enabled);
    InetAddress address = InetAddress.getByName(ipv6 ? "::" : "0.0.0.0");
    return factory -> {
      if (ipv6) {
        logger.info("Overwriting Localhost IP:  [::]");
      }
      factory.setAddress(address);
    };
  }
}
