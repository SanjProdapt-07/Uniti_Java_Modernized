# Accounting Service

This service consumes new orders from a Kafka topic and stores them in the
`accounting` schema of the PostgreSQL database (`order`, `orderitem` and
`shipping` tables).

It is a Spring Boot 4 (Java 21) application using Spring for Apache Kafka
(`@KafkaListener`) and plain JDBC. Telemetry comes from the OpenTelemetry Spring
Boot starter (Kafka consumer spans with trace context propagated from the record
headers, JDBC spans, logs) plus a manual `order-consumed` span. It replaces the
former .NET implementation with the same behavior.

## Configuration

| Variable | Meaning |
|---|---|
| `KAFKA_ADDR` | Kafka bootstrap servers (required) |
| `KAFKA_TOPIC` | Topic to consume, default `orders` |
| `DB_CONNECTION_STRING` | Database, Npgsql format as before, for example `Host=astronomy-db;Username=astronomy_user;Password=...;Database=astronomy_db` (a `jdbc:` URL is accepted too). Without it orders are only logged. |
| `OTEL_*` | Standard OpenTelemetry SDK configuration |

The consumer group is `accounting`, offsets start at the earliest position and
are committed automatically.

## Local Build

Requires JDK 21 or newer. The protobuf classes are generated from
`../../pb/demo.proto` as part of the Gradle build:

```sh
./gradlew build
```

The tests run offline: they use an embedded Kafka broker and an in-memory H2
database in PostgreSQL mode.

## Docker Build

From the root directory, run:

```sh
docker compose build accounting
```

See [PORTING-NOTES.md](PORTING-NOTES.md) for the behavior mapping from the .NET
service.
