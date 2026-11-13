# Porting notes: load-generator (Locust to Spring Boot 4.1)

Original: `locustfile.py` + Locust + locust-plugins Playwright user + OpenTelemetry Python + OpenFeature OFREP.
Port: Spring Boot 4.1.2, Java 21, virtual threads, OTel Spring Boot starter 2.31.1, OpenFeature + flagd provider,
Playwright for Java 1.65 (optional browser users). `people.json` moved to `src/main/resources/`.

## Behavior mapping

| Original | Java |
|---|---|
| `WebsiteUser` weight `LOCUST_HTTP_USER_WEIGHT` (9), `wait_time = between(1, 10)` | `WebsiteUser`, uniform random 1.0 to 10.0 s after every task (tested) |
| tasks index 1, browse_product 10, get_recommendations 3, get_ads 3, view_cart 3, add_to_cart 2, checkout 1, checkout_multi 1, flood_home 5, ask_agent 3 | same names and weights, weighted random selection (tests: table + distribution) |
| `on_start`: session uuid4, baggage `session.id` + `synthetic_request=true` attached for the user's whole life, span `user_session_start` around `index()` | `WebsiteUser.onStart()`: same, baggage made current on the user's thread; requests carry `baggage` + `traceparent` (tested) |
| `GET /` with span `user_index` | same |
| `GET /api/products/<id>`, span `user_browse_product` attr `demo.product.id` | same |
| `GET /api/recommendations?productIds=<id>`, span `user_get_recommendations` | same |
| `GET /api/data/?contextKeys=<category>` (category `None` sends no param), span attr `demo.ad.category` = `str(category)` (`"None"`) | same, including the `"None"` string and omitted param (tested) |
| `GET /api/cart` span `user_view_cart` | same |
| add_to_cart: random product + quantity from [1,2,3,4,5,10]; `GET /api/products/<id>` then `POST /api/cart {"item":{"productId","quantity"},"userId"}`; span `user_add_to_cart` attrs `user.id`, `demo.product.id`, `demo.product.quantity` | same |
| checkout: add_to_cart for a fresh user then `POST /api/checkout` with a random `people.json` entry + `userId`; span `user_checkout_single` | same; the payload is a copy (original mutated the shared entry) |
| checkout_multi: 2, 3 or 4 add_to_cart for one user, then checkout; span `user_checkout_multi` attr `demo.cart.items.count` | same |
| flood_home: flag `loadGeneratorFloodHomepage` (int, default 0), if > 0 that many `GET /`, span `user_flood_home` attr `demo.request.flood.count` | same flag key and semantics via OpenFeature (flagd provider + `TracesHook`) |
| ask_agent: `POST http://$AGENT_ENDPOINT:$AGENT_PORT/prompt {"message": prompt}`, one of 3 fixed prompts, span `user_ask_agent` attr `gen_ai.input.messages` (JSON, Python `json.dumps` spacing) | same (tested incl. exact attribute string) |
| log lines (`User browsing product: ...`, `Checkout completed for user ...`, ...) | identical messages through SLF4J/Logback; console pattern mimics the original (`trace_id=... span_id=...`); logs also shipped over OTLP by the Logback appender |
| requests session: cookie jar per user, redirects followed, `python-requests` User-Agent, `Accept: */*`, `Accept-Encoding: gzip, deflate` | `UserHttpSession`: per-user cookie manager, manual redirect following (max 30), same headers; User-Agent default `python-requests/2.32.5` (configurable `load-generator.user-agent`) |
| Locust stats: name = path + query of the first request, failure = connection error or status >= 400 | `RequestStats`, same naming/failure rule, histogram rounding like Locust |
| `WebsiteBrowserUser` (only if `LOCUST_BROWSER_TRAFFIC_ENABLED` in true/yes/on): weight `LOCUST_BROWSER_USER_WEIGHT` (1); tasks `open_cart_page_and_change_currency` and `add_product_to_cart` | `BrowserUser` on Playwright for Java; same selectors, 15 s image wait, 2 s trace-export waits, baggage header `synthetic_request=true` appended to every browser request, new root span per task (`browser_change_currency`, `browser_add_to_cart`) |
| `LOCUST_WEB_PORT` (8089), `LOCUST_WEB_HOST` | `server.port` / `server.address` |
| `LOCUST_USERS`, `LOCUST_HOST`, `LOCUST_AUTOSTART`, `LOCUST_HEADLESS` | same variables; autostart/headless start `LOCUST_USERS` users at `LOCUST_SPAWN_RATE` (default 1/s) |
| Locust web UI (Flask, Envoy `/loadgen/` rewritten to `/`) | `GET /` page with relative URLs only, `POST /swarm`, `GET /stop`, `GET /stats/requests`, `/stats/reset`, CSV and `/exceptions`, `/tasks` JSON (tested incl. real-server `/` and stats) |
| OTLP traces, metrics, logs (gRPC), `OTEL_*` env | OTel starter, default protocol `grpc`, `OTEL_*` env honored; `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_SERVICE_NAME` come from compose unchanged |
| Python system metrics (`SystemMetricsInstrumentor`) and SDK internal metrics | JVM runtime metrics from the starter; not metric-name compatible |
| `RequestsInstrumentor` / `URLLib3Instrumentor` client spans | one CLIENT span per HTTP request (JDK HttpClient instrumentation); the original produced requests + urllib3 spans |

## Deviations (known and deliberate)

* **Locust UI**: not Locust's UI or full API. It is a minimal page and a subset of the endpoints (no charts,
  no download-report page, no `/stats/requests_csv` history, no worker/distributed mode, no auth, no CLI flags
  other than the env vars above, no `--tags`/`--exclude-tags`, no stop timeout, no `LOCUST_CSV`).
  `/stats/requests` has the main Locust fields; `/exceptions` lists failure messages without tracebacks.
* **Headless**: the HTTP server stays up (Locust would not start the web UI in headless mode).
* **Flags**: the original used OFREP (`FLAGD_OFREP_PORT`, 8016) through `openfeature-provider-ofrep`. The Java port
  follows the migration standard and uses the flagd provider (gRPC, `FLAGD_HOST`/`FLAGD_PORT`). `FLAGD_OFREP_PORT`
  is accepted in compose but no longer used. Flag key and default (0) are the same. The Java OFREP provider
  (`dev.openfeature.contrib.providers:ofrep:0.0.2`) is pre-1.0 and was not adopted.
* **User ids**: Python `uuid.uuid1()` became random UUIDs (`user.id`); session ids are uuid4 as before.
* **User mix**: Locust's dispatcher algorithm is not reproduced exactly. Users are assigned to classes by weight
  using a deficit round robin (9:1 gives 9 http users then 1 browser user per 10; 5 users are all http).
  Whether Locust would pick the same split for small counts was not checked.
* **Browser users**: no wait time between tasks, like locust-plugins `PlaywrightUser`, but after a failed task
  there is a 1 s pause (the original would retry immediately). Stats entries are task-level (`BROWSER <task>`),
  not the per-request events locust-plugins emitted. Console messages are printed like before.
  Every browser user creates its own Playwright driver process plus Chromium (Playwright for Java is bound to
  one thread), which is heavier than locust-plugins sharing one Playwright instance.
* **Added metrics** (not in the original): `load_generator.users`, `load_generator.requests`,
  `load_generator.request.duration`.
* **Log format**: `trace_sampled` prints the raw trace flags (`01`/`03`), the original printed `True`/`False`.
* **Image size**: the Playwright driver bundle makes `load-generator.jar` about 287 MB.
* `LOCUST_RUN_TIME`, `LOCUST_SPAWN_RATE`, `LOCUST_AUTOQUIT` were not used by the demo; they are implemented
  as a best effort of Locust semantics (headless exits after the run time with 1 if any request failed).

## Not verified

* The Docker image build and container start (no Docker). The Dockerfile installs Chromium through
  `PropertiesLauncher -Dloader.main=com.microsoft.playwright.CLI install --with-deps chromium`; that command was
  not executed here.
* **No real browser was ever launched.** `BrowserUser` is tested against Mockito mocks of the Playwright `Page`,
  `Route`, `Request`, `Response` interfaces only. Selectors and timings follow the original but have not run
  against the real frontend.
* End to end behavior against the real demo (frontend, cart, checkout, agent) and a real flagd; the plain HTTP
  users were exercised only against in-process fake HTTP sites. A manual smoke run of the jar against the
  Java image-provider worked (requests recorded, stats JSON, trace ids in logs).
* OTLP export to a real collector (tests use in-memory exporters; the manual run used exporters `none`).
  The Logback OTLP appender wiring was only checked as "context starts", not on received log records.
* Memory behavior within compose's `1500M` limit, especially with browser users (default compose sets
  `LOCUST_BROWSER_TRAFFIC_ENABLED=true`, i.e. about 1 of 10 users is a browser).
* Equivalence of the Locust statistics/percentile numbers with Locust's own.

## Tests

99 tests, all passing with `bootJar test`: `WebsiteUserTests` (19), `UserHttpSessionTests` (11),
`PropertiesAndStatsTests` (37), `LoadRunnerTests` (13), `BrowserUserTests` (8), `LocustControllerTests` (8),
`UiPageTests` (2), `AutostartAndBrowserWiringTests` (1). The original had no tests.
