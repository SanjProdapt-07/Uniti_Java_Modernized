/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.ad;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DEMO: this counter and its {@code /metrics} HTTP exporter use the Prometheus Java client library
 * rather than the OpenTelemetry SDK, to illustrate how existing Prometheus instrumentation can be
 * ingested into an OpenTelemetry pipeline through the Collector's {@code prometheus} receiver (the
 * {@code prometheus/ad} receiver scrapes {@code ad:AD_PROMETHEUS_PORT}). See README.md.
 */
@Configuration(proxyBeanMethods = false)
class PrometheusConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(PrometheusConfiguration.class);

  @Bean
  PrometheusRegistry prometheusRegistry() {
    return new PrometheusRegistry();
  }

  @Bean
  Counter adsServedCounter(PrometheusRegistry registry) {
    return Counter.builder()
        .name("demo_ad_served_total")
        .help("Total number of ads served, labeled by category")
        .labelNames("category")
        .register(registry);
  }

  @Bean(destroyMethod = "stop")
  HTTPServer prometheusServer(
      PrometheusRegistry registry, @Value("${ad.prometheus-port:9465}") int port)
      throws IOException {
    HTTPServer server = HTTPServer.builder().port(port).registry(registry).buildAndStart();
    logger.info("Prometheus metrics endpoint started, listening on {}/metrics", server.getPort());
    return server;
  }
}
