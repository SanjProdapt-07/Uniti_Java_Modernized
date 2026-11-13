/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * Envoy rewrites the matched {@code /feature} prefix to {@code /}, so the service sees paths such
 * as {@code //api/read}. These are sent over a real HTTP connection.
 */
class DuplicateSlashFilterTest extends AbstractWebTest {

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void apiReadIsReachableWithADoubleSlashPrefix() throws Exception {
    HttpResponse<String> response = send("GET", "//api/read", null);

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"adFailure\""));
  }

  @Test
  void apiWriteIsReachableWithADoubleSlashPrefix() throws Exception {
    HttpResponse<String> response = send("POST", "//api/write", "{\"data\":{\"flags\":{}}}");

    assertEquals(200, response.statusCode());
    assertEquals("{}", response.body());
    assertEquals(200, send("GET", "/api/read", null).statusCode());
    assertEquals("{\"flags\":{}}", send("GET", "/api/read", null).body());
  }

  @Test
  void theRootPageAndStaticAssetsAreReachableThroughTheRewrittenPrefix() throws Exception {
    HttpResponse<String> page = send("GET", "//", null);
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("Flagd Configurator"));

    HttpResponse<String> advanced = send("GET", "//advanced", null);
    assertEquals(200, advanced.statusCode());

    HttpResponse<String> script = send("GET", "//assets/js/app.js", null);
    assertEquals(200, script.statusCode());
    assertTrue(script.body().contains("flagd UI"));

    HttpResponse<String> css = send("GET", "//assets/css/app.css", null);
    assertEquals(200, css.statusCode());
  }

  @Test
  void queryStringsSurviveTheForward() throws Exception {
    HttpResponse<String> response = send("GET", "//ui/flags/version?x=1", null);

    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("version"));
  }

  @Test
  void singleSlashPathsAreUntouched() throws Exception {
    assertEquals(200, send("GET", "/api/read-file", null).statusCode());
    assertEquals(200, send("GET", "/", null).statusCode());
  }
}
