/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.stereotype.Component;

/**
 * Remembers the final resource of the OpenTelemetry SDK (service name, host, process, ...), which
 * the OpAMP client reports as the description of this agent.
 */
@Component
class ResourceCapture implements AutoConfigurationCustomizerProvider {

  private volatile Resource resource = Resource.empty();

  @Override
  public void customize(AutoConfigurationCustomizer customizer) {
    customizer.addResourceCustomizer(
        (resource, config) -> {
          this.resource = resource;
          return resource;
        });
  }

  Attributes attributes() {
    return resource.getAttributes();
  }
}
