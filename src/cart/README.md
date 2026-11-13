# Cart Service

This service stores user shopping carts in Valkey.

It is a Spring Boot 4 application (Java 21). The gRPC API (`oteldemo.CartService`
and `grpc.health.v1.Health`) is served with Spring gRPC, the carts are stored with
the Lettuce Valkey client, and the telemetry comes from the OpenTelemetry Spring
Boot starter plus the OpenTelemetry gRPC and Lettuce instrumentation libraries, so
no Java agent is involved.

## Configuration

| Environment variable | Meaning |
|---|---|
| `CART_PORT` | gRPC port, `7070` by default |
| `VALKEY_ADDR` | `host[:port]` of Valkey. Required: the service exits with an error when it is missing |
| `VALKEY_PASSWORD` | Valkey password, optional |
| `VALKEY_TLS` | `true` to connect with TLS, `false` by default |
| `FLAGD_HOST`, `FLAGD_PORT` | flagd, for the feature flags |
| `OPAMP_SERVER_ENDPOINT` | OpAMP server WebSocket URL; the OpAMP client is only started when this is set |
| `OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY` | `true` to skip the certificate check of the OpAMP server |
| `OTEL_*` | standard OpenTelemetry SDK settings |

## Feature flags

* `cartFailure` (number): the share of `EmptyCart` calls that are sent to a store
  pointing at the unreachable `badhost:1234` and so fail with `FAILED_PRECONDITION`.
* `cartMetricCardinalityOverflow` (boolean): see below.
* `failedReadinessProbe` (boolean): the health check reports `NOT_SERVING` while on.

## Local Build

Requires JDK 21 or newer. The protobuf classes are generated from
`../../pb/demo.proto` as part of the Gradle build:

```sh
./gradlew build
```

## Docker Build

From the root directory of this repository, run:

```sh
docker compose build cart
```

## Metric cardinality overflow

Enable `cartMetricCardinalityOverflow` in the feature flag UI to deliberately add
the current trace id as `demo.cart.trace_id` on the get-cart latency metric.
After enough distinct traces exceed the SDK limit (2,000 by default), view the
**Metric cardinality overflow** section of the **Self-Observability** dashboard.
This simulates an incorrect attempt to correlate metrics with traces; use exemplars
instead (the OpenTelemetry SDK's default exemplar filter is trace based). The flag
is off by default.

Turning the flag off stops adding trace IDs, but cumulative overflow can remain
visible. Restart cart with the flag off to reset SDK state; historical overflow
remains visible in dashboard time ranges that include it.

See [PORTING-NOTES.md](PORTING-NOTES.md) for how this service maps to the original
.NET one and what has not been verified.
