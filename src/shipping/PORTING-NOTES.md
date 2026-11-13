# Porting notes: shipping (Rust actix-web -> Java 21 / Spring Boot 4.1 Web MVC)

Original: `opentelemetry-demo/src/shipping` (`main.rs`, `shipping_service.rs`, `shipping_service/{quote,tracking,
shipping_types}.rs`, `healthcheck.rs`, `telemetry_conf.rs`).

## Behavior mapping

| Original (Rust) | Java |
|---|---|
| `POST /get-quote` (JSON body `{items:[{product_id|productId, quantity}], address?}`) | `ShippingController.getQuote`; snake_case fields with camelCase aliases (`productId`, `streetAddress`, `zipCode`); `address` optional, everything else required and non-null; `quantity` must be a u32 |
| item count = sum of quantities as `u32` | same (wraps at 2^32 like the release build) |
| quote service: `POST $QUOTE_ADDR/getquote`, body `{"numberOfItems": N}`, response is a bare number (any HTTP status accepted) | `QuoteClient` with `RestClient`, same URL/body, status ignored, body parsed with Rust `f64::from_str` rules (no whitespace/suffixes; `inf`/`nan` accepted), UTF-8 checked |
| cost: `(value * 100).round() as u64` -> dollars/cents, `nanos = cents * 10_000_000`, `units` u64 | same arithmetic (`BigDecimal` HALF_UP, saturating u64 cast, `BigInteger` units) |
| response `{"cost_usd":{"currency_code":"USD","units":..,"nanos":..}}` | identical JSON (field names and order) |
| failure -> `500` body `Failed to get quote: status: Unknown, message: "<msg>", details: [], metadata: MetadataMap { headers: {} }` | same text, messages `Failed to call quote service: ...`, `Failed to read response body from quote service`, `Failed to parse quote service response as UTF-8`, `Failed to parse quote value as f64` |
| `POST /ship-order` -> `{"tracking_id":"<uuid v4>"}` | same |
| non-US check: `country.to_uppercase().trim()` not in `US, USA, UNITED STATES, UNITED STATES OF AMERICA`; no address = US | same |
| flag `intlShippingSlowdown` (int, default 0) evaluated only for non-US orders; `> 0` -> sleep that many seconds; evaluation error -> warn + 0 | same (OpenFeature Java client, `getIntegerDetails`); error code in the details -> warn + 0 |
| `GET /health` -> empty 200 | same |
| bad JSON -> actix 400 plain text, wrong content type -> 415, wrong method on a known route -> empty 405, unknown route -> empty 404 | `ErrorResponses`: 400 `Json deserialize error: ...`, 415, empty 405, empty 404. (The 405/404 split is from my knowledge of actix-web's resource default service; the Rust service was not run to confirm it.) |
| `SHIPPING_PORT` (required), `IPV6_ENABLED=true` -> bind `[::]` else `0.0.0.0`, `QUOTE_ADDR` (default `http://quote:8090`), `FLAGD_HOST`/`FLAGD_PORT` | `server.port: ${SHIPPING_PORT:50050}`, `ServerAddressConfiguration`, `quote.addr`, flagd provider reads the same variables |
| flagd provider with cache disabled | `FlagdOptions.cacheType("disabled")` |
| spans: actix `RequestTracing` + awc `trace_request` | Spring Web MVC server spans + RestClient client spans (OpenTelemetry Spring Boot starter + `opentelemetry-spring-webmvc-6.0`, `opentelemetry-spring-web-3.1`) |
| span event `shipping.quote.received` and attribute `demo.shipping.cost.total` (e.g. `10.99`) on the active span | same |
| counter `demo.shipping.items_shipped` (meter `otel_demo.shipping.quote`), only after a successful quote | same name/meter, same condition |
| actix `RequestMetrics` | `http.server.*` metrics from the Spring instrumentation |
| tracing events `shipping.server.started`, `shipping.quote.requested/sent`, `shipping.feature_flag.evaluated/evaluation_failed`, `shipping.international.slowdown`, `shipping.tracking_id.created` | SLF4J logs with the same messages and fields as key/value pairs (exported as log attributes); the event name is the `event.name` attribute |
| OTLP http/protobuf exporters, resource detectors host/os/process | OpenTelemetry Spring Boot starter (`OTEL_*` variables, including `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` from compose); starter's resource providers |
| `healthcheck` binary (TCP connect to `$SHIPPING_PORT`) used by compose | `/app/healthcheck` shell script in the image (bash `/dev/tcp`), same command in compose |

## Tests

* Rust `quote.rs` tests (`test_create_quote_from_float`, `test_quote_display`) -> `QuoteMathTests`.
* Rust `shipping_service.rs` tests (`ship_order` no address / US / international flag off / on / US with flag on) ->
  `ShippingApiTests` (same timing assertions, flag mocked with Mockito).
* New: `QuoteClientTests` (`MockRestServiceServer`: request, status handling, parse and UTF-8 errors, connection
  failure, counter, span attribute/event), more `ShippingApiTests` (JSON shapes, 400/415/404, u32 wrap, camelCase
  aliases, error body text, flag error, US name variants), `QuoteMathTests` (f64 grammar and u64 saturation).

## Deviations

1. **Error bodies of 400/415**: serde/actix message texts cannot be reproduced; status codes, plain-text content type and
   the `Json deserialize error: ` prefix are kept, the detail is Jackson's. No 413 for bodies over 2 MiB (actix's JSON
   limit) is enforced.
2. **Span and metric names/attributes** are those of the Java instrumentation (e.g. `POST /get-quote`, current HTTP
   semantic conventions), not of `opentelemetry-instrumentation-actix-web`; log `name` events are carried as the
   `event.name` attribute instead of a log event name.
3. **OpenFeature traces hook** (`TracesHook`) is added as required by the migration standard; the Rust service did not
   use one (it called the provider directly), so `feature_flag` span events are new.
4. `SHIPPING_PORT` defaults to `50050` (Rust panicked when unset).
5. Quote HTTP client: 5 s connect/read timeouts (awc default); redirect/compression handling is that of the JDK/Boot
   client, not of awc.
6. Memory/startup: a JVM does not fit the `20M` limit of compose.yaml and starts in ~8 s (compose `start_period` 10 s, 20
   retries of 5 s); see "Shared changes".
7. `unicode trim`: `country.trim()` in Rust strips Unicode White_Space, Java `strip()` strips `Character.isWhitespace`
   characters (differs for e.g. NBSP).

## Shared changes needed (not made here)

* `compose.yaml`: `shipping` `deploy.resources.limits.memory: 20M` must be raised (suggest 200-300M; `-Xmx128m` in the
  image). Everything else (`SHIPPING_PORT`, `QUOTE_ADDR`, `FLAGD_*`, `IPV6_ENABLED`, `OTEL_*`, healthcheck
  `/app/healthcheck`, `read_only` + tmpfs `/tmp`) works unchanged.

## NOT verified

* The Docker image was **not built** (no Docker here); base image digests come from Docker Hub tag metadata.
* The service was started from the built jar and `/health`, `/ship-order` (flag evaluation falling back because flagd
  was unreachable) and the `/get-quote` 500 path (quote service unreachable) were exercised by hand; **no run against a
  real flagd, the Java `quote` service or the OpenTelemetry collector**, so OTLP export, trace propagation to quote and
  the exact attribute/metric names on the wire are unverified.
* `event.name` / key-value log attribute capture was configured (`capture-key-value-pair-attributes`) but the exported
  log records were not inspected.
