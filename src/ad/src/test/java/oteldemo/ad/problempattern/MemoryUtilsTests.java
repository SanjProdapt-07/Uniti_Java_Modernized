/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.ad.problempattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import org.junit.jupiter.api.Test;

class MemoryUtilsTests {

  private final MemoryMXBean memoryBean = mock(MemoryMXBean.class);
  private final MemoryUtils memoryUtils = new MemoryUtils(memoryBean);

  @Test
  void heapUsageIsUsedDividedByMax() {
    when(memoryBean.getHeapMemoryUsage()).thenReturn(new MemoryUsage(0, 85, 100, 100));

    assertThat(memoryUtils.getHeapUsage()).isEqualTo(0.85);
  }

  @Test
  void heapUsageIsMinusOneWhenNoMaxHeapIsSet() {
    when(memoryBean.getHeapMemoryUsage()).thenReturn(new MemoryUsage(0, 85, 100, -1));

    assertThat(memoryUtils.getHeapUsage()).isEqualTo(-1.0);
  }

  @Test
  void delegatesPendingFinalizationCount() {
    when(memoryBean.getObjectPendingFinalizationCount()).thenReturn(42);

    assertThat(memoryUtils.getObjectPendingFinalizationCount()).isEqualTo(42);
  }
}
