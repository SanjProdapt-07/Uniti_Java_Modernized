/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FraudDetectionApplication {

  public static void main(String[] args) {
    if (System.getenv("KAFKA_ADDR") == null) {
      System.out.println("KAFKA_ADDR is not supplied");
      System.exit(1);
    }
    SpringApplication.run(FraudDetectionApplication.class, args);
  }
}
