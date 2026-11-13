# Product Catalog Service

This service serves the product catalog over gRPC (`ListProducts`,
`GetProduct`, `SearchProducts` of `oteldemo.ProductCatalogService`). The products
live in the PostgreSQL database (`catalog.products`, created and filled by
`src/postgresql/init.sql`); the service has no product files of its own.

It is a Spring Boot 4 / Java 21 application that replaces the former Go
implementation with the same contract. The gRPC API is served with Spring gRPC
(including the standard `grpc.health.v1` health service and server reflection),
the database is accessed with Spring JDBC (HikariCP + the PostgreSQL JDBC
driver), and the telemetry comes from the OpenTelemetry Spring Boot starter plus
the OpenTelemetry gRPC, JDBC (with SQLCommenter) and HikariCP instrumentation
libraries, so no Java agent is involved.

Feature flags (flagd, through OpenFeature):

* `productCatalogFailure`: when on, `GetProduct` fails with `INTERNAL`
  (evaluated with the `product_id` of the request, so flagd targeting rules can
  single out a product).
* `productCatalogLockContention`: when on, a transaction takes an
  `ACCESS EXCLUSIVE` lock on `catalog.products` for 30 seconds (checked every
  10 seconds), which stalls every other query on the table.

## Environment Variables

| Variable                                | Default        | Description                                                           |
|-----------------------------------------|----------------|-----------------------------------------------------------------------|
| `PRODUCT_CATALOG_PORT`                  | `3550`         | gRPC port                                                             |
| `DB_CONNECTION_STRING`                  | (required)     | `postgres://user:password@host[:port]/db?sslmode=disable`, a libpq `key=value` string, or a `jdbc:` URL |
| `FLAGD_HOST` / `FLAGD_PORT`             | `localhost` / `8013` | flagd (read by the flagd provider)                              |
| `OPAMP_SERVER_ENDPOINT`                 | (unset)        | enables the OpAMP client, e.g. `wss://opamp-server:4320/v1/opamp`     |
| `OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY` | `false`        | skip the TLS verification of the OpAMP server (self-signed certificate) |
| `OTEL_*`                                |                | standard OpenTelemetry SDK settings                                   |

## Local Build

Requires JDK 21 or newer. The protobuf classes are generated from
`../../pb/demo.proto` as part of the Gradle build:

```sh
./gradlew build
```

## Docker Build

From the root directory, run:

```sh
docker compose build product-catalog
```

## Test

```sh
./gradlew test
```

The tests use an in-memory H2 database in PostgreSQL mode. To run
`PostgresIntegrationTests` against a real PostgreSQL that was initialised with
`src/postgresql/init.sql`, set `PRODUCT_CATALOG_TEST_DB`, for example:

```sh
PRODUCT_CATALOG_TEST_DB='postgres://astronomy_user:astronomy_password@127.0.0.1:5432/astronomy_db?sslmode=disable' ./gradlew test
```

## Database Calls Instrumentation

PostgreSQL queries are instrumented with the OpenTelemetry JDBC library, with
SQLCommenter enabled to append the trace context to the SQL statements (for
example `traceparent` key-value pairs in a query comment), see
`otel.instrumentation.jdbc.experimental.sqlcommenter.enabled` in
`application.yaml`. Connection pool metrics come from the HikariCP
instrumentation.

See [PORTING-NOTES.md](PORTING-NOTES.md) for the differences to the Go version.
