/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.ad.problempattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import org.junit.jupiter.api.Test;

// The real trigger fills the heap to 90% with slow-to-finalize objects. These tests report a full heap
// through a mocked MemoryMXBean so the allocation loop is skipped and only the GC cycle runs.
class GarbageCollectionTriggerTests {

  private final MemoryMXBean memoryBean = mock(MemoryMXBean.class);

  GarbageCollectionTriggerTests() {
    when(memoryBean.getHeapMemoryUsage()).thenReturn(new MemoryUsage(0, 95, 100, 100));
  }

  @Test
  void runsTheGcCycleOnFirstExecution() {
    GarbageCollectionTrigger trigger = new GarbageCollectionTrigger(memoryBean, 10_000, 0, 500_000);

    trigger.doExecute();

    // once per round of the 10-iteration loop
    verify(memoryBean, atLeast(10)).getHeapMemoryUsage();
  }

  @Test
  void doesNotRunAgainWithinTheDelay() {
    GarbageCollectionTrigger trigger = new GarbageCollectionTrigger(memoryBean, 60_000, 0, 500_000);
    trigger.doExecute();
    org.mockito.Mockito.clearInvocations(memoryBean);

    trigger.doExecute();

    verify(memoryBean, never()).getHeapMemoryUsage();
  }

  @Test
  void runsAgainWhenTheDelayIsZero() throws InterruptedException {
    GarbageCollectionTrigger trigger = new GarbageCollectionTrigger(memoryBean, 0, 0, 500_000);
    trigger.doExecute();
    org.mockito.Mockito.clearInvocations(memoryBean);
    Thread.sleep(5);

    trigger.doExecute();

    verify(memoryBean, atLeast(10)).getHeapMemoryUsage();
  }

  @Test
  void defaultConstructorUsesTheRealMemoryBean() {
    GarbageCollectionTrigger trigger = new GarbageCollectionTrigger();

    assertThat(trigger).isNotNull();
  }
}
