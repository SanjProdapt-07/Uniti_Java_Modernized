# Porting notes: cart (.NET 10 / ASP.NET Core gRPC -> Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/cart` (`src/` and `tests/`). All original C# sources were read
fully before porting and have been removed from this directory (`*.cs`, `*.csproj`,
`Directory.*.props`, `NuGet.config`, `cart.slnx`, `appsettings.json`, old `src/.dockerignore`
and `src/Dockerfile`).

## Behavior mapping

| Original (.NET) | Java | Notes |
|---|---|---|
| `CartService` gRPC: `AddItem`, `GetCart`, `EmptyCart` (proto unchanged, from `/pb/demo.proto`) | `CartService` (`CartServiceImplBase`) | same RPCs; empty cart for unknown user is an empty `Cart` (no user id) |
| Span tags `user.id`, `demo.product.id`, `demo.product.quantity`, `demo.cart.items.count`; events `Fetch cart`, `Empty cart`; exception + ERROR status on `RpcException` | same attributes/events on the current (server) span; `recordException` + `setStatus(ERROR, message)` on `StatusRuntimeException` | server span name/attrs now come from `opentelemetry-grpc-1.6` instead of ASP.NET Core instrumentation |
| `ICartStore` | `CartStore` | same 5 operations (`initialize`, `addItem`, `emptyCart`, `getCart`, `ping`) |
| `ValkeyCartStore` on StackExchange.Redis | `ValkeyCartStore` on Lettuce (`LettuceValkeyLinkFactory`) | see below |
| Cart storage: hash key = user id, field `cart`, protobuf bytes, 60 min expiry after each change | identical | |
| `EmptyCart` with `cartFailure` flag: if rate > 0 and `random < rate`, use store on `badhost:1234` | identical (`badCartStore`) | flag read as number (`getDoubleValue`, default 0) |
| Storage errors -> `RpcException(FailedPrecondition, "Can't access cart storage. {ex}")` | `StatusRuntimeException(FAILED_PRECONDITION, "Can't access cart storage. " + ex)` | `ex` is `Throwable.toString()` (no stack trace in the text, the .NET `ToString()` includes it) |
| `SingleOrDefault` throws if the cart has the product twice | `IllegalStateException` -> `FAILED_PRECONDITION` | |
| Histograms `demo.cart.add_item.latency`, `demo.cart.get_cart.latency` (meter `OpenTelemetry.Demo.Cart`, unit `s`, same 14 bucket boundaries) | same names/meter/unit/boundaries (explicit bucket advice) | add_item recorded also on failure; get_cart also on failure |
| `cartMetricCardinalityOverflow`: add `demo.cart.trace_id` attribute to get_cart latency when flag on and a trace id exists | identical | flag evaluated before the storage call |
| Exemplar filter `TraceBased` | SDK default exemplar filter (trace based) | not set explicitly |
| Redis connection: `ssl`, password on options (not in the logged string), `ConnectRetry=30`, `ExponentialRetry(1000)`, `KeepAlive=180`, `abortConnect=false` | TLS and password on `RedisURI`; up to 30 initial connect attempts with exponential backoff 1s..10s between them; Lettuce `autoReconnect` with exponential reconnect delay 1s..10s; TCP keep-alive idle 180s; connect timeout 5s; commands rejected while disconnected | see deviations |
| Connect: small test `SET cart OK` / `GET cart`; events: connection lost -> flag false and reconnect on next call, restored -> flag true, internal error logged | identical (`Redis*` state listener) | |
| Redis instrumentation, `SetVerboseDatabaseStatements=true` | `opentelemetry-lettuce-5.1` tracing, query sanitization disabled | |
| gRPC health (`HealthServiceImpl`, `ReadinessCheck` named `oteldemo.CartService`, flag `failedReadinessProbe`) | `HealthService` + `ReadinessCheck` | `Check`/`Watch`; empty service name and `oteldemo.CartService` -> SERVING or NOT_SERVING; Watch sends once then completes; unknown service -> `UNKNOWN` (the original's unhandled `KeyNotFoundException`). Spring's own health service is disabled to avoid a duplicate registration |
| Feature flags: flagd provider, `MetricsHook`, `TraceEnricherHook` | OpenFeature Java SDK + flagd provider + `MetricsHook` + `TracesHook` | `FLAGD_HOST`/`FLAGD_PORT` read by the provider |
| Logs (`Log.cs`, event names `cart.*`, structured params) | `CartLog`: same messages and levels, `event.name` and parameters as SLF4J key/values, captured as log attributes via `capture-key-value-pair-attributes` | OTLP log export from the starter's logback appender |
| Resource: service name, random `service.instance.id`, container + host detectors | service name from `OTEL_SERVICE_NAME`/`spring.application.name`; random `service.instance.id` added (`ResourceConfiguration`, an `OTEL_RESOURCE_ATTRIBUTES` value wins); host/os/process attributes from the starter's resource providers | container detector not separately verified |
| Process/runtime metrics | the starter's runtime-telemetry (JVM) metrics | different metric names than .NET runtime/process metrics (unavoidable) |
| OpAMP client (WebSocket, resource as identifying/non-identifying attributes, optional TLS skip) | `OpAmpClientManager` with `opentelemetry-opamp-client` (`WebSocketRequestService`), same env vars; failures logged as a warning and ignored | uses classes in the library's `internal` packages (the library has no public WebSocket factory yet); alpha library |
| `VALKEY_ADDR` missing -> prints message, exit 1 | prints `VALKEY_ADDR environment variable is required.` and fails startup (exit code 1) | |
| Ports/env: `CART_PORT` (via `ASPNETCORE_HTTP_PORTS`), `VALKEY_*`, `FLAGD_*`, `OPAMP_*`, `OTEL_*` | `spring.grpc.server.port=${CART_PORT:7070}`, same names | |

## Tests

Ported xunit tests (`CartServiceTests.cs`) -> `CartServiceTests` (same three scenarios, in-memory store,
real in-process gRPC server) plus more cases. In total 71 JUnit 5 tests:
`CartServiceTests` (16, gRPC/Spring), `ValkeyCartStoreTests` (29, Mockito),
`LettuceValkeyStoreTests` (11, real Lettuce client against a small fake RESP server),
`CartApplicationWiringTests` (2, production wiring against the fake RESP server),
`ConfigurationTests` (13).

## Deviations

* **Dockerfile location.** The original image was built from `src/cart/src/Dockerfile`
  (`CART_DOCKERFILE=./src/cart/src/Dockerfile` in `.env`). The standard layout puts it at
  `src/cart/Dockerfile`, because `src/cart/src` is now the Java source root. `.env` needs
  `CART_DOCKERFILE=./src/cart/Dockerfile` (shared change, not made here).
* **Memory.** compose sets a 160M limit for cart; a Spring Boot JVM does not fit in that
  (the image runs with `-Xmx256m`). Raise the limit (suggest 400M).
* **ASPNETCORE_HTTP_PORTS** in compose is now unused (harmless).
* **Initial connect retry.** The meaning of `ConnectRetry=30` together with `abortConnect=false` in
  StackExchange.Redis was not verified against its source; it is implemented as documented
  ("repeat initial connect attempts"): up to 30 attempts with 1s..10s exponential backoff at start-up
  of the real store. The `badhost:1234` store makes a single attempt so a `cartFailure` call fails
  quickly instead of blocking for minutes.
* **Closing failed connections.** A connection lost by Lettuce is closed before a new one is opened on
  the next call (.NET left the old multiplexer undisposed).
* **No HTTP `GET /`.** The ASP.NET app answered `GET /` with a text; the Java gRPC server does not
  serve plain HTTP.
* **OpAMP**: see table.
* **Metrics names** of runtime/process instrumentation differ (JVM vs .NET).
* Unhandled non-gRPC exceptions in handlers surface as gRPC `UNKNOWN` in both versions.

## NOT verified

* **No real Valkey.** Nothing was run against Valkey/Redis. The store was tested with Mockito (commands,
  errors, retries, reconnect logic) and with the real Lettuce client against `FakeValkeyServer`, a
  minimal RESP2 server written for the tests (it accepts `HELLO` with an error so the client falls back
  to RESP2). TLS, real Valkey `AUTH`/RESP3 negotiation, key expiry, keep-alive and the real reconnect
  behavior after a server restart are untested.
* **Docker image build** (no Docker here). The Dockerfile follows the recommendation pattern; the
  base images use the floating `eclipse-temurin:21-jdk`/`21-jre` tags (no digests pinned). The
  `grpc-health-probe` stage was kept from the original image.
* **flagd integration**: tests use the OpenFeature `InMemoryProvider`; the real flagd provider was
  only constructed (it was not connected to a flagd).
* **OpAMP client** against a real OpAMP server (only attribute mapping and the disabled/failed-start
  paths are tested), including `wss://` with the TLS skip option.
* **Telemetry export**: spans/metrics/logs were checked with in-memory exporters only, not through an
  OTLP collector. The log attributes (`event.name`, structured params) were not looked at in an actual
  log backend.
* Container resource detection and the exact set of host/process resource attributes.
* The jar was started once without `VALKEY_ADDR` to check the exit code and message; it was not run as
  a full service next to compose.

## Dependency note

`opentelemetry-opamp-client` 1.59.0-alpha is used because 1.62.x pulls OpenTelemetry API 1.67 while
the Spring Boot starter 2.31.1 uses SDK 1.65, which breaks at runtime (`ConfigUtil` missing).
