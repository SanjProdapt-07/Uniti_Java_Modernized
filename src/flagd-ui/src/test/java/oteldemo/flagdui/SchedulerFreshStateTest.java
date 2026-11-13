/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The scheduler keeps its last configuration after it stops (as the GenServer did), so checking the
 * pristine form needs an application context that no other test has started a schedule in.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SchedulerFreshStateTest extends AbstractWebTest {

  @Test
  void theFormStartsWithTheDefaultTimingAndAllFlagsSelected() throws Exception {
    String html = page();

    assertTrue(html.contains("name=\"interval_seconds\" value=\"900\""), html);
    assertTrue(html.contains("name=\"min_duration_seconds\" value=\"60\""));
    assertTrue(html.contains("name=\"max_duration_seconds\" value=\"180\""));
    assertTrue(html.contains("name=\"concurrency\" value=\"1\""));
    assertTrue(html.contains("name=\"seed\" value=\"\""));
    assertTrue(html.contains("name=\"variants[cartFailure][]\" value=\"10%\" checked"), html);
  }

  private String page() throws Exception {
    return mvc.perform(get("/scheduler"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
