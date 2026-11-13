/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CurrencyApplication {

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(CurrencyApplication.class);
    // The original bound to 0.0.0.0, or to [::] when IPV6_ENABLED=true. Without an address Spring
    // gRPC binds the wildcard of the IPv6 stack ("*"), which is [::] (and also accepts IPv4 on a
    // dual stack host). Default properties have the lowest precedence, so
    // spring.grpc.server.address can still be set explicitly.
    if (!isIpv6Enabled(System.getenv("IPV6_ENABLED"))) {
      application.setDefaultProperties(Map.of("spring.grpc.server.address", "0.0.0.0"));
    }
    application.run(args);
  }

  static boolean isIpv6Enabled(String ipv6Enabled) {
    return "true".equals(ipv6Enabled);
  }
}
