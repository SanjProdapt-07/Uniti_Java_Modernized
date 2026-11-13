/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.Value;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The two flag lookups of the fraud detection loop. */
@Component
class FeatureFlags {

  /** Boolean flag: while on, the consumer leaves the group and stops polling. */
  static final String KAFKA_CONSUMER_DEAD = "kafkaConsumerDead";

  /** Integer flag: per record sleep in milliseconds. */
  static final String KAFKA_QUEUE_PROBLEMS = "kafkaQueueProblems";

  private final Client client;

  FeatureFlags(Client client) {
    this.client = client;
  }

  /**
   * Retrieves the integer value of a feature flag from the feature flag service.
   *
   * @return the flag's integer value, or 0 on error / when unset
   */
  int getFeatureFlagValue(String ff) {
    return client.getIntegerValue(ff, 0, sessionContext());
  }

  /**
   * Retrieves the boolean value of a feature flag from the feature flag service.
   *
   * @return the flag's boolean value, or false on error / when unset
   */
  boolean getBooleanFeatureFlagValue(String ff) {
    return client.getBooleanValue(ff, false, sessionContext());
  }

  // TODO (carried over): plumb the actual session ID from the frontend via baggage?
  private static EvaluationContext sessionContext() {
    Map<String, Value> attributes = new HashMap<>();
    attributes.put("session", new Value(UUID.randomUUID().toString()));
    return new ImmutableContext(attributes);
  }
}
