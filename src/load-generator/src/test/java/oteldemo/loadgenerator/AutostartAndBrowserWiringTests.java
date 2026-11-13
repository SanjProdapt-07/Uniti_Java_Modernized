/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.microsoft.playwright.Page;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** LOCUST_AUTOSTART=true with LOCUST_USERS, plus LOCUST_BROWSER_TRAFFIC_ENABLED wiring and weights. */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "load-generator.autostart=true",
      "load-generator.users=10",
      "load-generator.spawn-rate=1000",
      "load-generator.browser-traffic-enabled=True",
      "load-generator.http-user-weight=4",
      "load-generator.browser-user-weight=1"
    })
class AutostartAndBrowserWiringTests {

  private static final FakeSite SITE = createSite();
  private static final AtomicInteger BROWSERS_OPENED = new AtomicInteger();

  private static FakeSite createSite() {
    try {
      return new FakeSite();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("load-generator.host", SITE::url);
  }

  @AfterAll
  static void closeSite() {
    SITE.close();
  }

  @Autowired private LoadRunner runner;

  @Test
  void autostartSpawnsConfiguredUsersWithBothClassesInConfiguredRatio() {
    await().atMost(Duration.ofSeconds(20)).until(() -> runner.state() == LoadRunner.State.RUNNING);

    assertThat(runner.userCount()).isEqualTo(10);
    assertThat(runner.userClasses()).extracting(LoadRunner.UserClass::name).containsExactly("WebsiteUser", "WebsiteBrowserUser");
    assertThat(runner.targets(10)).containsExactly(8, 2);
    // the plain http users visited the homepage as soon as they started
    await().atMost(Duration.ofSeconds(20)).until(() -> SITE.requests().stream().anyMatch(r -> r.uri().equals("/")));
    // the browser users opened their browsers (mocked) against the configured host
    await().atMost(Duration.ofSeconds(20)).until(() -> BROWSERS_OPENED.get() == 2);
    runner.stop();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }

    @Bean
    @Primary
    BrowserSessionFactory fakeBrowsers() {
      return baseUrl -> {
        BROWSERS_OPENED.incrementAndGet();
        BrowserSession session = Mockito.mock(BrowserSession.class);
        Mockito.when(session.newPage()).thenAnswer(i -> {
          Thread.sleep(Duration.ofHours(1));
          return Mockito.mock(Page.class);
        });
        return session;
      };
    }
  }
}
