# Frontend service

The frontend is the OpenTelemetry Demo web store. It is a Java 21 / Spring Boot 4.1 application
(Spring Web MVC + Thymeleaf) composed of two layers:

1. **Pages and browser code.** Thymeleaf renders the page shells (layout, header, footer, the
   `window.ENV` bootstrap) and a small vanilla-JavaScript layer under
   `src/main/resources/static/assets/js` renders the dynamic parts (product grid, cart, checkout form,
   recommendations, ads) from the API. No Node build step is involved.
2. **API layer.** `/api/*` routes connect the browser to the backend services: gRPC (Spring gRPC
   client) for ad, cart, checkout, currency, product-catalog and recommendation, and HTTP (`RestClient`)
   for shipping.

Telemetry on the server comes from the OpenTelemetry Spring Boot starter plus the gRPC client
instrumentation, so no Java agent is involved. Browser telemetry comes from a vendored OpenTelemetry JS
bundle (see `tools/otel-bundle/README.md`).

See [PORTING-NOTES.md](PORTING-NOTES.md) for the mapping from the original Next.js implementation, the
deviations and what has not been verified.

## Configuration

Same environment variables as the original service (see `compose.yaml` / `.env`):

| Variable | Purpose |
|---|---|
| `PORT` / `FRONTEND_PORT` | HTTP port (default `8080`) |
| `AD_ADDR`, `CART_ADDR`, `CHECKOUT_ADDR`, `CURRENCY_ADDR`, `PRODUCT_CATALOG_ADDR`, `RECOMMENDATION_ADDR` | gRPC backends (`host:port`) |
| `SHIPPING_ADDR` | HTTP shipping service base URL |
| `ENV_PLATFORM` | label shown in the page footer |
| `WEB_OTEL_SERVICE_NAME` | `service.name` of the browser telemetry (`frontend-web`) |
| `PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | where the browser sends traces |
| `OTEL_COLLECTOR_HOST` | collector used by the browser for synthetic (load generator) requests |
| `FLAGD_HOST`, `FLAGD_PORT` | flagd for the server-side OpenFeature client |
| `OTEL_*` | standard OpenTelemetry SDK variables (`OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, ...) |
| `LOG_LEVEL` | root log level (default `info`) |

## Local Build

Requires JDK 21 or newer. The protobuf classes are generated from `../../pb/demo.proto` as part of the
Gradle build:

```sh
./gradlew bootJar test
```

## Docker Build

From the root directory, run:

```sh
docker compose build frontend
```

## End-to-end tests

The Cypress specs of the original frontend are kept unchanged in `cypress/` (with `cypress.config.ts`,
`Dockerfile.cypress`, `package.json` and the helper files they import). Run them against a running
stack, from this directory:

```sh
npm ci
npx cypress run
```
