# Porting notes: image-provider (nginx to Spring Boot 4.1)

Original: nginx 1.29 + `ngx_otel_module` (`Dockerfile`, `nginx.conf.template`, `static/`).
Port: Spring Boot 4.1.2, Java 21, Spring Web MVC, OpenTelemetry Spring Boot starter 2.31.1.

## Behavior mapping

| nginx | Java |
|---|---|
| `listen ${IMAGE_PROVIDER_PORT}` (8081) | `server.port: ${IMAGE_PROVIDER_PORT:8081}` |
| `root /static;` | resource handler `/**` over `image-provider.static-root` (`IMAGE_PROVIDER_STATIC_ROOT`, default `file:./static/`); same files, byte for byte, in `static/` |
| `include mime.types` | Spring `MediaTypeFactory` (`image/png`, `image/jpeg`; test-verified for all 12 shipped files) |
| `sendfile on`, `Last-Modified`, range requests, `If-Modified-Since` | provided by `ResourceHttpRequestHandler` (tested: 200, 206, 304, HEAD) |
| no `Cache-Control`, no CORS headers in the nginx config | none added (tested) |
| `gzip_static on` | `EncodedResourceResolver` serves `<file>.gz` to clients accepting gzip (tested). No `.gz` files are shipped, as before |
| `autoindex off` | `/` and `/dir/` answer 403; `/dir` answers 301 to `/dir/` (tested) |
| `server_tokens off` | no `Server` header at all (nginx sent `Server: nginx`) |
| nginx error pages | 404/403/... returned as the same small HTML page (`<hr><center>nginx</center>`), test-verified on the real server |
| `location /status { stub_status on; }` | `GET /status` in the `stub_status` text layout. Counters are per HTTP request: "accepts", "handled" and "requests" are all the total request count, "Reading" is always 0, "Writing" = in-flight requests (see deviations) |
| `otel_trace on; otel_trace_context propagate` | OTel starter: W3C trace context + baggage extracted from incoming requests (test: trace id and parent span id continue the caller's) |
| `otel_span_name image-provider` | every server span is renamed `image-provider` after the request (tested for 200, 404 and `/status`) |
| `otel_service_name ${OTEL_SERVICE_NAME}` | `OTEL_SERVICE_NAME` (default `spring.application.name=image-provider`) |
| `otel_exporter endpoint ${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT_GRPC}` | `otel.exporter.otlp.endpoint=http://${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT_GRPC}`, protocol `grpc`; `OTEL_EXPORTER_OTLP_*` still override |
| access log to stdout (image default) | Tomcat access log, combined pattern, to `/dev/stdout`, enabled through env in the Dockerfile |
| `OTEL_RESOURCE_ATTRIBUTES` (compose, e.g. `service.criticality=low`) | honored by the OTel SDK (not nginx before: the nginx module ignored it) |

## Deviations / differences

* Server span attributes are those of the OTel servlet instrumentation (`http.request.method`, `url.path`, ...),
  not those of the nginx module. Span name and kind (SERVER) match.
* `/status` counters are an approximation of `stub_status` (no connection-level accounting in Tomcat).
* Memory: nginx ran in 120M. A JVM needs more; the Dockerfile caps heap at 64m but the compose limit of
  `deploy.resources.limits.memory: 120M` is likely too small (see requested shared changes in the report).
* The 301 `Location` for `/dir` is built from the request's scheme/host/port, similar to nginx.
* `HEAD`/`GET` only (like nginx, other methods get a 4xx; the exact code was not compared with nginx).

## Not verified

* The Docker image build and the container start (no Docker here). Tomcat's stdout access-log settings
  (`/dev/stdout` through env vars) were not exercised.
* `wget` availability in `eclipse-temurin:21-jre` for the compose health check (`wget --spider .../status`).
* Real OTLP export to the collector, end to end through Envoy (`/images/...`).
* Memory behavior under the compose limit.
* Behavior against a real nginx side by side; the mapping above follows the nginx documentation and the
  config file, not a recorded comparison.

## Tests

`ImageProviderTests` (MockMvc, 27 incl. one parameterized case per shipped file), `RealServerTests` (embedded
Tomcat, 6), `GzipStaticTests` (2): 35 tests, all passing with `bootJar test`.
