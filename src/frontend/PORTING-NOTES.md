# Frontend: porting notes (Next.js -> Spring Boot 4.1 / Java 21)

Reference: `opentelemetry-demo/src/frontend` (Next.js 16, React 19, styled-components, Cypress).
This service keeps the public URLs, the `/api/*` contract, the DOM hooks and the CSS, and replaces the
React runtime with server-rendered Thymeleaf shells plus a small static JavaScript layer (no Node build).

## Behavior mapping

| Original | Java |
|---|---|
| `pages/index.tsx` (`/`) | `PageController#home` -> `templates/index.html`; products rendered by `app.js` `homePage()` |
| `pages/product/[productId]` | `/product/{productId}` -> `product.html`; `productPage()` |
| `pages/cart/index.tsx` | `/cart` -> `cart.html`; `cartPage()` (cart items, shipping, checkout form, recommendations) |
| `pages/cart/checkout/[orderId]` | `/cart/checkout/{orderId}` -> `checkout.html`; `checkoutPage()` reads the `?order=` JSON like the original |
| `pages/404.tsx`, `pages/500.tsx` | `ErrorPageController` + `templates/error/404.html`, `500.html` (HTML for every unmatched path, `/api/*` included, as Next does) |
| `pages/_document.tsx` `window.ENV` | `WindowEnvironment` + `th:inline="javascript"` in `fragments/base.html`; `synthetic_request` baggage switches the browser OTLP endpoint to `http://${OTEL_COLLECTOR_HOST}:4318/v1/traces` |
| `pages/api/cart.ts` (GET/POST/DELETE) | `CartApiController` (GET enriches items with the product, POST = AddItem then GetCart, DELETE = 204) |
| `pages/api/checkout.ts` | `CheckoutApiController`: same 422 `PAYMENT_FAILED` (prefix `failed to charge card:` and not `code = Unavailable` / `code = Internal`), 500 `{"error":"Failed to place order."}`, 200 with priced items; same log messages |
| `pages/api/currency.ts`, `data.ts`, `products/*`, `recommendations.ts`, `shipping.ts` | `SimpleApiControllers` (one nested `@RestController` per route). Every route answers unsupported methods with an empty `405`; unhandled errors give `500 Internal Server Error` (text/plain) |
| `services/ProductCatalog.service.ts` | `ProductCatalogService` (convert only when currency is non-empty and not `USD`; products priced in parallel) |
| `gateways/rpc/*.gateway.ts` | Spring gRPC client stubs (`@ImportGrpcClients` in `FrontendApplication`), channels `ad`, `cart`, `checkout`, `currency`, `product-catalog`, `recommendation` fed by `AD_ADDR`, `CART_ADDR`, ... in `application.yaml` |
| `gateways/http/Shipping.gateway.ts` | `ShippingGateway` (`RestClient`, `POST {SHIPPING_ADDR}/get-quote`, snake_case contract, `HTTP error: <status> <text> - <body>` on failure) |
| ts-proto JSON shape of API responses | `ProtoJson`: camelCase, defaults present, int64 as JSON numbers, unset messages omitted |
| `utils/telemetry/InstrumentationMiddleware.ts` | `ApiInstrumentationInterceptor` (+ `ApiExceptionHandler`): `http.status_code` span attribute, exception recorded + span ERROR on failure, `API request completed` / `API request failed` logs with `http.request.method`, `url.path`, `http.response.status_code` |
| `utils/telemetry/Instrumentation.js` (Node SDK, OTLP gRPC) | `opentelemetry-spring-boot-starter` (traces, metrics, logs via `OTEL_*`) + `opentelemetry-grpc-1.6` client interceptor; `logback-spring.xml` adds the OTel logback appender |
| `utils/telemetry/FrontendTracer.ts` + `SessionIdProcessor.ts` | `static/assets/js/vendor/frontend-otel.js`, an esbuild bundle of the same OTel JS packages/versions (`tools/otel-bundle/`): ZoneContextManager, W3C trace-context + baggage propagators, `getWebAutoInstrumentations` (document-load, user-interaction, fetch, XHR) with `propagateTraceHeaderCorsUrls: /.*/`, `demo.synthetic_request` attribute, `session.id`/`enduser.id` on every span, OTLP/HTTP `BatchSpanProcessor` (500 ms) to `PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (fallback `http://localhost:4318/v1/traces`), resource `service.name=WEB_OTEL_SERVICE_NAME` + browser detector |
| `gateways/Api.gateway.ts` baggage proxy | `app.js` `withBaggage()` sets `session.id` / `enduser.id` baggage around every API call |
| `gateways/Session.gateway.ts` | `session.js`: same `localStorage` key `session` `{userId, currencyCode}`, corrupted value recovery. (The original has no cookie; none is added.) |
| `@openfeature/react-sdk` + `flagd-web-provider` (`imageSlowLoad`) | `flags.js`: Connect-protocol client for flagd through Envoy `/flagservice` (`ResolveAll` with context `{targetingKey, userId, currencyCode}`, `EventStream` for config changes, 100 ms x2 back-off capped at 10 s, 3 retries); product card images are fetched with `x-envoy-fault-delay-request: <imageSlowLoad>` and `Cache-Control: no-cache` |
| `styles/*.styled.ts`, `components/**/*.styled.ts`, `globals.css`, `Theme.ts` | `static/assets/css/frontend.css` (same values, same 768 px breakpoint) |
| `public/` | `static/favicon.ico`, `static/icons/*` at the same URLs |
| `cypress/`, `cypress.config.ts`, `Dockerfile.cypress`, `package.json`, `package-lock.json`, `tsconfig.json`, `utils/Cypress.ts`, `utils/enums/CypressFields.ts`, `gateways/Session.gateway.ts` | carried over byte-identical (Cypress and its helpers need them) |
| `Dockerfile` (node distroless) | multi-stage Temurin 21 build/JRE, `EXPOSE ${FRONTEND_PORT}` |

Env vars honoured: `PORT`/`FRONTEND_PORT`, `AD_ADDR`, `CART_ADDR`, `CHECKOUT_ADDR`, `CURRENCY_ADDR`,
`PRODUCT_CATALOG_ADDR`, `RECOMMENDATION_ADDR`, `SHIPPING_ADDR`, `ENV_PLATFORM`, `WEB_OTEL_SERVICE_NAME`,
`PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_COLLECTOR_HOST`, `FLAGD_HOST`, `FLAGD_PORT`, `LOG_LEVEL`
and the standard `OTEL_*` variables. `FRONTEND_ADDR` is passed by compose but was never read by the app.

## Deviations (intentional)

1. **No client-side router.** Next `Link`/`router.push` navigation was a single-page transition; here every
   navigation is a normal page load (`/cart` after add-to-cart, `/cart/checkout/{id}?order=...` after order
   placement). Consequences: the cart/currency requests are repeated per page and the browser emits
   document-load spans per navigation. All Cypress interactions remain valid.
2. **No react-query behaviour:** no 3x automatic retry of failed requests, no refetch-on-focus, no shared
   cache. The initial requests the original issued with an empty currency code (state not yet initialised)
   are not repeated: the first request already uses the stored currency.
3. **Recommendation ids** are sent to the recommendation service as proper repeated elements. The original
   passed the comma-joined query string to the gRPC client as if it were an array, producing one element per
   character; the Java recommendation service accepts both forms and returns the same result.
4. **`data-cy="checkout-item"`** is rendered on every order item of the order-complete page. The original
   page did not render it although `Checkout.cy.ts` expects two of them (the hook lived only in the unused
   `CheckoutItem` component).
5. `next/image` is replaced by plain `<img>`; image URLs have no `?w=&q=` suffix (the demo's image loader only
   appended it). Brand logo `<img>` has an empty `alt`.
6. Lenient request handling where the original crashed: `POST /api/cart` without `userId` uses `""` (the
   original threw in the ts-proto encoder and answered 500); `OPTIONS` requests get Spring's automatic
   `200 + Allow` response instead of 405.
7. `/path/` redirects with 308 to `/path` (Next default), implemented by `TrailingSlashRedirectFilter`
   because the load generator calls `/api/data/`.
8. `window.ENV` values fall back to `local` / `frontend-web` / `""` when the variables are unset; the original
   interpolated the string `undefined`.
9. Server telemetry differences inherent to the runtime change: span/metric names follow the Java
   instrumentation (servlet server spans, `oteldemo.*Service/*` gRPC client spans, `POST` for the shipping
   HTTP call, JVM metrics). The Next-specific render spans (`render route (pages)`, `resolve page components`)
   and Node process metrics no longer exist. Logs use Logback (console + OTLP appender) instead of pino JSON;
   the structured fields are emitted as log attributes.
10. The server-side OpenFeature client (flagd provider + OTel traces hook, as in recommendation) is wired
    (`FeatureFlagConfiguration`) but no server code path evaluates a flag, exactly like the original: the only
    frontend flag (`imageSlowLoad`) is evaluated in the browser because its targeting context is the browser
    session.

## Tests

`./gradlew test`: 66 JUnit 5 tests (Mockito mocks for the six gRPC stubs and the shipping gateway):

* `ApiTests` (38, MockMvc over the full application context): every API route (shape, status codes, call
  arguments, 405s, malformed JSON, trailing-slash redirect, payment-failure classification, span
  exception/`http.status_code` recording).
* `PageTests` (11): page shells, `window.ENV`, synthetic-request baggage, 404/500 pages, static assets, every
  Cypress `data-cy` hook present, checkout form field names/ids.
* `ServerTests` (5, real Tomcat on a random port): HTML 404 for unknown pages and `/api/*`, 308 redirect,
  empty 405, content types.
* `GatewayTests` (12): `ProtoJson`, `ShippingGateway` (MockRestServiceServer), `ProductCatalogService`,
  `ParallelExecutor`.

The original had no unit tests; its Cypress specs are carried over.

## Verification actually performed / not performed

Performed:
* `bootJar` and `test` with the helper script (66 tests, 0 failures).
* The packaged jar was started locally (backends down): pages render, API answers 500 as designed.
* The real pages + real static JS were executed in jsdom against a faked `/api` backend
  (`tools/browser-smoke/pages-smoke.js`, 56 checks: product grid, currency switch + persistence, corrupted
  session recovery, product detail incl. missing picture, add to cart, cart totals/shipping, dropdown, quantity
  delta, empty cart, order placement request body, order-complete page, recommendations/ads, all `data-cy`).
* The vendored OTel bundle was run in real headless Edge against a local OTLP sink: spans arrive with
  `service.name=frontend-web`, `session.id`/`enduser.id`, `demo.synthetic_request`, document-load, fetch spans
  for `/api/*`.
* `flags.js` against a mock flagd Connect server (`tools/browser-smoke/flags-smoke.js`).

NOT performed / unverified:
* Docker image build (no Docker here); compose start-up of the whole stack.
* The Cypress suite was not run (needs the full stack). The specs are unchanged and were walked through by
  hand against the DOM; `Checkout.cy.ts`/`CheckoutPaymentFailure.cy.ts` need real checkout, payment and flagd.
* Real gRPC/HTTP integration with the Java/other backends, real flagd (Connect protocol framing is based on the
  flagd-web-provider source and a mock), Envoy routing (`/flagservice`, `/images`, `/otlp-http`).
* Telemetry arriving at a real collector from the Java side (exporters were disabled in tests); the Logback
  OTLP appender end to end; whether the frontend span/metric names are accepted by existing dashboards.
* Visual fidelity: the CSS is a manual translation of the styled-components rules and has not been compared
  side by side in a browser.

## Shared changes requested (not made here)

* `compose.yaml` frontend service: the health check uses `/nodejs/bin/node` (distroless), which does not exist
  in the Temurin JRE image. Suggested: `test: ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/${FRONTEND_PORT}'"]`.
* `compose.yaml` frontend memory limit is `250M`, too low for a Spring Boot + gRPC + OTel JVM; use at least
  `512M` (the image sets `-XX:MaxRAMPercentage=75`).
* Nothing else: ports, env vars and Envoy routes are unchanged; `compose.yaml` already passes every variable
  the service reads (`FLAGD_HOST`/`FLAGD_PORT` included).
