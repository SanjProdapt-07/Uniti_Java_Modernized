# Porting notes: ad (plain grpc-java + OTel agent -> Spring Boot 4.1 + Spring gRPC)

Original: `opentelemetry-demo/src/ad` (Java 21, plain grpc-java, log4j2, OTel Java agent).
The original had **no tests**, so there was nothing to port; all tests here are new.

## Behavior mapping

| Concern | Original | Java / Spring Boot port |
|---|---|---|
| gRPC contract | `oteldemo.AdService/GetAds` from `pb/demo.proto` | Same proto, compiled via `protoSourceDir`; `AdService extends AdServiceImplBase` as a `@Service` |
| Port | `AD_PORT` (required, startup fails if unset) | `spring.grpc.server.port=${AD_PORT:9555}` (see deviations) |
| Health | `HealthStatusManager`, `""` set to SERVING on start | Spring gRPC health auto-configuration registers `grpc.health.v1.Health` (seen in the startup log); `AdServiceIntegrationTests.healthServiceReportsServing` checks `""` is SERVING |
| Ad data | `ImmutableListMultimap`: binoculars, telescopes, accessories (3), assembly, travel; `books` empty | Same ads/texts/URLs and iteration order in a `LinkedHashMap` (Guava dropped) |
| Selection | context keys -> ads by category; no keys -> 2 random ads; empty result -> 2 random ads | Same, including `TARGETED/NOT_TARGETED` and `TARGETED/RANDOM` classification |
| Flags | `adHighCpu` (evaluated first), `adFailure` (1/10 -> `UNAVAILABLE`), `adManualGc`; context = baggage `session.id` targeting key + `session` | Same keys, order, defaults (`false`) and evaluation context |
| Failure handling | `StatusRuntimeException` -> span event `Error{exception.message}`, status ERROR, warn log, `onError` | Identical |
| Spans | `getAds` attributes on the server span: `session.id`, `enduser.id`, `demo.ad.context_keys`, `.context_keys.count`, `demo.ad.count`, `demo.ad.request_type`, `demo.ad.response_type`; child spans `getAdsByCategory` (`demo.ad.category`, `demo.ad.count`) and `getRandomAds` (`demo.ad.count`) | Same; server span now created by `GrpcTelemetry` server interceptor (`GrpcTelemetryConfiguration`) instead of the agent |
| OTel metric | `demo.ad.requests` counter (`demo.ad.request_type`, `demo.ad.response_type`) | Same name, description and attributes |
| Prometheus metric | `demo_ad_served_total{category}` via prometheus-metrics-core on an `HTTPServer` at `AD_PROMETHEUS_PORT` (default 9465), scraped by the collector `prometheus/ad` receiver | Same library, metric, help text and port; kept as a Spring-managed `HTTPServer` (`PrometheusConfiguration`) backed by a dedicated `PrometheusRegistry` bean (original used the global default registry; the exposed output is the same single metric). No collector/compose change needed |
| Problem patterns | `CPULoad`, `GarbageCollectionTrigger`, `MemoryUtils` | Same logic in `oteldemo.ad.problempattern`; log4j -> slf4j; small test seams added (see below) |
| Feature flag provider | `FlagdProvider` with `withGlobalTelemetry(true)` | `FlagdProvider` with the injected `OpenTelemetry` + OpenFeature `TracesHook`, as in `recommendation` (the hook is new, requested by the standards) |
| Logs | log4j2 pattern `%d{yyyy-MM-dd HH:mm:ss} - %logger{36} - %msg trace_id=... span_id=... trace_flags=...`; agent exported logs to OTLP | `logback-spring.xml` with the same pattern; the starter installs the logback MDC and OpenTelemetry appenders (`OTEL_LOGS_EXPORTER=otlp` from compose keeps working). A test run showed the line format with real trace ids |
| Telemetry bootstrap | OTel Java agent downloaded in the Dockerfile (`OTEL_JAVA_AGENT_VERSION`) | `opentelemetry-spring-boot-starter` 2.31.1 (same version as the agent in `.env`); agent download removed |
| JVM | `JAVA_TOOL_OPTIONS=-javaagent... -Xmx200m` | `-Xmx200m` kept (the GC pattern is sized against the heap) |

## Deviations / notes

* `AD_PORT` now defaults to `9555` (the Dockerfile `ARG` default and `.env` value) instead of failing when unset.
* `@WithSpan("getAdsByCategory")` / `@SpanAttribute` replaced by an explicit span, because Spring only
  weaves `@WithSpan` into proxied calls and this method is invoked from inside the same bean. The span
  carries `demo.ad.category`, plus `code.namespace` / `code.function` that the agent annotation support
  used to add. The `code.*` attribute names are from memory of the agent's behavior and were not compared
  against a running agent; `code.namespace` is now `oteldemo.ad.AdService` (package changed).
* Agent-provided telemetry that is no longer produced automatically: JVM/runtime metrics, other
  auto-instrumentation (e.g. the agent's gRPC client/Netty/etc. spans beyond the gRPC server interceptor
  and the flagd client instrumentation). The starter provides the Spring Boot/OTel SDK wiring, not the
  agent's full instrumentation set. Not measured against the original.
* Preserved quirk: `adManualGc` creates a **new** `GarbageCollectionTrigger` per request, so its 10 s
  throttle (`lastGC`) never takes effect, exactly as in the original.
* Preserved quirk: `MemoryUtils` debug message `"Current heap usage is {0} percent" + ...` is unchanged.
* Package is now `oteldemo.ad` (and `oteldemo.ad.problempattern`); the generated gRPC/proto classes remain in `oteldemo`.
* `CPULoad.getInstance()` is now `synchronized`; `CPULoad.runningWorkerCount()`, a package-private
  `GarbageCollectionTrigger` constructor, and a package-private `AdService` constructor (random source,
  CPU load, GC factory) were added only so tests can observe/mock behavior.
* Guava and log4j2 dependencies removed; the original `.java-version`, google-java-format plugin and
  `installDist` start-script setup were dropped in favor of `bootJar` (`build/libs/ad.jar`).
* `GarbageCollectionTrigger` uses `finalize()` (deprecated for removal) as the original did; it still works on Java 21.

## Verified

* `bootJar` and `test` pass with the helper script (42 tests: 12 gRPC integration tests with the
  OTel starter and in-memory flag provider/exporters, 18 Mockito unit tests of `getAds`, 12 for the
  problem patterns).
* The built jar was started locally with `AD_PORT`/`AD_PROMETHEUS_PORT` set: Spring Boot 4.1.2 started,
  registered `oteldemo.AdService` and `grpc.health.v1.Health`, bound the gRPC port and the Prometheus port.

## NOT verified

* The Docker image was **not built** (no Docker on this machine); the Dockerfile follows the
  recommendation service pattern but was never executed.
* Nothing was run against a real flagd, OTel collector, Jaeger/Prometheus/Grafana or the frontend; with no
  flagd reachable the provider logged an initialization timeout and flags fall back to defaults.
* The `adManualGc` (real heap exhaustion with finalizer-delayed objects) and `adHighCpu` effects on a
  200 MB heap inside a 300 MB container were not exercised; unit tests mock the memory bean. Whether a
  Spring Boot process fits the compose `memory: 300M` limit with `-Xmx200m` is unmeasured.
* Exact log/metric/trace output on the wire to the collector (OTLP) was not inspected; spans and metrics
  were checked only through in-memory exporters, and the `/metrics` output only in a test.
* An empty Prometheus counter exposes no samples until the first ad is served (same as the original).

## Shared changes requested (not made)

* `compose.yaml`: remove `build.args.OTEL_JAVA_AGENT_VERSION` from the `ad` service (the Dockerfile no
  longer has that `ARG`/agent download). `.env` `OTEL_JAVA_AGENT_VERSION` is still used by other services.
* `src/recommendation/README.md` still says the ad service uses the OpenTelemetry Java agent; that is no
  longer true.
