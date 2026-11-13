/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Static product-image server; replaces the original nginx (+ngx_otel_module) container. */
@SpringBootApplication
public class ImageProviderApplication {

  public static void main(String[] args) {
    SpringApplication.run(ImageProviderApplication.class, args);
  }
}
