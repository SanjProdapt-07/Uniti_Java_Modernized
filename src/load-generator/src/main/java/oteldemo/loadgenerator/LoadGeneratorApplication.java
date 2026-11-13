/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Java replacement of the Locust based load generator (locustfile.py). */
@SpringBootApplication
@EnableConfigurationProperties(LoadGeneratorProperties.class)
public class LoadGeneratorApplication {

  public static void main(String[] args) {
    SpringApplication.run(LoadGeneratorApplication.class, args);
  }
}
