# Porting notes: quote (PHP 8.5 / Slim 4 -> Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/quote` (`public/index.php`, `app/routes.php`, `app/settings.php`,
`app/dependencies.php`).

## Behavior mapping

| Original (PHP) | Java |
|---|---|
| `POST /getquote`, Slim route | `QuoteController` `@PostMapping("/getquote")` |
| Body decoded by Slim `BodyParsingMiddleware` by media type (`application/json`, `+json`, `application/x-www-form-urlencoded`, XML) | `RequestBodyParser`: the raw body is read from the servlet request and decoded by `Content-Type` (JSON incl. `+json`, form). XML and unknown/missing content types give "no parsed body". Invalid JSON, trailing garbage and non-array JSON (e.g. `5`) give "no parsed body" (as `json_decode` + Slim). |
| `intval($body['numberOfItems'])` | `PhpCompat.intval` (ints, floats truncated, numeric-prefix strings, bool, null, arrays, int64 saturation/wrap) |
| `round(8.99 * n, 2)` | `PhpCompat.round` (half away from zero, value returned unchanged beyond 1e15 after scaling) |
| `json_encode($float)` | `PhpCompat.jsonEncode`: shortest round-trip digits, `0` for zero, no `.0` suffix (`26.97`, `899`, `0`, `-17.98`), exponent form `1.0e+25` from 1e17 |
| Response `200`, `Content-Type: application/json` | same |
| Missing `numberOfItems` (object/list body): `InvalidArgumentException` caught, `recordException` on the child span, response `0` | same, status 200 body `0` |
| Non-array body: `array_key_exists()` throws `TypeError`, not caught by `catch (\Exception)`, but swallowed by `return` in `finally` -> quote `0.0`, nothing recorded | same: child span with only the `Calculating quote` event, response `0` |
| Server span from Slim auto-instrumentation | Spring Web MVC server span from the OpenTelemetry Spring Boot starter (`POST /getquote`) |
| `Span::getCurrent()->addEvent('Received get quote request, processing it')` and `'Quote processed, response sent back'` with attribute `demo.shipping.quote.cost.total` (double) | same events and attribute on the current server span |
| Child span `calculate-quote`, tracer `manual-instrumentation`, INTERNAL, events `Calculating quote` / `Quote calculated, returning its value`, attributes `demo.shipping.quote.items_count` (long), `demo.shipping.quote.cost.total` (double) | same (`QuoteService`) |
| Counter `quotes` (meter `quotes`, unit `quotes`, description `number of quotes calculated`), attribute `number_of_items` | same |
| Monolog `slim-app` logger -> OTel logs handler (INFO+), message `Calculated quote`, context `total` | SLF4J logger `slim-app`, message `Calculated quote`, key-value `total`, exported by the OpenTelemetry logback appender (`logback-spring.xml`, `keyValuePairAttributesIncluded`) |
| Access line printed to stdout `[date] "METHOD path HTTP/x" status size` | `AccessLogFilter` prints the same line |
| `QUOTE_PORT` | `server.port: ${QUOTE_PORT:8090}` |
| `IPV6_ENABLED=true` -> bind `[::]` and print `Overwriting Localhost IP: [::]`; always prints `Listening on: addr:port` | `ServerAddressConfiguration` (same messages) |
| `OTEL_*` env vars | read by the OpenTelemetry Spring Boot starter (same names). `OTEL_PHP_*` variables in compose are ignored. |
| Periodic `forceFlush` workaround for PHP's non-async batch processors | not needed (the Java SDK exports asynchronously) |

## Deviations

* 404 / 405 responses: status codes match (Slim also answers 404 for unknown routes and 405 for
  other methods on `/getquote`) but the response bodies are Spring Boot's, not Slim's error renderer
  output.
* Unhandled-error responses: Slim's error middleware (with details) is not reproduced; Spring Boot's
  default error handling applies.
* `parse_str` array syntax in form bodies (`numberOfItems[]=1`, dotted keys) is not emulated; only
  simple `key=value` pairs.
* PHP 8.5's exact `round()` implementation was not available to compare against; the port uses the
  classic half-away-from-zero on `value * 100`. For the values this service produces (`8.99 * n`) the
  results are the nearest two-decimal value; this is covered by tests for typical inputs only.
* Span names/attributes of the server span come from the Java instrumentation (HTTP semantic
  conventions of the Spring Web MVC instrumentation) rather than the PHP Slim auto-instrumentation,
  so extra/differently-named HTTP attributes are expected.

## Not verified

* The Docker image was **not** built (no Docker available here). The Dockerfile follows the
  recommendation service pattern and was not executed.
* The service was started from the built `bootJar` locally and answered `POST /getquote` with `26.97`
  (200, `application/json`) and `405` for GET; no traffic through Envoy (frontend-proxy), the shipping
  service, or the OTLP collector was exercised, so export of spans/metrics/logs to a real collector is
  unverified (tests use in-memory exporters).
* Behavior of the PHP service was derived from reading its source and the Slim/PHP semantics; the PHP
  service was not run for a side-by-side comparison.

## Shared changes needed (not made here)

* `compose.yaml` `quote`: memory limit `40M` is far too small for a JVM; use roughly `300M`.
* `compose.yaml` `quote` healthcheck uses `php -r fsockopen(...)`; PHP is no longer in the image. A TCP
  check that works with the Temurin image is
  `["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/localhost/${QUOTE_PORT}'"]`.
* The `OTEL_PHP_AUTOLOAD_ENABLED` / `OTEL_PHP_INTERNAL_METRICS_ENABLED` environment variables can be removed.

## Tests

`./gradlew test`: 39 tests, no Docker. No tests existed for the original service. `PhpCompatTests` (29) pins the
PHP number semantics (`intval`, `round`, `json_encode`); `QuoteControllerTests` (10) runs the real HTTP server and
checks responses, error paths, server span events, child span attributes/events, the `quotes` counter, and the
exported `Calculated quote` log record, using in-memory OpenTelemetry exporters.
