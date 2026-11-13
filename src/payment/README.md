# Payment Service

This service is responsible for processing and validating payments through the
application. It implements `oteldemo.PaymentService/Charge` from
[`pb/demo.proto`](../../pb/demo.proto).

It is a Spring Boot 4 application (Java 21). The gRPC API is served with Spring
gRPC (`spring-boot-starter-grpc-server`), the telemetry comes from the
OpenTelemetry Spring Boot starter plus the OpenTelemetry gRPC instrumentation
library (no Java agent), and feature flags are evaluated with the OpenFeature
Java SDK and the flagd provider.

Only VISA and MasterCard are accepted. The `paymentFailure` feature flag makes
a share of the charges fail, and `emitRawPii` adds the raw card number and CVV
to the `charge` span. See [PORTING-NOTES.md](PORTING-NOTES.md) for how the
behavior maps to the original Node.js service.

Environment variables: `PAYMENT_PORT` (default `50051`), `IPV6_ENABLED`
(`true` binds `[::]` instead of `0.0.0.0`), `FLAGD_HOST`, `FLAGD_PORT`,
`OTEL_*`.

## Local Build

Requires JDK 21 or newer. The protobuf classes are generated from
`../../pb/demo.proto` as part of the Gradle build:

```sh
./gradlew build
```

## Docker Build

From the root directory, run:

```sh
docker compose build payment
```
