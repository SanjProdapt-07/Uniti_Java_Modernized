/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.quote;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The PHP service listened on 0.0.0.0 and switched to the IPv6 wildcard when {@code
 * IPV6_ENABLED=true}. It also printed the address it listens on.
 */
@Configuration(proxyBeanMethods = false)
class ServerAddressConfiguration {

  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> quoteListenAddress(
      Environment environment) {
    return factory -> {
      String ip = "0.0.0.0";
      boolean ipv6 = "true".equals(environment.getProperty("IPV6_ENABLED"));
      try {
        if (ipv6) {
          ip = "[::]";
          System.out.println("Overwriting Localhost IP: " + ip);
          factory.setAddress(InetAddress.getByName("::"));
        } else {
          factory.setAddress(InetAddress.getByName("0.0.0.0"));
        }
      } catch (UnknownHostException e) {
        throw new IllegalStateException("Cannot resolve listen address " + ip, e);
      }
      System.out.println("Listening on: " + ip + ":" + environment.getProperty("server.port", "8090"));
    };
  }
}
