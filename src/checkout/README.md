# Checkout Service

This service provides checkout services for the application: the
`oteldemo.CheckoutService/PlaceOrder` gRPC call. It reads the cart, prices the
items, asks for a shipping quote, charges the card, ships the order, empties the
cart, sends the confirmation email and publishes the order to Kafka.

It is a Spring Boot 4.1 application (Java 21). The gRPC API is served with
Spring gRPC (`spring-boot-starter-grpc-server`) and the downstream gRPC services
(cart, product catalog, currency, payment) are called with
`spring-boot-starter-grpc-client`. Shipping and email are HTTP services, called
with `RestClient`. The order event is produced with Spring for Apache Kafka. The
telemetry comes from the OpenTelemetry Spring Boot starter plus the
OpenTelemetry gRPC instrumentation library, so no Java agent is involved.
Feature flags are read from flagd through the OpenFeature SDK.

See [PORTING-NOTES.md](PORTING-NOTES.md) for how the Go implementation maps to
this one, the known differences and what has not been verified.

## Configuration

| Variable | |
|---|---|
| `CHECKOUT_PORT` | gRPC port (required) |
| `CART_ADDR`, `CURRENCY_ADDR`, `PAYMENT_ADDR`, `PRODUCT_CATALOG_ADDR` | `host:port` of the gRPC services (required) |
| `SHIPPING_ADDR`, `EMAIL_ADDR` | base URLs of the HTTP services, for example `http://shipping:50050` (required) |
| `KAFKA_ADDR` | Kafka bootstrap servers; without it no order event is produced |
| `KAFKA_TOPIC` | topic of the order event, `orders` by default |
| `FLAGD_HOST`, `FLAGD_PORT` | flagd (read by the flagd OpenFeature provider) |
| `OTEL_*` | standard OpenTelemetry SDK configuration |

Feature flags used: `emitRawPii`, `kafkaQueueProblems`, `paymentUnreachable`
(see `flags.json`).

## Local Build

Requires JDK 21 or newer. The protobuf classes are generated from
`../../pb/demo.proto` as part of the Gradle build:

```sh
./gradlew build
```

## Docker Build

From the root directory, run:

```sh
docker compose build checkout
```

## Regenerate protos

There is no generated code to commit: the Gradle build compiles
`pb/demo.proto` (`-PprotoSourceDir` points it elsewhere, as the Dockerfile does).

## Bump dependencies

Update the versions in `build.gradle` (the Spring Boot plugin version and the
OpenTelemetry/OpenFeature versions at the top).
