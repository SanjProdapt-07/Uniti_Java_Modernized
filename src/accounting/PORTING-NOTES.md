# Porting notes: accounting (.NET 10 / EF Core / Confluent.Kafka -> Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/accounting` (`Program.cs`, `Consumer.cs`, `Entities.cs`, `Helpers.cs`,
`Log.cs`).

## Behavior mapping

| Original (.NET) | Java |
|---|---|
| Prints `Accounting service started`, then the relevant environment variables sorted by name (prefixes `DOTNET_`, `CORECLR_`, `OTEL_`, `KAFKA_`), secret-looking names masked as `[REDACTED]` (`Helpers.cs`) | `AccountingApplication` / `EnvironmentReport`: same message, prefixes `OTEL_` and `KAFKA_` (the .NET ones do not exist on a JVM), same masking regex and ordering |
| `KAFKA_ADDR` required, otherwise `InvalidOperationException` | `main()` throws `IllegalStateException("The KAFKA_ADDR environment variable is not set.")` |
| `KAFKA_TOPIC` default `orders` | `accounting.topic: ${KAFKA_TOPIC:orders}` |
| Confluent consumer: group `accounting`, `AutoOffsetReset=Earliest`, `EnableAutoCommit=true`, `string` key / `byte[]` value | Spring Kafka `@KafkaListener(groupId="accounting")`, same properties in `application.yaml`, `StringDeserializer` / `ByteArrayDeserializer` |
| Message value -> `OrderResult.Parser.ParseFrom` | `OrderResult.parseFrom`; classes generated from `/pb/demo.proto` by the protobuf Gradle plugin (`oteldemo.Demo.OrderResult`) |
| Log `accounting.order.received`: `Order details: {@OrderResult}.` | same message and event name (`event.name` log attribute), the order rendered like Google.Protobuf's diagnostic string (`ProtoDiagnosticFormat`) |
| `DB_CONNECTION_STRING` unset: order logged, nothing stored | `DatabaseConfiguration` is only active when it is set; `OrderConsumer` then skips the store |
| `DB_CONNECTION_STRING` (Npgsql format) | `NpgsqlConnectionString` translates it to a JDBC URL + credentials (Host/Server, Port, Username/User Id, Password/Pwd, Database, SSL Mode, quoted values); a `jdbc:` URL is also accepted |
| EF Core model: `accounting."order"(order_id)`, `accounting.shipping(...)`, `accounting.orderitem(...)`, snake case columns (`UseSnakeCaseNamingConvention`), one `SaveChanges()` = one transaction | `OrderStore`: explicit SQL against the same schema/table/column names, one JDBC transaction. Rows: order id; one orderitem per item (cost currency/units/nanos, product id, quantity, order id); shipping (tracking id, cost currency/units/nanos, street, city, state, country, zip, order id) |
| New `DbContext` (connection) per message | Hikari pool (max 2); started lazily so an unavailable DB does not stop the service from starting, only the individual orders fail (as before) |
| `DbUpdateException` caused by Postgres `23505` unique violation -> info `Duplicate order received, skipping.` (`accounting.order.duplicate_skipped`) | `OrderStore.isUniqueViolation` walks the cause chain for SQLSTATE `23505` -> same message and event name |
| Any other exception (bad protobuf, NullReference for a missing nested message, DB errors) -> error log `Order parsing failed:` with the exception (`accounting.order.parsing_failed`), consumption continues | same; missing shipping cost/address/item details and two items with the same product id are rejected before touching the DB so they also end up as `Order parsing failed:` and not as a duplicate |
| `ConsumeException` -> error log `Consume error: {reason}` (`accounting.kafka.consume_failed`), loop continues | `DefaultErrorHandler` with a logging recoverer and no retries (`KafkaErrorHandlingConfiguration`) |
| `Connecting to Kafka: {servers}` at startup, `Closing consumer` at shutdown | same messages (logger `Accounting.Consumer`) |
| ActivitySource `Accounting.Consumer`, span `order-consumed` (INTERNAL) | tracer `Accounting.Consumer`, span `order-consumed` (INTERNAL) around the processing of each record, made current so DB spans and logs are its children/correlated |
| Kafka and Npgsql spans/context propagation from .NET auto-instrumentation | OpenTelemetry Spring Boot starter: Kafka listener process span with the context extracted from the record headers (`traceparent`), `opentelemetry-jdbc` spans for the SQL statements |
| `OTEL_*` env vars, OTLP export of traces/metrics/logs | same names, read by the OpenTelemetry Spring Boot starter; logs exported through the OpenTelemetry logback appender |

## Deviations

* `order-consumed` spans only the processing of a record. The .NET service started the activity before
  the blocking `Consume()` call, so it also covered the wait for the next message and was not linked to the
  producer's trace.
* The `Order details` message uses the compact one-line JSON of protobuf's `JsonFormat` re-spaced to the
  .NET diagnostic style; numbers/escaping were not compared byte for byte against a real .NET run.
* The environment dump prints `[NAME, value]`; I believe `DictionaryEntry` prints that way in .NET but did not
  check against a running .NET service.
* `OTEL_DOTNET_AUTO_*` variables from `compose.full.yaml` have no meaning for the JVM (see requested shared
  changes).
* No JPA/Hibernate: plain JDBC is used (smaller footprint, explicit control of the SQL). The data model is
  unchanged.
* A JDBC unique-violation is recognised by SQLSTATE `23505` like the Npgsql check; the integration tests
  exercise this against H2 in PostgreSQL mode (which reports the same SQLSTATE), not against PostgreSQL.

## Tests

`./gradlew test`: 36 tests (embedded Kafka via spring-kafka-test and H2 in PostgreSQL mode, no Docker).
No tests existed for the original service. Covered: connection string translation, environment report,
diagnostic formatting, `OrderStore` (column names, rollback, duplicates, validation), consumer logging and
error paths with Mockito, and end-to-end listener runs (store, duplicate skip, garbage message, trace context
propagation from `traceparent`, `order-consumed` span, JDBC span, exported log records).

## Not verified

* The Docker image was **not** built (no Docker available here); the Dockerfile follows the recommendation
  service pattern.
* **Real PostgreSQL** was never used: SQL, `"order"` quoting and SQLSTATE handling are only proven against H2
  in PostgreSQL mode (the test schema uses `VARCHAR` instead of `TEXT`). The Npgsql-to-JDBC translation of the
  actual compose string is unit tested but no connection to the real `astronomy-db` was made.
* **Real Kafka broker / checkout producer** were not used, only the embedded broker. The real topic format
  (producer is checkout, raw protobuf in the value) is taken from the original consumer code.
* OTLP export to the collector was not exercised (tests use in-memory exporters).
* The built `bootJar` was started against an unreachable broker: it starts, logs `Connecting to Kafka`, and
  stays alive.

## Shared changes needed (not made here)

* `compose.full.yaml` `accounting`: memory limit `160M` is too low for a Spring Boot + Kafka JVM; use about `300M`.
* `OTEL_DOTNET_AUTO_TRACES_ENTITYFRAMEWORKCORE_INSTRUMENTATION_ENABLED` can be removed.
  `DB_CONNECTION_STRING` keeps its Npgsql format and works unchanged.
