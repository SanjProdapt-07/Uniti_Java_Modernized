/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** nginx {@code gzip_static on}: a pre-compressed sibling is served to clients that accept gzip. */
@SpringBootTest(properties = {"otel.traces.exporter=none", "otel.metrics.exporter=none", "otel.logs.exporter=none"})
@AutoConfigureMockMvc
class GzipStaticTests {

  @TempDir static Path root;

  @DynamicPropertySource
  static void staticRoot(DynamicPropertyRegistry registry) throws IOException {
    Files.writeString(root.resolve("hello.txt"), "hello plain");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write("hello plain".getBytes(StandardCharsets.UTF_8));
    }
    Files.write(root.resolve("hello.txt.gz"), bytes.toByteArray());
    registry.add("image-provider.static-root", () -> root.toUri().toString());
  }

  @Autowired private MockMvc mvc;

  @Test
  void servesGzipSiblingWhenAccepted() throws Exception {
    byte[] body =
        mvc.perform(get("/hello.txt").header(HttpHeaders.ACCEPT_ENCODING, "gzip"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_ENCODING, "gzip"))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(body).isEqualTo(Files.readAllBytes(root.resolve("hello.txt.gz")));
  }

  @Test
  void servesPlainFileWhenGzipNotAccepted() throws Exception {
    mvc.perform(get("/hello.txt"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(HttpHeaders.CONTENT_ENCODING))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("hello plain"));
  }
}
