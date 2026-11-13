# Java / Spring Boot Migration Standards

This tree (`otel-demo-java`) is the Java/Spring Boot modernization of the OpenTelemetry Demo.
The untouched original lives in `d:\dev2\mordernization\opentelemetry-demo` and is the **behavioral reference**:
read the original source from there, write only into this tree.

## Goal

Every migrated service keeps **identical externally observable behavior**: same contract (gRPC / HTTP / Kafka),
same ports and env vars, same feature-flag behavior, same telemetry (span names, attributes, metrics names, log
fields), same error codes, same data. "Same flow and quality" is the acceptance bar. Where exact parity is
impossible, record it in the service's `PORTING-NOTES.md` — never silently drop behavior.

## Targets (from the migration tables)

| Contract | Target |
|---|---|
| gRPC services (ad, recommendation, cart, checkout, currency, payment, product-catalog) | Java 21 / Spring Boot 4.1.x + `spring-boot-starter-grpc-server` (checkout also `spring-boot-starter-grpc-client`) |
| HTTP services (email, quote, shipping) | Java 21 / Spring Boot 4.1.x + Spring Web MVC (`@RestController`) |
| Kafka consumers (accounting, fraud-detection) | Java 21 / Spring Boot 4.1.x + Spring for Apache Kafka (`@KafkaListener`) |
| Application services (frontend, image-provider, flagd-ui, agent, chatbot, mcp, load-generator) | Java 21 / Spring Boot 4.1.x (Web MVC / Thymeleaf / Spring AI as fits; see each service's brief) |

Not migrated (kept as-is): frontend-proxy (Envoy), flagd, kafka, postgresql, otel-collector, jaeger, grafana,
prometheus, opensearch, opamp-server, react-native-app, and all repo-level config.

## Same for every service

| Concern | Standard |
|---|---|
| Build | Gradle **Groovy DSL**, Spring Boot **4.1.x** plugin (reference: `src/recommendation/build.gradle`), `options.release = 21` |
| Telemetry | `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` (no Java agent, no manual SDK bootstrap). gRPC services add `opentelemetry-grpc-1.6` + the `GrpcTelemetryConfiguration` pattern. Keep `OTEL_*` env var names working. Preserve custom spans, attributes and metrics. |
| Feature flags | OpenFeature Java SDK (`dev.openfeature:sdk`) + flagd provider (`dev.openfeature.contrib.providers:flagd`) + OTel traces hook, wired as in `FeatureFlagConfiguration` of recommendation. Same flag keys, same semantics. |
| Tests | JUnit 5 + Mockito. Port every existing original test; add tests for each behavior you port. Must run offline of Docker (no Testcontainers; use embedded/in-memory alternatives or mocks). |
| Container | Multi-stage `Dockerfile` at the same path as the original (`src/<service>/Dockerfile`, build context = repo root): builder `eclipse-temurin:21-jdk`, runtime `eclipse-temurin:21-jre`. Same exposed port env var. Use the recommendation Dockerfile as the pattern. |

## Layout per service

```
src/<service>/
  build.gradle  settings.gradle  gradlew  gradlew.bat  gradle/wrapper/*
  Dockerfile
  README.md            (updated for the Java service)
  PORTING-NOTES.md     (behavior mapping, deviations, what was NOT verified and why)
  src/main/java/...    src/main/resources/application.yaml    src/test/java/...
```

* Java package: `oteldemo.<service>` (match the recommendation service). Keep the license header
  (`Copyright The OpenTelemetry Authors / SPDX-License-Identifier: Apache-2.0`).
* Shared protos stay in `/pb/demo.proto`; services compile them via the `protoSourceDir` pattern in
  recommendation's `build.gradle`.
* Data files the service needs at runtime (e.g. product catalog JSON) stay with the service.
* Remove the old-language source files in the service directory once ported (the tree is a copy; the
  original is preserved in `opentelemetry-demo`). Keep non-code assets that are still used.
* Config via `application.yaml` reading the **same env var names** as the original (see `compose.yaml`
  and `.env`), with the same defaults.
* gRPC health: keep the standard `grpc.health.v1` health service available where the original had it.

## Building and testing locally

No Docker is available on this machine. Use the helper (JDK 21 + Gradle 9.9, serialized to protect RAM):

```
powershell.exe -NoProfile -File "<scratchpad>\jgradle.ps1" "<service-dir>" <gradle args>
# e.g. ... jgradle.ps1 D:\dev2\mordernization\otel-demo-java\src\cart bootJar test
# generate the wrapper for a new service:  ... jgradle.ps1 <service-dir> wrapper --gradle-version 9.9.0
```

`<scratchpad>` = `C:\Users\sanjay.h\AppData\Local\Temp\claude\9330f0d3d3df4581\3d509f84-6eab-4e52-8c29-0f3be481a27d\scratchpad`.
Builds are limited to 2 concurrent machine-wide; they may wait for a slot.

## Definition of done (per service)

1. `bootJar` and `test` pass with the helper.
2. Contract identical to the original (proto/HTTP routes/Kafka topic + message format).
3. Env vars/ports identical to the original; `compose.yaml` wiring unchanged or minimally noted.
4. Feature flags, spans, attributes, metrics, logs preserved.
5. `PORTING-NOTES.md` written: behavior table (original → Java), deviations, and honest list of what could
   not be verified (e.g. real Docker image build, real Kafka/Valkey/Postgres/flagd integration).
