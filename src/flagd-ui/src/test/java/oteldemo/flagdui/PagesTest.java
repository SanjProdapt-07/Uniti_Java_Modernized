/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Ports of PageControllerTest and NavbarTest, plus the basic and advanced pages. */
class PagesTest extends AbstractWebTest {

  private String page(String path) throws Exception {
    MvcResult result = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
    return result.getResponse().getContentAsString();
  }

  // PageControllerTest
  @Test
  void getRoot() throws Exception {
    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString("Flagd Configurator")));
  }

  // NavbarTest: the three pages link to each other
  @Test
  void theBasicPageLinksToTheAdvancedEditor() throws Exception {
    String html = page("/");

    assertTrue(html.contains("href=\"/advanced\""), html);
    assertTrue(html.contains(">Advanced<"));
    assertTrue(page("/advanced").contains("Flagd Configurator"));
  }

  @Test
  void theAdvancedEditorLinksBackToTheDashboard() throws Exception {
    String html = page("/advanced");

    assertTrue(Pattern.compile("href=\"/\"[^>]*>\\s*Basic").matcher(html).find(), html);
    assertTrue(page("/").contains("Flagd Configurator"));
  }

  @Test
  void theNavbarLinksToTheSchedulerAndMarksTheCurrentPage() throws Exception {
    String basic = page("/");
    assertTrue(basic.contains("href=\"/scheduler\""));
    assertTrue(page("/scheduler").contains("Flagd Configurator"));

    String activeClasses =
        "rounded-md px-3 py-2 text-sm font-medium bg-blue-700 text-white underline underline-offset-4 transition-all duration-200";
    assertTrue(
        Pattern.compile("class=\"" + activeClasses + "\"[^>]*>\\s*Basic").matcher(basic).find()
            || Pattern.compile("href=\"/\"[^>]*class=\"" + activeClasses + "\"[^>]*>\\s*Basic")
                .matcher(basic)
                .find(),
        "Basic must be highlighted on the basic page");
    assertTrue(
        Pattern.compile("class=\"" + activeClasses + "\"[^>]*>\\s*Advanced")
            .matcher(page("/advanced"))
            .find()
            || Pattern.compile(
                    "href=\"/advanced\"[^>]*class=\"" + activeClasses + "\"[^>]*>\\s*Advanced")
                .matcher(page("/advanced"))
                .find());
  }

  @Test
  void pagesCarryTheRootPathMetaTagAndTitle() throws Exception {
    String html = page("/");

    assertTrue(html.contains("<meta name=\"root-path\" content=\"/\""), html);
    assertTrue(html.contains("<title data-default=\"Flagd-ui\">Flagd-ui</title>"));
    assertTrue(html.contains("href=\"/assets/css/app.css\""));
    assertTrue(html.contains("src=\"/assets/js/app.js\""));
  }

  // Basic page
  @Test
  void theBasicPageShowsACardWithAVariantSelectPerFlag() throws Exception {
    String html = page("/");

    assertTrue(html.contains("Fail ad service"));
    Matcher select =
        Pattern.compile("<select[^>]*name=\"adFailure\"[^>]*>(.*?)</select>", Pattern.DOTALL)
            .matcher(html);
    assertTrue(select.find(), "select for adFailure");
    String options = select.group(1);
    assertTrue(Pattern.compile("<option[^>]*value=\"off\"[^>]*selected").matcher(options).find());
    assertTrue(Pattern.compile("<option[^>]*value=\"on\"").matcher(options).find());
    assertTrue(!Pattern.compile("<option[^>]*value=\"on\"[^>]*selected").matcher(options).find());
  }

  @Test
  void flagsWithTargetingRulesAreMarked() throws Exception {
    String html = page("/");

    assertTrue(html.contains("This flag has targeting rules."));
    int marked = html.split("This flag has targeting rules\\.", -1).length - 1;
    assertTrue(marked >= 1);
  }

  @Test
  void aFlagWithATargetingRuleShowsItsOnValue() throws Exception {
    storage.write("productCatalogFailure", "on");

    Matcher select =
        Pattern.compile(
                "<select[^>]*name=\"productCatalogFailure\"[^>]*>(.*?)</select>", Pattern.DOTALL)
            .matcher(page("/"));
    assertTrue(select.find());
    assertTrue(
        Pattern.compile("<option[^>]*value=\"on\"[^>]*selected").matcher(select.group(1)).find());
  }

  @Test
  void changingAFlagWritesItAndReportsTheSave() throws Exception {
    long before = storage.version();

    mvc.perform(
            post("/ui/flags")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flag\":\"adFailure\",\"variant\":\"on\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Saved: adFailure"));

    mvc.perform(get("/api/read")).andExpect(jsonPath("$.flags.adFailure.defaultVariant").value("on"));
    assertTrue(Files.readString(TestFixtures.sharedStorageFile()).contains("\"defaultVariant\": \"on\""));
    assertTrue(storage.version() > before);

    Matcher select =
        Pattern.compile("<select[^>]*name=\"adFailure\"[^>]*>(.*?)</select>", Pattern.DOTALL)
            .matcher(page("/ui/flags/grid"));
    assertTrue(select.find());
    assertTrue(
        Pattern.compile("<option[^>]*value=\"on\"[^>]*selected").matcher(select.group(1)).find());
  }

  @Test
  void theGridFragmentCarriesTheStorageVersionForPolling() throws Exception {
    String html = page("/ui/flags/grid");

    assertTrue(html.startsWith("<div"), html.substring(0, Math.min(80, html.length())));
    assertTrue(html.contains("id=\"flags-grid\""));
    assertTrue(html.contains("data-version=\"" + storage.version() + "\""));
    assertTrue(!html.contains("<html"));

    mvc.perform(get("/ui/flags/version"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(storage.version()));
  }

  @Test
  void theBasicPageToleratesAConfigurationWithoutFlags() throws Exception {
    storage.replace("{}");

    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("<select"))));
  }

  // Advanced page
  @Test
  void theAdvancedEditorShowsThePrettyPrintedConfiguration() throws Exception {
    String html = page("/advanced");

    Matcher textarea =
        Pattern.compile("<textarea[^>]*>(.*?)</textarea>", Pattern.DOTALL).matcher(html);
    assertTrue(textarea.find());
    String text = textarea.group(1).replace("&quot;", "\"");
    assertTrue(text.startsWith("{\n  \"$schema\": "), text.substring(0, 30));
    assertTrue(text.contains("\"adFailure\": {"));
    assertTrue(html.contains(">Save<") || html.contains("Save\n"));
  }

  @Test
  void savingValidJsonReplacesTheConfigurationWithTheTrimmedText() throws Exception {
    mvc.perform(
            post("/ui/advanced/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"  {\\\"flags\\\":{\\\"x\\\":1}}\\n \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.message").value("Saved!"))
        .andExpect(jsonPath("$.content").value("{\"flags\":{\"x\":1}}"));

    assertEquals("{\"flags\":{\"x\":1}}", Files.readString(TestFixtures.sharedStorageFile()));
    mvc.perform(get("/api/read")).andExpect(jsonPath("$.flags.x").value(1));
  }

  @Test
  void savingInvalidJsonIsRejectedAndKeepsTheConfiguration() throws Exception {
    String before = Files.readString(TestFixtures.sharedStorageFile());

    mvc.perform(
            post("/ui/advanced/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"{not json\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(false))
        .andExpect(jsonPath("$.message").value("Invalid JSON"));
    mvc.perform(
            post("/ui/advanced/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"\"}"))
        .andExpect(jsonPath("$.ok").value(false));

    assertEquals(before, Files.readString(TestFixtures.sharedStorageFile()));
  }
}
