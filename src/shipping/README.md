# Shipping Service

The Shipping service queries `quote` for price quote, provides tracking IDs,
and the impression of order fulfillment & shipping processes.

It is a Spring Boot 4 / Java 21 application (Spring Web MVC) that replaces the
former Rust (actix-web) implementation with the same HTTP API:

| Route                  | Description                                                              |
|------------------------|--------------------------------------------------------------------------|
| `POST /get-quote`      | Sums the item quantities, asks `quote` (`POST $QUOTE_ADDR/getquote`) and answers `{"cost_usd":{"currency_code":"USD","units":..,"nanos":..}}` |
| `POST /ship-order`     | Answers `{"tracking_id":"<uuid>"}`                                       |
| `GET /health`          | Empty `200 OK`                                                           |

Telemetry comes from the OpenTelemetry Spring Boot starter (no Java agent).

## Local Build

Requires JDK 21 or newer.

```sh
./gradlew build
```

## Docker Build

From the root directory, run:

```sh
docker compose build shipping
```

## Test

```sh
./gradlew test
```

## Feature Flags

* `intlShippingSlowdown`: integer flag (default 0). When non-zero, non-US
  shipping requests are delayed by the flag's value in seconds to simulate
  overseas shipping latency. US addresses are never affected.

## Environment Variables

| Variable        | Default              | Description                                     |
|-----------------|----------------------|-------------------------------------------------|
| `SHIPPING_PORT` | `50050`              | Port the HTTP server listens on                 |
| `QUOTE_ADDR`    | `http://quote:8090`  | Base URL of the quote service                   |
| `IPV6_ENABLED`  | `false`              | `true` binds to `[::]` instead of `0.0.0.0`     |
| `FLAGD_HOST`    | `localhost`          | Hostname of the flagd service (flagd provider)  |
| `FLAGD_PORT`    | `8013`               | Port of the flagd RPC API (flagd provider)      |
| `OTEL_*`        |                      | Standard OpenTelemetry SDK settings             |

See [PORTING-NOTES.md](PORTING-NOTES.md) for the differences to the Rust version.
