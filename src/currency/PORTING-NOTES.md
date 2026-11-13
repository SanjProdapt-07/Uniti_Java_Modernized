# Currency service: C++ to Java 21 / Spring Boot 4.1 porting notes

Original: `opentelemetry-demo/src/currency` (`server.cpp`, `logger_common.h`, `meter_common.h`,
`tracer_common.h`, CMake, `genproto`). The original had no tests and no separate data file: the
exchange rates are a table inside `server.cpp`, now `CurrencyConverter.RATES`.

## Behavior mapping

| Original (C++) | Java |
|---|---|
| `CurrencyService::GetSupportedCurrencies` | `CurrencyService.getSupportedCurrencies`; returns the 33 table codes in the order written in `server.cpp` (`std::unordered_map` had no defined order, so any order was already valid) |
| `CurrencyService::Convert` | `CurrencyService.convert` + `CurrencyConverter.convert` (same arithmetic, see below) |
| `HealthServer::Check` always `SERVING`, any service name | `HealthService` (always `SERVING`, any service name, `Watch` unimplemented). Spring's own health auto-config is disabled (`spring.grpc.server.health.enabled=false`) so there is one health service |
| Port from `argv[1]` (`CURRENCY_PORT` via Dockerfile entrypoint) | `spring.grpc.server.port=${CURRENCY_PORT:7001}` |
| `IPV6_ENABLED=true` binds `[::]`, otherwise `0.0.0.0` | `CurrencyApplication` sets `spring.grpc.server.address=0.0.0.0` unless `IPV6_ENABLED=true`, in which case no address is set (wildcard, `[::]`). Same two log events as the original |
| `VERSION` env var (default `unknown`) as meter version | `currency.version=${VERSION:unknown}` |
| Server span `Currency/Convert`, `Currency/GetSupportedCurrencies`, kind SERVER, parent from W3C `traceparent` metadata | The SERVER span started by `opentelemetry-grpc-1.6` (parent extraction by the SDK propagator) is renamed to the same names with `updateName` |
| Span attributes `rpc.system.name=grpc`, `rpc.method=oteldemo.CurrencyService/<Method>`, `rpc.response.status.code=OK` | Set on that span with the same keys and values |
| Span attributes `demo.exchange.from`, `demo.exchange.to` (Convert) | Same |
| Span events "Processing ...", "... successful/fetched, response sent back", "Conversion failed" | Same text. The gRPC instrumentation's per-message events are switched off (`setEmitMessageEvents(false)`) so the event list is exactly the original's |
| Span status OK / ERROR | Same |
| Counter via `initIntCounter("demo.exchange.conversions", version)`: meter `demo.exchange.conversions`, instrument `demo.exchange.conversions_counter` (the helper appends `_counter`), attribute `demo.exchange.to`, +1 per successful Convert | Same meter name, version, instrument name and attribute (`CurrencyService.METER_NAME` / `COUNTER_NAME`) |
| OTLP log events (logger scope `currency`): `currency.get_supported_currencies`, `currency.conversion` (attrs `currency.from`, `currency.to`), `currency.conversion_failed` (ERROR), `currency.server.started`, `currency.server.ip_overwrite`, `currency.server.stopping`; `server.address` attributes | `CurrencyEvents` (OTel Logs API, `setEventName`), `ServerLifecycleLogger`. Same event names, bodies, severities and attributes |
| `catch(...)` returns `Status::CANCELLED` (no message) | `catch (RuntimeException)` calls `onError(Status.CANCELLED)` (no message) |
| OTLP over gRPC (C++ `OtlpGrpc*` exporters) | `otel.exporter.otlp.protocol=${OTEL_EXPORTER_OTLP_PROTOCOL:grpc}`, because compose points `OTEL_EXPORTER_OTLP_ENDPOINT` at port 4317 and the Java SDK would otherwise default to http/protobuf |
| SIGINT/SIGTERM: log, `server->Shutdown(now + 4s)` | Spring graceful shutdown with `spring.grpc.server.shutdown.grace-period=4s`; `ServerLifecycleLogger` prints and logs "Shutting down Currency Server" on context close |
| Alpine / CMake / genproto Dockerfile | Multi-stage Temurin 21 `Dockerfile`, same exposed `CURRENCY_PORT` |

## Conversion arithmetic (kept bug for bug)

`CurrencyConverter` reproduces `getDouble`, the rate division and `getUnitsAndNanos` with IEEE-754
doubles, so results are identical value for value:

* `getDouble` rebuilds the fraction from the decimal digits of `nanos`, least significant digit
  first, without left padding. `nanos = 5` is read as `0.5` and `nanos = 50_000_000` also as
  `0.5` (not `0.05`). Negative `nanos` give negative digits.
* `getUnitsAndNanos` truncates toward zero (`(long) value`), takes `value - unit`, multiplies by
  `1e9` and truncates. There is no rounding and no carry into `units`, so e.g. `1.999999999 EUR`
  converted to EUR returns `1` unit and `999999998` nanos.
* Unknown currency codes are not an error in the original: `currency_conversion[code]` inserts
  and reads `0.0`. An unknown source divides by zero (inf or NaN) and the result is narrowed with
  the x86-64 `cvttsd2si` rule (NaN, inf, out of int64 range give `Long.MIN_VALUE`, nanos narrow to
  0), which `CurrencyConverter.cppLong` emulates. An unknown target gives units 0 and nanos 0. The
  response currency code is the requested one in all cases. Callers therefore never get an error
  status for unsupported currencies, exactly as before.

## Deviations

1. `operator[]` also inserted unknown codes into the rate table, so after one such request the
   original listed the unknown code in `GetSupportedCurrencies`. The Java table is immutable and
   does not do that (a side-effect bug, not a feature).
2. Span creation: the original created its own span, the port decorates the one the gRPC
   instrumentation already creates (avoids two SERVER spans per call). The instrumentation also
   adds its usual attributes (e.g. `rpc.system`, `rpc.service`, `rpc.grpc.status_code` depending on
   semantic convention mode) and rpc server metrics, which the C++ service did not produce.
   `rpc.response.status.code` stays `OK` up front as in the original, even on the (practically
   unreachable) failure path.
3. The shutdown log text is "Shutting down Currency Server" (Java cannot tell SIGTERM from SIGINT).
4. Metric `demo.exchange.conversions_counter` is the name the C++ code really emitted;
   `telemetry-schema/metrics/currency.yaml` documents `demo.exchange.conversions` (unit
   `{conversion}`). Nothing in the repo consumes either name. Changing it is a one-constant edit if
   the schema name is wanted instead.
5. Spring gRPC also registers gRPC server reflection (not present before).
6. The IPv6 wildcard is bound by omitting the address: with `spring.grpc.server.address=::` the
   server ignored `CURRENCY_PORT` and listened on 9090 (observed, cause not investigated).

## Tests (60)

* `CurrencyConverterTests` (42): rate table, `getDouble` digit walk, conversion matrix with
  expected values produced by a separate re-implementation of the original algorithm (same
  currency, regular, zero, negative values, nanos truncation / no carry, int32 extremes, unknown
  source / target, int64 overflow, `(long)` cast emulation).
* `CurrencyServiceTests` (12, Spring Boot + in-process gRPC + in-memory OTel exporters): both RPCs,
  span name / kind / attributes / events / status, W3C parent propagation, counter per target
  currency, log events and attributes, health Check (default and named service).
* `CurrencyServiceUnitTests` (2, Mockito): failure path returns `CANCELLED` without message, span
  ERROR + "Conversion failed" event, error log; success path.
* `ServerStartupTests` (4): IPv6 flag, started / ip_overwrite / stopping log events.

## Not verified

* The Docker image build (no Docker here). The `Dockerfile` follows the recommendation service
  pattern but was never built.
* A run against the real OpenTelemetry Collector, and the real frontend / checkout calling the
  service. The jar was only started locally to check it listens on `CURRENCY_PORT`, with and
  without `IPV6_ENABLED`; the OTLP export had no collector to talk to.
* Bit-for-bit equality with the compiled C++ binary: no C++ toolchain was available, so the expected
  conversion values come from an independent re-implementation of the algorithm (IEEE doubles),
  not from running the original. The x86-64 narrowing rule is from the architecture manual, not
  observed. glibc `pow(10, 9)` is assumed to be exactly `1e9`.
* Heap/memory behaviour under the compose limit (see requested shared changes).

## Requested shared changes (not made here)

* `compose.yaml` `currency`: raise `deploy.resources.limits.memory` from `20M` (a C++ binary) to about
  `300M`; remove the now unused `OPENTELEMETRY_CPP_VERSION` build arg (harmless if left).
* `compose.yaml` `currency` healthcheck: `nc -z` may not exist in `eclipse-temurin:21-jre`; use
  `bash -c 'echo > /dev/tcp/localhost/${CURRENCY_PORT}'` as the recommendation service does, and a
  `start_period` of about 20s (Spring Boot starts slower than the C++ binary).
* `.env`: `OPENTELEMETRY_CPP_VERSION` is no longer needed by currency (it may be used by other C++
  services).
