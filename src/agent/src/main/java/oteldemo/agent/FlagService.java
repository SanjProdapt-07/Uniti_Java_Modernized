/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import dev.openfeature.sdk.Client;

/** {@code get_int_feature_flag}: integer flag lookup, default 0 (also on provider errors). */
class FlagService {

  private final Client client;

  FlagService(Client client) {
    this.client = client;
  }

  int intFlag(String name) {
    return client.getIntegerValue(name, 0);
  }
}
