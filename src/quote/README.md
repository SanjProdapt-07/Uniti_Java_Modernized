# Quote Service

The Quote Service calculates the shipping costs,
based on the number of items to be shipped.

It is a Spring Boot 4 (Java 21) service built with Spring Web MVC, instrumented
with the OpenTelemetry Spring Boot starter (automatic HTTP server spans) plus
manual instrumentation (the `calculate-quote` span, the `quotes` counter and
span events/attributes). It replaces the former PHP (Slim 4) implementation
with the same HTTP contract.

## API

`POST /getquote` with a JSON body such as `{"numberOfItems":3}` returns the
shipping cost as a bare JSON number (`26.97`). The service listens on
`QUOTE_PORT` (default `8090`) and on `[::]` instead of `0.0.0.0` when
`IPV6_ENABLED=true`.

## Local Build

Requires JDK 21 or newer:

```sh
./gradlew build
```

## Docker Build

To build the quote service, run the following from the root directory
of opentelemetry-demo

```sh
docker compose build quote
```

## Run the service

```sh
docker compose up quote
```

In order to get traffic into the service you have to deploy
the whole opentelemetry-demo.

Please follow the root README to do so.

## Development

Run the service locally:

```sh
cd src/quote
QUOTE_PORT=8999 ./gradlew bootRun
```

Then, send some curl requests:

```sh
curl --location 'http://localhost:8999/getquote' \
--header 'Content-Type: application/json' \
--data '{"numberOfItems":3}'
```

See [PORTING-NOTES.md](PORTING-NOTES.md) for the behavior mapping from the PHP
service.
