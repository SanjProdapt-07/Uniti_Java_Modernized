/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.accounting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class AccountingApplication {

  public static void main(String[] args) {
    System.out.println("Accounting service started");

    EnvironmentReport.printRelevant(System.getenv());

    // The .NET consumer threw when KAFKA_ADDR was missing; fail just as early and explicitly.
    String servers = System.getenv("KAFKA_ADDR");
    if (servers == null) {
      throw new IllegalStateException("The KAFKA_ADDR environment variable is not set.");
    }

    SpringApplication.run(AccountingApplication.class, args);
  }

}
