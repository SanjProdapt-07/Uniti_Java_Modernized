/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "image-provider.static-root=file:./static/",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none"
    })
@AutoConfigureMockMvc
class ImageProviderTests {

  private static final Path STATIC = Paths.get("static");
  private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

  @Autowired private MockMvc mvc;
  @Autowired private InMemorySpanExporter spans;

  @BeforeEach
  void setUp() {
    spans.reset();
  }

  static Stream<String> staticFiles() throws IOException {
    try (Stream<Path> files = Files.walk(STATIC)) {
      return files.filter(Files::isRegularFile)
          .map(p -> STATIC.relativize(p).toString().replace('\\', '/'))
          .toList()
          .stream();
    }
  }

  @Test
  void carriesOverAllOriginalImages() throws IOException {
    assertThat(staticFiles().toList())
        .containsExactlyInAnyOrder(
            "Banner.png",
            "opentelemetry-demo-logo.png",
            "products/EclipsmartTravelRefractorTelescope.jpg",
            "products/LensCleaningKit.jpg",
            "products/NationalParkFoundationExplorascope.jpg",
            "products/OpticalTubeAssembly.jpg",
            "products/RedFlashlight.jpg",
            "products/RoofBinoculars.jpg",
            "products/SolarFilter.jpg",
            "products/SolarSystemColorImager.jpg",
            "products/StarsenseExplorer.jpg",
            "products/TheCometBook.jpg");
  }

  @ParameterizedTest
  @MethodSource("staticFiles")
  void servesEveryFileByteForByteWithNginxLikeHeaders(String path) throws Exception {
    byte[] expected = Files.readAllBytes(STATIC.resolve(path));
    String contentType = path.endsWith(".png") ? "image/png" : "image/jpeg";

    mvc.perform(get("/" + path))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, contentType))
        .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, expected.length))
        .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
        .andExpect(header().exists(HttpHeaders.LAST_MODIFIED))
        .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .andExpect(content().bytes(expected));
  }

  @Test
  void headRequestReturnsHeadersWithoutBody() throws Exception {
    mvc.perform(head("/products/RoofBinoculars.jpg"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
        .andExpect(content().bytes(new byte[0]));
  }

  @Test
  void supportsRangeRequests() throws Exception {
    mvc.perform(get("/products/RoofBinoculars.jpg").header(HttpHeaders.RANGE, "bytes=0-9"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string(HttpHeaders.CONTENT_RANGE, org.hamcrest.Matchers.startsWith("bytes 0-9/")))
        .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 10));
  }

  @Test
  void answersNotModifiedForConditionalRequests() throws Exception {
    String lastModified =
        mvc.perform(get("/Banner.png")).andReturn().getResponse().getHeader(HttpHeaders.LAST_MODIFIED);

    mvc.perform(get("/Banner.png").header(HttpHeaders.IF_MODIFIED_SINCE, lastModified))
        .andExpect(status().isNotModified());
  }

  @Test
  void missingFileIsNotFound() throws Exception {
    mvc.perform(get("/products/DoesNotExist.jpg")).andExpect(status().isNotFound());
  }

  @Test
  void missingFileEvenWhenClientOnlyAcceptsImages() throws Exception {
    mvc.perform(get("/products/DoesNotExist.jpg").header(HttpHeaders.ACCEPT, "image/avif,image/webp,*/*;q=0.8"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/products/DoesNotExist.jpg").header(HttpHeaders.ACCEPT, "image/png"))
        .andExpect(status().isNotFound());
  }

  @Test
  void rootAndDirectoriesAreForbiddenLikeNginxWithAutoindexOff() throws Exception {
    mvc.perform(get("/")).andExpect(status().isForbidden());
    mvc.perform(get("/products/")).andExpect(status().isForbidden());
  }

  @Test
  void directoryWithoutTrailingSlashRedirects() throws Exception {
    mvc.perform(get("/products"))
        .andExpect(status().isMovedPermanently())
        .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.endsWith("/products/")));
  }

  @Test
  void pathTraversalIsNotServed() throws Exception {
    mvc.perform(get("/../build.gradle")).andExpect(status().is4xxClientError());
    mvc.perform(get("/products/..%2f..%2fbuild.gradle")).andExpect(status().is4xxClientError());
  }

  @Test
  void statusEndpointMimicsStubStatus() throws Exception {
    String body =
        mvc.perform(get("/status"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.startsWith("text/plain")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .matches(
            "(?s)Active connections: \\d+ \\nserver accepts handled requests\\n \\d+ \\d+ \\d+ \\n"
                + "Reading: \\d+ Writing: \\d+ Waiting: \\d+ \\n");
  }

  @Test
  void statusEndpointAcceptsHead() throws Exception {
    mvc.perform(head("/status")).andExpect(status().isOk());
  }

  @Test
  void statusCountsRequests() throws Exception {
    long before = handled(mvc.perform(get("/status")).andReturn().getResponse().getContentAsString());
    mvc.perform(get("/Banner.png"));
    mvc.perform(get("/Banner.png"));
    long after = handled(mvc.perform(get("/status")).andReturn().getResponse().getContentAsString());
    assertThat(after - before).isEqualTo(3);
  }

  private static long handled(String statusBody) {
    return Long.parseLong(statusBody.split("\n")[2].trim().split(" ")[2]);
  }

  @Test
  void onlyReadMethodsAreServed() throws Exception {
    mvc.perform(post("/Banner.png")).andExpect(status().is4xxClientError());
  }

  @Test
  void serverSpanIsNamedImageProviderAndContinuesIncomingTrace() throws Exception {
    mvc.perform(
            get("/products/RoofBinoculars.jpg")
                .header("traceparent", "00-" + TRACE_ID + "-b7ad6b7169203331-01"))
        .andExpect(status().isOk());

    await().untilAsserted(() -> assertThat(serverSpans()).isNotEmpty());
    SpanData span = serverSpans().get(0);
    assertThat(span.getName()).isEqualTo("image-provider");
    assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
    assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
    assertThat(span.getParentSpanId()).isEqualTo("b7ad6b7169203331");
  }

  @Test
  void notFoundAndStatusRequestsAreTracedWithTheSameSpanName() throws Exception {
    mvc.perform(get("/missing.png")).andExpect(status().isNotFound());
    mvc.perform(get("/status")).andExpect(status().isOk());

    await().untilAsserted(() -> assertThat(serverSpans()).hasSize(2));
    assertThat(serverSpans()).extracting(SpanData::getName).containsOnly("image-provider");
  }

  private List<SpanData> serverSpans() {
    return spans.getFinishedSpanItems().stream().filter(s -> s.getKind() == SpanKind.SERVER).toList();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(InMemorySpanExporter spanExporter) {
      return customizer ->
          customizer.addTracerProviderCustomizer(
              (tracerProvider, config) ->
                  tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)));
    }
  }
}
