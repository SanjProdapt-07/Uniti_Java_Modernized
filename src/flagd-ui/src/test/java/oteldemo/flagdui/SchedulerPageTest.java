/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Port of the LiveView scheduler tests onto the server-rendered page and its JSON endpoints. */
class SchedulerPageTest extends AbstractWebTest {

  private String page() throws Exception {
    return mvc.perform(get("/scheduler"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private ResultActions start(String json) throws Exception {
    return mvc.perform(
        post("/ui/scheduler/start").contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private static String form(
      String interval, String min, String max, String seed, String variantsJson) {
    return "{\"interval_seconds\":\""
        + interval
        + "\",\"min_duration_seconds\":\""
        + min
        + "\",\"max_duration_seconds\":\""
        + max
        + "\",\"concurrency\":\"1\",\"seed\":\""
        + seed
        + "\",\"variants\":"
        + variantsJson
        + "}";
  }

  private String allVariants() {
    StringBuilder json = new StringBuilder("{");
    for (Scheduler.SchedulableFlag flag : Scheduler.schedulableFlags(storage.read())) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append('"').append(flag.name()).append("\":[");
      for (int i = 0; i < flag.variants().size(); i++) {
        json.append(i == 0 ? "" : ",").append('"').append(flag.variants().get(i)).append('"');
      }
      json.append(']');
    }
    return json.append('}').toString();
  }

  @Test
  void rendersTheSchedulableFlagsAndStartsOutIdle() throws Exception {
    String html = page();

    assertTrue(html.contains("Scheduler stopped"));
    assertTrue(html.contains("Idle"));
    assertTrue(html.contains("adFailure"));
    assertFalse(html.contains("emitRawPii"));
    assertTrue(html.contains("Activates randomly picked flags each interval, then reverts them to normal."));
    assertTrue(html.contains(">Start<") || html.contains("Start\n"));
    assertFalse(html.contains("data-action=\"stop\""));
  }

  @Test
  void surfacesAValidationErrorForAnUnusableConfiguration() throws Exception {
    start(form("10", "20", "30", "", allVariants()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.error", containsString("must fit within the interval")));

    assertTrue(page().contains("Scheduler stopped"));
  }

  @Test
  void rejectsANonNumericInterval() throws Exception {
    start(form("soon", "10", "20", "", allVariants()))
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.error", containsString("whole number of seconds")));
  }

  @Test
  void rejectsOtherUnparsableValues() throws Exception {
    start(form("10", "1.5", "20", "", allVariants()))
        .andExpect(
            jsonPath("$.error")
                .value("Minimum duration must be a whole number of seconds greater than zero"));
    start(form("10", "10", "0", "", allVariants()))
        .andExpect(
            jsonPath("$.error")
                .value("Maximum duration must be a whole number of seconds greater than zero"));
    start(form("10", "10", "10", "abc", allVariants()))
        .andExpect(jsonPath("$.error").value("Seed must be a whole number"));
    start(
            "{\"interval_seconds\":10,\"min_duration_seconds\":10,\"max_duration_seconds\":10,"
                + "\"concurrency\":\"x\",\"seed\":\"\",\"variants\":"
                + allVariants()
                + "}")
        .andExpect(
            jsonPath("$.error").value("Flags at once must be a whole number greater than zero"));
  }

  @Test
  void startingTheSchedulerReportsItAsRunning() throws Exception {
    start(form("600", "600", "600", "7", allVariants()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.message").value("Scheduler started"));

    String html = page();
    assertTrue(html.contains("Scheduler running"));
    assertTrue(html.contains("Active"));
    assertTrue(html.contains("Restart"));
    assertTrue(html.contains("data-action=\"stop\""));

    Scheduler.State state = scheduler.state();
    assertTrue(state.running());
    assertEquals(7L, state.seedUsed());
  }

  @Test
  void numericJsonValuesAreAcceptedToo() throws Exception {
    start(
            "{\"interval_seconds\":600,\"min_duration_seconds\":600,\"max_duration_seconds\":600,"
                + "\"concurrency\":2,\"seed\":9,\"variants\":"
                + allVariants()
                + "}")
        .andExpect(jsonPath("$.ok").value(true));

    assertEquals(2, scheduler.state().config().concurrency());
    assertEquals(9L, scheduler.state().seedUsed());
  }

  @Test
  void theStatusFragmentShowsHoldsAndRecentActivations() throws Exception {
    start(form("600", "600", "600", "7", allVariants())).andExpect(jsonPath("$.ok").value(true));

    String status =
        mvc.perform(get("/ui/scheduler/status"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"scheduler-status\"")))
            .andExpect(content().string(containsString("id=\"scheduler-actions\"")))
            .andExpect(content().string(containsString("id=\"scheduler-history\"")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertTrue(status.contains("Scheduler running"));
    assertTrue(status.contains("Currently active ("));

    mvc.perform(post("/ui/scheduler/stop")).andExpect(jsonPath("$.ok").value(true));

    String stopped = mvc.perform(get("/ui/scheduler/status")).andReturn().getResponse().getContentAsString();
    assertTrue(stopped.contains("Scheduler stopped"));
    assertFalse(stopped.contains("<html"));
  }

  @Test
  void stoppingTheSchedulerRevertsAndReportsIdle() throws Exception {
    start(form("600", "600", "600", "7", allVariants())).andExpect(jsonPath("$.ok").value(true));

    mvc.perform(post("/ui/scheduler/stop"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Scheduler stopped"));

    assertFalse(scheduler.state().running());
    assertTrue(page().contains("Scheduler stopped"));
  }

  @Test
  void aPresetFillsInTheTimingFields() throws Exception {
    String html = page();

    assertTrue(html.contains("Presentation (60s / 10-20s)"));
    assertTrue(html.contains("Frequent (15m / 1-3m)"));
    assertTrue(html.contains("Hourly (1h / 5-15m)"));
    assertTrue(html.contains("data-name=\"presentation\""));
    assertTrue(html.contains("data-interval=\"60\""));
    assertTrue(html.contains("data-min=\"10\""));
    assertTrue(html.contains("data-max=\"20\""));
    assertTrue(html.contains("data-interval=\"3600\""));
    assertTrue(html.contains("data-interval=\"900\""));
  }

  @Test
  void clearingTheFlagSelectionIsRejected() throws Exception {
    start(form("600", "60", "120", "", "{}"))
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.error", containsString("at least one flag variant")));
  }

  @Test
  void offersACheckboxPerVariantForAFlagWithSeveralVariants() throws Exception {
    String html = page();

    for (String variant : List.of("10%", "25%", "50%", "75%", "90%", "100%")) {
      assertTrue(
          html.contains("name=\"variants[cartFailure][]\" value=\"" + variant + "\""),
          "checkbox for " + variant);
    }
    assertTrue(html.contains("name=\"variants[adFailure][]\" value=\"on\""));
    assertTrue(html.contains("data-action=\"toggle_flag\""));
    assertTrue(html.contains("data-flag=\"cartFailure\""));
  }

  @Test
  void aFlagWithOneVariantHasNoToggleAllButton() throws Exception {
    String html = page();

    assertFalse(html.contains("data-flag=\"adFailure\""));
  }

  @Test
  void schedulingOnlyTheGentlePercentagesKeepsTheHarshOnesOut() throws Exception {
    start(form("600", "600", "600", "3", "{\"cartFailure\":[\"10%\",\"25%\"]}"))
        .andExpect(jsonPath("$.ok").value(true));

    assertEquals(
        Map.of("cartFailure", List.of("10%", "25%")), scheduler.state().config().flags());

    // The form of a page opened next reflects what was scheduled. The run is stopped first: while
    // it holds a flag, that flag's stored default is the held variant and the page (like the
    // LiveView page before it) derives the offered variants from the current file.
    scheduler.stopSchedule();
    String html = page();
    assertTrue(html.contains("name=\"variants[cartFailure][]\" value=\"10%\" checked"));
    assertFalse(html.contains("name=\"variants[cartFailure][]\" value=\"100%\" checked"));
    assertFalse(html.contains("name=\"variants[adFailure][]\" value=\"on\" checked"));
  }

  @Test
  void flagsAreScheduledWithTheSeedAndConcurrencyOfTheForm() throws Exception {
    start(form("600", "600", "600", "3", allVariants())).andExpect(jsonPath("$.ok").value(true));

    Scheduler.Config config = scheduler.state().config();
    assertEquals(600_000L, config.intervalMs());
    assertEquals(600_000L, config.minDurationMs());
    assertEquals(600_000L, config.maxDurationMs());
    assertEquals(1, config.concurrency());
    assertEquals(3L, config.seed());
  }
}
