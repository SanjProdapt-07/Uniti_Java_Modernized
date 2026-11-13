/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * The equivalent of Locust's {@code HttpSession} (a {@code requests.Session}): one per simulated user, with
 * its own cookie jar, redirect following, and Locust style statistics (name = path and query of the first
 * request, failure = connection error or status &gt;= 400).
 */
public class UserHttpSession {

  private static final int MAX_REDIRECTS = 30;

  private final HttpClient client;
  private final Supplier<String> host;
  private final String userAgent;
  private final RequestStats stats;
  private final LoadGeneratorMetrics metrics;
  private final JsonMapper mapper;
  private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

  UserHttpSession(
      HttpClient client,
      Supplier<String> host,
      String userAgent,
      RequestStats stats,
      LoadGeneratorMetrics metrics,
      JsonMapper mapper) {
    this.client = client;
    this.host = host;
    this.userAgent = userAgent;
    this.stats = stats;
    this.metrics = metrics;
    this.mapper = mapper;
  }

  /** Result of a request; {@code status} is 0 for connection level errors, like Locust. */
  public record Response(int status, byte[] body, String error) {
    public boolean ok() {
      return error == null;
    }
  }

  public Response get(String urlOrPath) {
    return request("GET", urlOrPath, null, null);
  }

  /** GET with query parameters; {@code null} values are dropped like {@code requests} does. */
  public Response get(String urlOrPath, Map<String, List<String>> params) {
    return request("GET", urlOrPath, params, null);
  }

  public Response post(String urlOrPath, Object jsonBody) {
    return request("POST", urlOrPath, null, jsonBody);
  }

  private Response request(String method, String urlOrPath, Map<String, List<String>> params, Object json) {
    long start = System.nanoTime();
    String url = buildUrl(urlOrPath, params);
    String name = pathUrl(url);
    Response response;
    try {
      byte[] payload = json == null ? null : mapper.writeValueAsBytes(json);
      response = execute(method, url, payload);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SimulatedUserStopped();
    } catch (Exception e) {
      response = new Response(0, new byte[0], describe(e));
    }
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
    stats.log(method, name, elapsedMillis, response.body().length, response.error());
    metrics.recordRequest(method, response.error() == null, elapsedMillis);
    return response;
  }

  private Response execute(String method, String initialUrl, byte[] payload) throws Exception {
    String url = initialUrl;
    String currentMethod = method;
    byte[] body = payload;
    for (int hop = 0; ; hop++) {
      URI uri = URI.create(url);
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(60))
              .header("User-Agent", userAgent)
              .header("Accept", "*/*")
              .header("Accept-Encoding", "gzip, deflate");
      cookies.get(uri, Map.of()).forEach((k, values) -> values.forEach(v -> builder.header(k, v)));
      if (body != null) {
        builder.header("Content-Type", "application/json");
        builder.method(currentMethod, HttpRequest.BodyPublishers.ofByteArray(body));
      } else {
        builder.method(currentMethod, HttpRequest.BodyPublishers.noBody());
      }
      HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      cookies.put(uri, response.headers().map());

      int status = response.statusCode();
      boolean redirect = status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
      String location = response.headers().firstValue("Location").orElse(null);
      if (redirect && location != null && hop < MAX_REDIRECTS) {
        url = uri.resolve(location).toString();
        if (status == 303 || ((status == 301 || status == 302) && !"HEAD".equals(currentMethod))) {
          currentMethod = "GET";
          body = null;
        }
        continue;
      }
      byte[] content = decode(response);
      if (redirect && location != null) {
        return new Response(status, content, "TooManyRedirects: Exceeded " + MAX_REDIRECTS + " redirects.");
      }
      String error = status >= 400 ? status + " " + reason(status) + " for url: " + url : null;
      return new Response(status, content, error);
    }
  }

  private String buildUrl(String urlOrPath, Map<String, List<String>> params) {
    String url = urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://") ? urlOrPath : host.get() + urlOrPath;
    if (params != null) {
      StringBuilder query = new StringBuilder();
      params.forEach(
          (key, values) -> {
            for (String value : values) {
              if (value != null) {
                if (query.length() > 0) {
                  query.append('&');
                }
                query
                    .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
              }
            }
          });
      if (query.length() > 0) {
        url = url + (url.contains("?") ? "&" : "?") + query;
      }
    }
    return url;
  }

  /** The Locust stats name: path and query of the request URL. */
  static String pathUrl(String url) {
    try {
      URI uri = URI.create(url);
      String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
      return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    } catch (IllegalArgumentException e) {
      return url;
    }
  }

  private static byte[] decode(HttpResponse<byte[]> response) throws IOException {
    String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase();
    byte[] body = response.body();
    if (body == null || body.length == 0) {
      return new byte[0];
    }
    try {
      if (encoding.contains("gzip")) {
        return new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes();
      }
      if (encoding.contains("deflate")) {
        return new InflaterInputStream(new ByteArrayInputStream(body)).readAllBytes();
      }
    } catch (IOException e) {
      return body;
    }
    return body;
  }

  private static String reason(int status) {
    return org.springframework.http.HttpStatus.resolve(status) == null
        ? ""
        : org.springframework.http.HttpStatus.resolve(status).getReasonPhrase();
  }

  private static String describe(Exception e) {
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    return cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
  }
}
