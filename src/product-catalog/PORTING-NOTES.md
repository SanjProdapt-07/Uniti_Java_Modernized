# Porting notes: product-catalog (Go -> Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/product-catalog` (`main.go`, `opamp.go`, `opamp_test.go`,
`flags/flags_gen.go`, `flags.json`, generated `genproto`). Products are **not** loaded from files: the Go
service reads them from PostgreSQL (`catalog.products`, created and filled by `src/postgresql/init.sql`).
There is no file fallback in the original, so there is none here.

## Behavior mapping

| Original (Go) | Java |
|---|---|
| gRPC `ListProducts`, `GetProduct`, `SearchProducts` (`oteldemo.ProductCatalogService`) | `ProductCatalogService`, same proto (`/pb/demo.proto`), Spring gRPC server |
| `PRODUCT_CATALOG_PORT` | `spring.grpc.server.port: ${PRODUCT_CATALOG_PORT:3550}` (Go had no default and only logged when unset; 3550 is the `.env` value) |
| `DB_CONNECTION_STRING` (lib/pq) | `db.connection-string: ${DB_CONNECTION_STRING:}`, converted by `ConnectionString` (postgres:// URL, libpq key/value, or `jdbc:` URL) into a HikariCP + pgjdbc pool. Missing variable or unreachable DB fails the start-up (Go: `os.Exit(1)`), message `DB_CONNECTION_STRING environment variable not set` kept |
| SQL: `SELECT ... FROM catalog.products ORDER BY p.id`, `WHERE LOWER(p.name) LIKE $1 OR LOWER(p.description) LIKE $1`, `WHERE p.id = $1` | identical statements via `JdbcTemplate` (`ProductRepository`) |
| search pattern `"%" + strings.ToLower(query) + "%"` (not escaped) | same (`toLowerCase(Locale.ROOT)`, not escaped) |
| categories: comma split + trim, empty string -> none | same |
| NULL in a text column -> Go `Scan` error | same: `converting NULL to string is unsupported` error path |
| `ListProducts` error -> `INTERNAL` `failed to load products: failed to query products: <err>` | same shape (`failed to get products from rows: failed to scan product row: ...` for row errors); `<err>` is the pgjdbc message, not the lib/pq one |
| `SearchProducts` error -> `INTERNAL` `failed to search products: ...` | same |
| `GetProduct`: any DB error -> `NOT_FOUND` `Product Not Found: <id>` | same (incl. non-"no rows" errors) |
| `productCatalogFailure` flag (boolean, default false), evaluated with context `{product_id: <id>}` (targetless) -> `INTERNAL` `Error: Product Catalog Fail Feature Flag Enabled` | same flag, same evaluation context (`ImmutableContext` without targeting key), same error |
| `productCatalogLockContention` flag, ticker every 10 s, `LOCK TABLE catalog.products IN ACCESS EXCLUSIVE MODE` in a transaction, hold 30 s, never two at a time, rollback | `LockContentionTrigger` (interval `product-catalog.lock-contention.interval`, hold `...hold`, defaults 10 s / 30 s); also releases the lock on shutdown |
| server span attributes `demo.product.count`, `demo.product.search.count`, `demo.product.id`, `demo.product.name`; events `Product Found`, `Product Not Found: <id>`, the failure-flag message; span status ERROR on errors | same, set on the gRPC server span (`Span.current()`) |
| logs `Found N products from database` (attr `products`), `Product Found` (attrs `demo.product.name`, `demo.product.id`), `Database connection established`, lock-contention messages | same messages; the fields are SLF4J key/value pairs exported as log attributes (`capture-key-value-pair-attributes`) |
| otelgrpc server handler, health checks filtered out | `opentelemetry-grpc-1.6` server interceptor, calls to `grpc.health.v1.Health` bypass it (`GrpcTelemetryConfiguration`) |
| otelsql spans + `WithSQLCommenter(true)` + `OmitRows`/`OmitConnResetSession` | `opentelemetry-jdbc` wrapping the `DataSource` (Spring Boot starter), SQLCommenter enabled; verified by a test that finds `traceparent` in the executed SQL (H2) |
| `otelsql.RegisterDBStatsMetrics` | HikariCP instrumentation (`db.client.connection.*` metrics) |
| `runtime.Start` runtime metrics | `opentelemetry-runtime-telemetry` (JVM metrics) |
| `otelslog` logger + otelconf SDK (OTLP gRPC to collector) | Logback appender + OpenTelemetry Spring Boot starter; `OTEL_*` variables honoured |
| grpc health server (`grpc.health.v1`) + reflection | Spring gRPC health + reflection services (tested) |
| flagd provider + OTel traces hook | `FlagdProvider` + `TracesHook` (`FeatureFlagConfiguration`) |
| OpAMP client (`opamp.go`): identity, `service.instance.id` shared with the resource, WebSocket client, TLS skip verify, health reporting | `OpampIdentity`, `OpampEnvironmentPostProcessor` (puts the instance id into `otel.resource.attributes.service.instance.id` before the SDK is built), `OpampClientLifecycle` (opentelemetry-java-contrib `opentelemetry-opamp-client` over OkHttp WebSocket); failures are logged and never stop the service |
| `flags.json` + generated `flags/flags_gen.go` (OpenFeature CLI) | removed; flag keys are constants in the Java sources |

## Tests

* `opamp_test.go` (3 tests) -> `OpampIdentityTests` (same three scenarios, plus the Spring environment wiring) and
  `OpampWiringTests` (the instance id really ends up in the SDK resource).
* New: `ProductCatalogServiceTests` (gRPC over the in-process transport + H2 in PostgreSQL mode: every RPC, error codes
  and messages, span attributes/events, flag behaviour, health, reflection, SQLCommenter), `ConnectionStringTests`,
  `LockContentionTriggerTests` (Mockito), `DatabaseConfigurationTests`, `OpampClientLifecycleTests`,
  `PostgresIntegrationTests` (opt-in, needs `PRODUCT_CATALOG_TEST_DB`, skipped otherwise).

## Deviations

1. **Span status description on INTERNAL errors.** The Go service set `span.SetStatus(Error, msg)` itself. The
   OpenTelemetry gRPC instrumentation sets the final status of a server span with a server-fault code (INTERNAL) itself,
   which replaces the description. The span is still ERROR and the gRPC status keeps the message; the description is
   only preserved for `NOT_FOUND`. For the failure-flag case the message is still recorded as a span event.
2. **Error text of database failures** comes from the PostgreSQL JDBC driver instead of lib/pq (`pq: ...`).
3. **Connection pool**: Go `database/sql` had no limit on open connections; HikariCP is capped at `db.max-pool-size`
   (default 20) with the default 30 s wait. Only matters while the lock-contention flag stalls the table.
4. **Metric names**: `db.client.connection.*` (HikariCP instrumentation) instead of the otelsql `db.sql.connection.*`
   family; `rpc.server.*` and JVM runtime metrics come from the Java instrumentation, so names/attributes differ from the
   Go instrumentation where the libraries differ.
5. **DB_CONNECTION_STRING parameters** are passed to pgjdbc by name (with `connect_timeout`, `application_name`,
   `target_session_attrs` translated). lib/pq-only parameters are not translated.
6. The lock-contention transaction is also released on service shutdown (Go kept it until the process ended).
7. Log record `event.name`/body are the SLF4J message; the Go logger also exported `severity`/`timestamp` through
   otelslog, which the Logback appender provides in its own way.
8. OpAMP uses an **internal** class of the (alpha) opamp client (`WebSocketRequestService`/`OkHttpWebSocket`), because
   its public API only exposes the `RequestService` abstraction; this may break on a client upgrade. The agent
   description attributes are the SDK resource attributes (service/host/os/process/telemetry.sdk); the Go code listed
   host and container attributes through its own detectors.
9. Container image: `eclipse-temurin:21` (JRE) instead of distroless static; the Java process needs far more than the
   `20M` memory limit of compose.yaml and `GOMEMLIMIT` is meaningless for it (see "Shared changes").

## Shared changes needed (not made here)

* `compose.yaml`: `product-catalog` `deploy.resources.limits.memory: 20M` must be raised (a JVM needs roughly 300-400M
  with `-Xmx256m`). `GOMEMLIMIT=16MiB` and `OTEL_GO_X_OBSERVABILITY=true` are Go-only and can be dropped;
  `OTEL_CONFIG_FILE=/otel-config.yml` (and its volume) is not read by the Java service. Everything else (ports, env
  vars, healthcheck `/bin/grpc_health_probe`, `OPAMP_SERVER_*` in compose.observability.yaml) works unchanged.

## NOT verified

* The Docker image was **not built** (no Docker here); the Dockerfile follows the recommendation pattern.
  Base image digests were read from Docker Hub tag metadata, not by pulling the images.
* No run against flagd (the service starts and degrades to flag defaults when flagd is down; flag evaluation is tested
  with the OpenFeature in-memory provider only, including the `product_id` evaluation context).
* No run against a real OpAMP server or the OpenTelemetry collector; OTLP export, log attribute capture and the
  `OTEL_SEMCONV_STABILITY_OPT_IN=database` environment variable were not exercised.
* PostgreSQL: `PostgresIntegrationTests` was run once against a throwaway PostgreSQL 18.6 (local scoop binaries,
  data directory under the scratchpad, removed afterwards) initialised with `src/postgresql/init.sql`: list/get/search,
  `NOT_FOUND`, connection-string conversion and the lock-contention scenario (lock timeout while held, readable after
  release) passed. Other PostgreSQL versions, TLS (`sslmode=require`) and keyword-style DSNs were not run against a
  real server (the latter two are only unit-tested at the string level).
* The default `./gradlew test` runs offline on H2; H2's `LOCK TABLE ... ACCESS EXCLUSIVE` is not supported, so the
  lock statement is only covered by mocks there and by the opt-in PostgreSQL test.
