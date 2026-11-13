# Ad Service

The Ad service provides advertisement based on context keys. If no context keys
are provided then it returns random ads.

It is a Spring Boot 4.1 application (Java 21). The gRPC API (`oteldemo.AdService/GetAds`)
and the standard `grpc.health.v1.Health` service are served with Spring gRPC
(`spring-boot-starter-grpc-server`). Telemetry comes from the OpenTelemetry Spring Boot
starter plus the OpenTelemetry gRPC instrumentation library, so **no Java agent is
involved**. Feature flags are read from flagd through OpenFeature.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `AD_PORT` | `9555` | gRPC port |
| `AD_PROMETHEUS_PORT` | `9465` | port of the Prometheus `/metrics` endpoint |
| `FLAGD_HOST`, `FLAGD_PORT` | flagd provider defaults | flagd location (read by the flagd provider) |
| `OTEL_*` | | standard OpenTelemetry SDK environment variables |

Feature flags: `adHighCpu` (burns CPU on 4 daemon threads while on), `adFailure` (one
request in ten fails with `UNAVAILABLE`) and `adManualGc` (forces an expensive
garbage-collection cycle).

## Building Locally

Requires JDK 21 or newer. The protobuf classes are generated from
`../../pb/demo.proto` as part of the Gradle build:

```sh
./gradlew bootJar test
```

To run the Ad Service:

```sh
export AD_PORT=8080
export FLAGD_HOST=localhost
java -jar build/libs/ad.jar
```

### Upgrading Gradle

If you need to upgrade the version of gradle then run

```sh
./gradlew wrapper --gradle-version <new-version>
```

## Building Docker

From the root of the repository, run:

```sh
docker compose build ad
```

## Custom metrics: bridging Prometheus to OpenTelemetry

The Ad service intentionally emits custom metrics in **two different ways**:

1. **OpenTelemetry SDK** (recommended): `demo.ad.requests` is created through the
   OpenTelemetry API and exported to the OTel Collector over OTLP.
2. **Prometheus client library**: `demo_ad_served_total{category}` is created with the
   `io.prometheus:prometheus-metrics-core` library and exposed on a separate HTTP
   endpoint (`AD_PROMETHEUS_PORT`, default `9465`). The OTel Collector's `prometheus/ad`
   receiver scrapes this endpoint and forwards the metric into the same metrics pipeline.

The Prometheus-client path is included to demonstrate a **common pattern during
OpenTelemetry adoption**: organizations frequently already own a significant amount of
Prometheus instrumentation (in libraries, third-party exporters, or legacy services) and
want to ingest those metrics into an OpenTelemetry-native pipeline without rewriting
everything up front. The Collector's `prometheus` receiver is the bridge that makes this
possible.

**Recommendation**: this is a *transitional* pattern. Prefer the OpenTelemetry SDK for
new custom metrics, and migrate existing Prometheus-client metrics to it.
