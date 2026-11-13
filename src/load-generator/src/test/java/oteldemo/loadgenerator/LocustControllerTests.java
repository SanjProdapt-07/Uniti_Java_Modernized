/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "load-generator.browser-traffic-enabled=false"
    })
@AutoConfigureMockMvc
class LocustControllerTests {

  private static final FakeSite SITE = createSite();

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

  @Autowired private MockMvc mvc;
  @Autowired private LoadRunner runner;
  @Autowired private LoadGeneratorProperties properties;

  @AfterEach
  void stopSwarm() {
    runner.stop();
    SITE.requests().clear();
  }

  @Test
  void bindsOriginalEnvironmentDefaults() {
    assertThat(properties.host()).isEqualTo(SITE.url());
    assertThat(properties.users()).isEqualTo(1);
    assertThat(properties.spawnRate()).isEqualTo(1.0);
    assertThat(properties.headless()).isFalse();
    assertThat(properties.autostart()).isFalse();
    assertThat(properties.httpUserWeight()).isEqualTo(9);
    assertThat(properties.browserUserWeight()).isEqualTo(1);
    assertThat(properties.agentEndpoint()).isEqualTo("agent");
    assertThat(properties.agentPort()).isEqualTo("8010");
    assertThat(properties.isBrowserTrafficEnabled()).isFalse();
    assertThat(runner.userClasses()).extracting(LoadRunner.UserClass::name).containsExactly("WebsiteUser");
  }

  @Test
  void indexPageIsServedWithRelativeUrlsOnly() throws Exception {
    mvc.perform(get("/")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("index.html"));
    mvc.perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("fetch('stats/requests')")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("fetch('swarm'")))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fetch('/"))));
  }

  @Test
  void swarmStartsUsersThatHitTheHostAndStatsShowThem() throws Exception {
    mvc.perform(post("/swarm").param("user_count", "2").param("spawn_rate", "100").param("host", SITE.url()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("Swarming started"))
        .andExpect(jsonPath("$.host").value(SITE.url()));

    // each user visits the homepage on start
    await().atMost(Duration.ofSeconds(15)).until(() -> SITE.requests().stream().filter(r -> r.uri().equals("/")).count() >= 2);
    await().atMost(Duration.ofSeconds(15)).until(() -> runner.state() == LoadRunner.State.RUNNING);

    mvc.perform(get("/stats/requests"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("running"))
        .andExpect(jsonPath("$.user_count").value(2))
        .andExpect(jsonPath("$.stats[?(@.name == '/')].num_requests").isNotEmpty())
        .andExpect(jsonPath("$.stats[?(@.name == 'Aggregated')]").isNotEmpty())
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.fail_ratio").value(0.0));
  }

  @Test
  void stopEndsTheTest() throws Exception {
    mvc.perform(post("/swarm").param("user_count", "1").param("spawn_rate", "10"));
    await().until(() -> runner.userCount() == 1);

    mvc.perform(get("/stop"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("Test stopped"));

    mvc.perform(get("/stats/requests"))
        .andExpect(jsonPath("$.state").value("stopped"))
        .andExpect(jsonPath("$.user_count").value(0));
  }

  @Test
  void swarmWithoutParametersUsesConfiguredDefaults() throws Exception {
    mvc.perform(post("/swarm")).andExpect(jsonPath("$.success").value(true));
    await().until(() -> runner.userCount() == 1);
  }

  @Test
  void swarmRejectsInvalidParameters() throws Exception {
    LoadRunner.State before = runner.state();
    mvc.perform(post("/swarm").param("user_count", "abc")).andExpect(jsonPath("$.success").value(false));
    mvc.perform(post("/swarm").param("user_count", "1").param("spawn_rate", "0")).andExpect(jsonPath("$.success").value(false));
    assertThat(runner.state()).isEqualTo(before);
  }

  @Test
  void resetClearsStats() throws Exception {
    mvc.perform(post("/swarm").param("user_count", "1").param("spawn_rate", "10"));
    await().until(() -> SITE.requests().size() >= 1);
    await().until(() -> !runner.state().equals(LoadRunner.State.SPAWNING) || runner.userCount() == 1);

    mvc.perform(get("/stats/reset")).andExpect(status().isOk()).andExpect(content().string("ok"));
  }

  @Test
  void csvAndExceptionsEndpointsRespond() throws Exception {
    mvc.perform(get("/stats/requests/csv"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.startsWith("\"Type\",\"Name\",\"Request Count\"")));
    mvc.perform(get("/stats/failures/csv"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.startsWith("\"Method\",\"Name\",\"Error\"")));
    mvc.perform(get("/exceptions")).andExpect(status().isOk()).andExpect(jsonPath("$.exceptions").isArray());
    mvc.perform(get("/tasks"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ratios.WebsiteUser").value(1.0));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }
  }
}
