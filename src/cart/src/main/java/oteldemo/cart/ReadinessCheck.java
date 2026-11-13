/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import dev.openfeature.sdk.Client;
import org.springframework.stereotype.Component;

/** Readiness of the service: unhealthy while the {@code failedReadinessProbe} flag is on. */
@Component
public class ReadinessCheck {

  /** The name the check is registered under, which is also the gRPC health service name. */
  public static final String NAME = "oteldemo.CartService";

  private final Client featureFlags;

  public ReadinessCheck(Client featureFlags) {
    this.featureFlags = featureFlags;
  }

  public boolean isHealthy() {
    return !featureFlags.getBooleanValue("failedReadinessProbe", false);
  }
}
