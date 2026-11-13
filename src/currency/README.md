# Currency Service

The Currency Service converts amounts from one currency to another. It is a
Spring Boot 4 application (Java 21) that serves the `oteldemo.CurrencyService`
gRPC API (`GetSupportedCurrencies`, `Convert`) with Spring gRPC, plus the
standard `grpc.health.v1.Health` service. It replaces the former C++
implementation without changing the contract, port, or telemetry names.

Telemetry comes from the OpenTelemetry Spring Boot starter and the
OpenTelemetry gRPC instrumentation library; no Java agent is involved.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `CURRENCY_PORT` | `7001` | gRPC listen port |
| `IPV6_ENABLED` | `false` | `true` listens on `[::]` instead of `0.0.0.0` |
| `VERSION` | `unknown` | version of the `demo.exchange.conversions` meter |
| `OTEL_*` | | standard OpenTelemetry SDK variables; the OTLP protocol defaults to `grpc` |

See [PORTING-NOTES.md](PORTING-NOTES.md) for the behavior mapping and the
quirks of the conversion arithmetic that were kept on purpose.

## Local Build

Requires JDK 21 or newer. The protobuf classes are generated from
`../../pb/demo.proto` as part of the Gradle build:

```sh
./gradlew build
```

## Docker Build

From the root directory, run:

```sh
docker compose build currency
```

## Run the service

```sh
docker compose up currency
```
