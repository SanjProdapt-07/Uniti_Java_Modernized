/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorPagesTest extends AbstractWebTest {

  // ErrorHTMLTest
  @Test
  void rendersNotFoundHtml() {
    assertEquals("Not Found", ErrorPages.statusMessageFromTemplate("404.html"));
    assertEquals("Not Found", ErrorPages.statusMessageFromTemplate("404"));
  }

  @Test
  void rendersInternalServerErrorHtml() {
    assertEquals("Internal Server Error", ErrorPages.statusMessageFromTemplate("500.html"));
  }

  @Test
  void unknownTemplatesRenderInternalServerError() {
    assertEquals("Internal Server Error", ErrorPages.statusMessageFromTemplate("oops"));
  }

  // ErrorJSONTest
  @Test
  void rendersNotFoundJson() {
    assertEquals(
        Map.of("errors", Map.of("detail", "Not Found")), ErrorPages.errorJson("404.json"));
  }

  @Test
  void rendersInternalServerErrorJson() {
    assertEquals(
        Map.of("errors", Map.of("detail", "Internal Server Error")),
        ErrorPages.errorJson("500.json"));
  }

  @Test
  void unknownRoutesBelowApiAreAJsonNotFound() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/nope"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertEquals(404, response.statusCode());
    assertEquals("{\"errors\":{\"detail\":\"Not Found\"}}", response.body());
  }

  @Test
  void unknownPagesAreAPlainTextNotFound() throws Exception {
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/nope"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertEquals(404, response.statusCode());
    assertEquals("Not Found", response.body());
  }
}
