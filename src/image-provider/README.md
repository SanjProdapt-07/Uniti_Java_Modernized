# Image Provider

The image provider serves the static product images (and the demo banner/logo) of the Astronomy Shop.
It is a Java 21 / Spring Boot 4.1 application (Spring Web MVC) that replaces the original nginx container.

Requests reach it through the frontend proxy: `/images/products/RoofBinoculars.jpg` is rewritten by Envoy to
`/products/RoofBinoculars.jpg` and served from the `static/` directory of this service.

## Build and run

```sh
./gradlew bootJar
IMAGE_PROVIDER_PORT=8081 IMAGE_PROVIDER_STATIC_ROOT=file:./static/ java -jar build/libs/image-provider.jar
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `IMAGE_PROVIDER_PORT` | `8081` | HTTP port |
| `IMAGE_PROVIDER_STATIC_ROOT` | `file:./static/` | directory served at `/` (Docker image: `/usr/src/app/static/`) |
| `OTEL_COLLECTOR_HOST` / `OTEL_COLLECTOR_PORT_GRPC` | `localhost` / `4317` | OTLP/gRPC trace endpoint (what the nginx `otel_exporter` used) |
| `OTEL_SERVICE_NAME` | `image-provider` | service name |
| `OTEL_*` | | any standard OpenTelemetry SDK variable, e.g. `OTEL_EXPORTER_OTLP_ENDPOINT`, takes precedence |

## Endpoints

* `GET /<path>`: static file from the static root (images in `/products/`, `Banner.png`,
  `opentelemetry-demo-logo.png`)
* `GET /status`: nginx `stub_status` style text, used by the compose health check

See `PORTING-NOTES.md` for the exact mapping from the nginx configuration and for known differences.
