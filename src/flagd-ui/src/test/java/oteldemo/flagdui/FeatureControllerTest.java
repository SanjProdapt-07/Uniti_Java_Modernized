/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class FeatureControllerTest extends AbstractWebTest {

  private static final String DATA =
      "{\"flags\":{"
          + "\"adFailure\":{\"defaultVariant\":\"off\",\"description\":\"Fail ad service\","
          + "\"state\":\"ENABLED\",\"variants\":{\"off\":true,\"on\":false}},"
          + "\"adHighCpu\":{\"defaultVariant\":\"off\","
          + "\"description\":\"Triggers high cpu load in the ad service\","
          + "\"state\":\"ENABLED\",\"variants\":{\"off\":true,\"on\":false}}}}";

  private ResultActions assertOriginalFlags(ResultActions result) throws Exception {
    return result
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.flags.adFailure.defaultVariant").value("off"))
        .andExpect(jsonPath("$.flags.adFailure.description").value("Fail ad service"))
        .andExpect(jsonPath("$.flags.adFailure.state").value("ENABLED"))
        .andExpect(jsonPath("$.flags.adFailure.variants.off").value(false))
        .andExpect(jsonPath("$.flags.adFailure.variants.on").value(true))
        .andExpect(jsonPath("$.flags.adHighCpu.defaultVariant").value("off"))
        .andExpect(
            jsonPath("$.flags.adHighCpu.description")
                .value("Triggers high cpu load in the ad service"))
        .andExpect(jsonPath("$.flags.adHighCpu.state").value("ENABLED"))
        .andExpect(jsonPath("$.flags.adHighCpu.variants.off").value(false))
        .andExpect(jsonPath("$.flags.adHighCpu.variants.on").value(true));
  }

  private ResultActions assertWrittenFlags(ResultActions result) throws Exception {
    return result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.flags.adFailure.variants.off").value(true))
        .andExpect(jsonPath("$.flags.adFailure.variants.on").value(false))
        .andExpect(jsonPath("$.flags.adHighCpu.variants.off").value(true))
        .andExpect(jsonPath("$.flags.adHighCpu.variants.on").value(false))
        .andExpect(jsonPath("$.flags.adHighCpu.state").value("ENABLED"));
  }

  @Test
  void getApiRead() throws Exception {
    assertOriginalFlags(mvc.perform(get("/api/read")));
  }

  @Test
  void getApiReadFileLegacyApi() throws Exception {
    assertOriginalFlags(mvc.perform(get("/api/read-file")));
  }

  @Test
  void readReturnsOnlyTheFlagsKey() throws Exception {
    mvc.perform(get("/api/read")).andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void postApiWrite() throws Exception {
    mvc.perform(
            post("/api/write")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\":" + DATA + "}"))
        .andExpect(status().isOk())
        .andExpect(content().json("{}", true));

    assertWrittenFlags(mvc.perform(get("/api/read")));
  }

  @Test
  void postApiWriteToFileLegacyApi() throws Exception {
    mvc.perform(
            post("/api/write-to-file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\":" + DATA + "}"))
        .andExpect(status().isOk());

    assertWrittenFlags(mvc.perform(get("/api/read-file")));
  }

  @Test
  void writeReplacesTheWholeFileWithCompactJson() throws Exception {
    mvc.perform(
            post("/api/write")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"data\":" + DATA + "}"))
        .andExpect(status().isOk());

    String onDisk = Files.readString(TestFixtures.sharedStorageFile());
    assertEquals(FlagJson.encode(FlagJson.decode(DATA)), onDisk);
    assertEquals(-1, onDisk.indexOf('\n'));
  }

  @Test
  void writeWithoutDataIsABadRequest() throws Exception {
    mvc.perform(post("/api/write").contentType(MediaType.APPLICATION_JSON).content("{\"flags\":{}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("Bad Request"));
    mvc.perform(post("/api/write").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/write").contentType(MediaType.APPLICATION_JSON).content("[1]"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void writeWithAnUnparsableBodyIsABadRequest() throws Exception {
    mvc.perform(post("/api/write").contentType(MediaType.APPLICATION_JSON).content("{nope"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("Bad Request"));

    assertOriginalFlagsStillServed();
  }

  private void assertOriginalFlagsStillServed() throws Exception {
    mvc.perform(get("/api/read"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.flags.adFailure.variants.on").value(true));
  }

  @Test
  void readWithoutFlagsIsAnInternalServerError() throws Exception {
    storage.replace("{}");

    mvc.perform(get("/api/read"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errors.detail").value("Internal Server Error"));
  }
}
