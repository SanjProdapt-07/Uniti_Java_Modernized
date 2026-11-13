/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import dev.openfeature.sdk.Client;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.javahttpclient.JavaHttpClientTelemetry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class LoadGeneratorConfiguration {

  static final String INSTRUMENTATION_SCOPE = "locustfile";

  @Bean
  RequestStats requestStats() {
    return new RequestStats();
  }

  @Bean
  LoadGeneratorMetrics loadGeneratorMetrics(OpenTelemetry openTelemetry) {
    return new LoadGeneratorMetrics(openTelemetry);
  }

  /** HTTP client whose requests become CLIENT spans and carry traceparent + baggage (RequestsInstrumentor). */
  @Bean
  HttpClient instrumentedHttpClient(OpenTelemetry openTelemetry) {
    HttpClient client =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(30)).build();
    return JavaHttpClientTelemetry.create(openTelemetry).wrap(client);
  }

  @Bean
  BrowserSessionFactory browserSessionFactory() {
    return new PlaywrightBrowserSessionFactory();
  }

  @Bean
  ExitHandler exitHandler(ApplicationContext context) {
    return code -> System.exit(SpringApplication.exit(context, () -> code));
  }

  @Bean(destroyMethod = "shutdown")
  LoadRunner loadRunner(
      LoadGeneratorProperties properties,
      RequestStats stats,
      LoadGeneratorMetrics metrics,
      OpenTelemetry openTelemetry,
      HttpClient httpClient,
      Client featureFlags,
      BrowserSessionFactory browserSessions,
      JsonMapper mapper,
      ExitHandler exitHandler) {
    Tracer tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    List<Map<String, Object>> people = Catalog.loadPeople(mapper);

    LoadRunner[] holder = new LoadRunner[1];
    HttpSessionFactory sessions =
        new HttpSessionFactory(httpClient, () -> holder[0].host(), properties.userAgent(), stats, metrics, mapper);

    List<LoadRunner.UserClass> classes = new ArrayList<>();
    classes.add(
        new LoadRunner.UserClass(
            "WebsiteUser",
            properties.httpUserWeight(),
            () ->
                new WebsiteUser(
                    tracer,
                    sessions.newSession(),
                    key -> featureFlags.getIntegerValue(key, 0),
                    newRandom(),
                    people,
                    properties.agentEndpoint(),
                    properties.agentPort(),
                    mapper)));
    if (properties.isBrowserTrafficEnabled()) {
      classes.add(
          new LoadRunner.UserClass(
              "WebsiteBrowserUser",
              properties.browserUserWeight(),
              () -> new BrowserUser(tracer, browserSessions, () -> holder[0].host(), newRandom(), stats)));
    }

    holder[0] =
        new LoadRunner(
            classes,
            stats,
            metrics,
            properties.host(),
            () -> LoadRunnerLifecycle.onRunTimeFinished(properties, stats, exitHandler));
    return holder[0];
  }

  private static RandomGenerator newRandom() {
    return RandomGeneratorFactory.of("L64X128MixRandom").create();
  }
}
