/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServerStartupTests {

  private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");

  @Mock private CurrencyEvents events;

  @Test
  void ipv6IsEnabledOnlyForTheLiteralTrue() {
    assertThat(CurrencyApplication.isIpv6Enabled(null)).isFalse();
    assertThat(CurrencyApplication.isIpv6Enabled("false")).isFalse();
    assertThat(CurrencyApplication.isIpv6Enabled("TRUE")).isFalse();
    assertThat(CurrencyApplication.isIpv6Enabled("true")).isTrue();
  }

  @Test
  void logsServerStartedWithTheListeningAddress() {
    new ServerLifecycleLogger(events, false, 7001).onReady(null);

    verify(events)
        .info("currency.server.started", "Currency Server started", Attributes.of(SERVER_ADDRESS, "0.0.0.0:7001"));
  }

  @Test
  void logsTheIpOverwriteWhenIpv6IsEnabled() {
    new ServerLifecycleLogger(events, true, 7001).onReady(null);

    verify(events)
        .info("currency.server.ip_overwrite", "Overwriting Localhost IP", Attributes.of(SERVER_ADDRESS, "[::]"));
    verify(events)
        .info("currency.server.started", "Currency Server started", Attributes.of(SERVER_ADDRESS, "[::]:7001"));
  }

  @Test
  void logsServerStoppingOnShutdown() {
    new ServerLifecycleLogger(events, false, 7001).onClose(null);

    verify(events).info("currency.server.stopping", "Shutting down Currency Server");
  }
}
