/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gives the telemetry resource a random {@code service.instance.id} (the original sets a new
 * GUID at every start; an explicit {@code OTEL_RESOURCE_ATTRIBUTES} value wins) and remembers the
 * final resource, which the OpAMP client reports as the agent description.
 */
@Configuration(proxyBeanMethods = false)
class ResourceConfiguration {

  static final AttributeKey<String> SERVICE_INSTANCE_ID =
      AttributeKey.stringKey("service.instance.id");

  /** The resource the SDK ended up with; empty until the SDK is built. */
  static final class ResourceHolder {
    private final AtomicReference<Resource> resource = new AtomicReference<>(Resource.empty());

    Resource get() {
      return resource.get();
    }

    void set(Resource value) {
      resource.set(value);
    }
  }

  @Bean
  ResourceHolder resourceHolder() {
    return new ResourceHolder();
  }

  @Bean
  AutoConfigurationCustomizerProvider serviceInstanceIdCustomizer(ResourceHolder holder) {
    String serviceInstanceId = UUID.randomUUID().toString();
    return customizer ->
        customizer.addResourceCustomizer(
            (resource, config) -> {
              Resource merged =
                  Resource.create(Attributes.of(SERVICE_INSTANCE_ID, serviceInstanceId))
                      .merge(resource);
              holder.set(merged);
              return merged;
            });
  }
}
