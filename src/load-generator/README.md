# Load Generator

The load generator creates simulated traffic to the demo. It is a Java 21 / Spring Boot 4.1 application that runs
each simulated user on a virtual thread and offers a small Locust-compatible control surface (web UI, swarm,
stop, statistics). It replaces the original Locust/Python implementation (`locustfile.py`).

## Accessing the Load Generator

The web interface is at `http://localhost:8080/loadgen/` (Envoy rewrites `/loadgen/` to `/` of this service,
which listens on `LOCUST_WEB_PORT`, default `8089`).

## Build and run

```sh
./gradlew bootJar
LOCUST_HOST=http://localhost:8080 LOCUST_AUTOSTART=true LOCUST_USERS=5 java -jar build/libs/load-generator.jar
```

## Simulated users

* `WebsiteUser` (plain HTTP, weight `LOCUST_HTTP_USER_WEIGHT`, default 9): tasks `index` (1), `browse_product` (10),
  `get_recommendations` (3), `get_ads` (3), `view_cart` (3), `add_to_cart` (2), `checkout` (1), `checkout_multi` (1),
  `flood_home` (5, driven by the `loadGeneratorFloodHomepage` flag), `ask_agent` (3); waits 1 to 10 seconds
  between tasks.
* `WebsiteBrowserUser` (headless Chromium through Playwright for Java, weight `LOCUST_BROWSER_USER_WEIGHT`,
  default 1): only when `LOCUST_BROWSER_TRAFFIC_ENABLED` is `true`, `yes` or `on`. Each user keeps its browser
  process for its whole life, so more browser users means more memory.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `LOCUST_WEB_PORT` / `LOCUST_WEB_HOST` | `8089` / `0.0.0.0` | UI listen port / address |
| `LOCUST_HOST` | empty | base URL of the site under test (also settable in the UI) |
| `LOCUST_USERS`, `LOCUST_SPAWN_RATE` | `1`, `1` | users and users per second for headless / autostart |
| `LOCUST_AUTOSTART`, `LOCUST_HEADLESS` | `false` | start swarming at startup |
| `LOCUST_RUN_TIME` | unlimited | e.g. `30s`, `10m`, `1h30m` |
| `LOCUST_AUTOQUIT` | `-1` | seconds to wait after the run ended before exiting (UI mode); headless exits right away |
| `LOCUST_BROWSER_TRAFFIC_ENABLED` | off | `true`/`yes`/`on` spawns browser users |
| `LOCUST_HTTP_USER_WEIGHT` / `LOCUST_BROWSER_USER_WEIGHT` | `9` / `1` | relative weights of the user types |
| `FLAGD_HOST`, `FLAGD_PORT` | `localhost`, `8013` | flagd (OpenFeature flagd provider) |
| `AGENT_ENDPOINT`, `AGENT_PORT` | `agent`, `8010` | target of the `ask_agent` task |
| `OTEL_*` | | standard OpenTelemetry SDK variables (`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`, ...) |

## HTTP surface

`GET /` (UI), `POST /swarm` (`user_count`, `spawn_rate`, `host`, `run_time`), `GET /stop`, `GET /stats/requests`,
`GET /stats/reset`, `GET /stats/requests/csv`, `GET /stats/failures/csv`, `GET /exceptions`, `GET /tasks`.

See `PORTING-NOTES.md` for the mapping from the locustfile and for the differences to Locust.
