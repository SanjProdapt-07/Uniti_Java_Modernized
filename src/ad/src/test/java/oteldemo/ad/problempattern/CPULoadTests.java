/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.ad.problempattern;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CPULoadTests {

  private final CPULoad cpuLoad = new CPULoad();

  @AfterEach
  void tearDown() {
    cpuLoad.execute(false);
  }

  @Test
  void isASingleton() {
    assertThat(CPULoad.getInstance()).isSameAs(CPULoad.getInstance());
  }

  @Test
  void startsFourWorkersWhenEnabledAndOnlyOnce() {
    cpuLoad.execute(true);
    assertThat(cpuLoad.runningWorkerCount()).isEqualTo(4);

    cpuLoad.execute(true);
    assertThat(cpuLoad.runningWorkerCount()).isEqualTo(4);
  }

  @Test
  void stopsWorkersWhenDisabled() {
    cpuLoad.execute(true);

    cpuLoad.execute(false);

    assertThat(cpuLoad.runningWorkerCount()).isZero();
  }

  @Test
  void canBeRestartedAfterStopping() {
    cpuLoad.execute(true);
    cpuLoad.execute(false);

    cpuLoad.execute(true);

    assertThat(cpuLoad.runningWorkerCount()).isEqualTo(4);
  }

  @Test
  void disablingWithoutEnablingIsANoOp() {
    cpuLoad.execute(false);

    assertThat(cpuLoad.runningWorkerCount()).isZero();
  }
}
