/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Exercises the real embedded server, including the servlet error dispatch that MockMvc does not perform. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"otel.traces.exporter=none", "otel.metrics.exporter=none", "otel.logs.exporter=none"})
class RealServerTests {

  @LocalServerPort private int port;

  private final HttpClient client = HttpClient.newHttpClient();

  private HttpResponse<byte[]> get(String path, String... headers) throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (headers.length > 0) {
      request.headers(headers);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void servesImageWithContentTypeAndNoServerHeader() throws Exception {
    HttpResponse<byte[]> response = get("/products/RoofBinoculars.jpg");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).contains("image/jpeg");
    assertThat(response.headers().firstValue("Server")).isEmpty();
    assertThat(response.headers().firstValue("Cache-Control")).isEmpty();
    assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    assertThat(response.body()).isEqualTo(Files.readAllBytes(Path.of("static/products/RoofBinoculars.jpg")));
  }

  @Test
  void missingFileGivesNginxStyleNotFoundPage() throws Exception {
    HttpResponse<byte[]> response = get("/products/DoesNotExist.jpg");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
    String body = new String(response.body());
    assertThat(body).contains("<title>404 Not Found</title>").contains("<hr><center>nginx</center>");
  }

  @Test
  void missingImageIsStill404WhenBrowserOnlyAcceptsImages() throws Exception {
    HttpResponse<byte[]> response =
        get("/products/DoesNotExist.jpg", "Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void rootIsForbidden() throws Exception {
    HttpResponse<byte[]> response = get("/");

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(new String(response.body())).contains("403 Forbidden");
  }

  @Test
  void directoryRedirectsToTrailingSlash() throws Exception {
    HttpResponse<byte[]> response = get("/products");

    assertThat(response.statusCode()).isEqualTo(301);
    assertThat(response.headers().firstValue("Location")).contains("http://localhost:" + port + "/products/");
  }

  @Test
  void statusIsPlainText() throws Exception {
    HttpResponse<byte[]> response = get("/status");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
    assertThat(new String(response.body())).startsWith("Active connections: ");
  }
}
