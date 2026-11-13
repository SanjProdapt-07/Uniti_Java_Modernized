/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frauddetection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeatureFlagsTests {

  @Mock Client client;

  @Test
  void integerFlagIsEvaluatedWithDefaultZeroAndARandomSession() {
    when(client.getIntegerValue(eq("kafkaQueueProblems"), eq(0), any(EvaluationContext.class))).thenReturn(250);
    FeatureFlags flags = new FeatureFlags(client);

    assertThat(flags.getFeatureFlagValue("kafkaQueueProblems")).isEqualTo(250);

    ArgumentCaptor<EvaluationContext> context = ArgumentCaptor.forClass(EvaluationContext.class);
    verify(client).getIntegerValue(eq("kafkaQueueProblems"), eq(0), context.capture());
    String session = context.getValue().getValue("session").asString();
    assertThat(UUID.fromString(session)).isNotNull();
  }

  @Test
  void booleanFlagIsEvaluatedWithDefaultFalseAndAFreshSessionEachTime() {
    when(client.getBooleanValue(eq("kafkaConsumerDead"), eq(false), any(EvaluationContext.class))).thenReturn(true);
    FeatureFlags flags = new FeatureFlags(client);

    assertThat(flags.getBooleanFeatureFlagValue("kafkaConsumerDead")).isTrue();
    assertThat(flags.getBooleanFeatureFlagValue("kafkaConsumerDead")).isTrue();

    ArgumentCaptor<EvaluationContext> context = ArgumentCaptor.forClass(EvaluationContext.class);
    verify(client, org.mockito.Mockito.times(2)).getBooleanValue(eq("kafkaConsumerDead"), eq(false), context.capture());
    assertThat(context.getAllValues().get(0).getValue("session").asString())
        .isNotEqualTo(context.getAllValues().get(1).getValue("session").asString());
  }

  @Test
  void unknownFlagsFallBackToTheDefaults() throws Exception {
    InMemoryProvider provider = new InMemoryProvider(new HashMap<>());
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait("fallback-test", provider);
    FeatureFlags flags = new FeatureFlags(api.getClient("fallback-test"));

    assertThat(flags.getFeatureFlagValue("kafkaQueueProblems")).isZero();
    assertThat(flags.getBooleanFeatureFlagValue("kafkaConsumerDead")).isFalse();
  }

  @Test
  void configuredFlagsAreReturned() throws Exception {
    Map<String, Flag<?>> configured = new HashMap<>();
    configured.put(
        "kafkaQueueProblems",
        Flag.<Integer>builder().variant("on", 500).variant("off", 0).defaultVariant("on").build());
    configured.put(
        "kafkaConsumerDead",
        Flag.<Boolean>builder().variant("on", true).variant("off", false).defaultVariant("on").build());
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait("configured-test", new InMemoryProvider(configured));
    FeatureFlags flags = new FeatureFlags(api.getClient("configured-test"));

    assertThat(flags.getFeatureFlagValue("kafkaQueueProblems")).isEqualTo(500);
    assertThat(flags.getBooleanFeatureFlagValue("kafkaConsumerDead")).isTrue();
  }
}
