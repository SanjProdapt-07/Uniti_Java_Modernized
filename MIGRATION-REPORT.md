# Migration Report: OpenTelemetry Demo to Java 21 / Spring Boot 4.1

Source of truth for behavior: the untouched original in `d:\dev2\mordernization\opentelemetry-demo`.
Standards followed: `MIGRATION-STANDARDS.md`. Per-service details: `src/<service>/PORTING-NOTES.md`.

## What was migrated

| Service | Was | Now (Spring Boot 4.1.2, Java 21, Gradle Groovy) |
|---|---|---|
| ad | Java, plain grpc-java + OTel agent | `starter-grpc-server`, OTel starter |
| recommendation | Spring Boot 4.1 (already) | unchanged apart from temurin 21 image and Mockito |
| cart | .NET 10 + Valkey | `starter-grpc-server`, Lettuce |
| checkout | Go 1.26, Kafka producer | `starter-grpc-server` + `-client`, Spring Kafka, `RestClient` |
| currency | C++ | `starter-grpc-server` |
| payment | Node.js | `starter-grpc-server` |
| product-catalog | Go + Postgres | `starter-grpc-server`, Spring JDBC |
| email | Ruby/Sinatra | Web MVC `@RestController` |
| quote | PHP/Slim | Web MVC `@RestController` |
| shipping | Rust/actix-web | Web MVC `@RestController` |
| accounting | .NET 10 EF Core + Postgres, Kafka consumer | Spring Kafka `@KafkaListener` + JDBC |
| fraud-detection | Kotlin, Kafka consumer | Spring Kafka `@KafkaListener` |
| frontend | Next.js | Web MVC + Thymeleaf + static JS, gRPC client |
| image-provider | nginx | Web MVC static serving |
| flagd-ui | Elixir/Phoenix LiveView | Web MVC + Thymeleaf + JS polling |
| agent / mcp / chatbot | Python | Spring AI 2.0.2 (agent, mcp) / Web MVC + Thymeleaf (chatbot) |
| load-generator | Locust | Spring Boot runner on virtual threads (+ optional Playwright) |

Kept as-is (per instruction): frontend-proxy (Envoy), flagd, kafka, postgresql, otel-collector, jaeger, grafana,
prometheus, opensearch, opamp-server, react-native-app, telemetry-schema, tests, repo-level config.

## Shared files changed

* `compose.yaml`: JVM-appropriate memory limits; healthchecks that used `grpc_health_probe`, `nc`, `php`, `ruby`,
  `python3`, `/nodejs/bin/node` replaced by a bash `/dev/tcp` check; removed unused `GOMEMLIMIT`,
  `OTEL_GO_X_OBSERVABILITY`, `OTEL_CONFIG_FILE`, `OTEL_PHP_*`, `ASPNETCORE_HTTP_PORTS`, and the
  `OTEL_JAVA_AGENT_VERSION` / `OPENTELEMETRY_CPP_VERSION` build args of migrated services.
* `compose.full.yaml`: accounting memory and env, fraud-detection build arg.
* `.env`: `CART_DOCKERFILE` path.
* `.github/dependabot.yml`: migrated services now use the `gradle` ecosystem.
* `docker-gen-proto.sh`, `ide-gen-proto.sh`: Java services generate stubs in the Gradle build.
* `src/shared/tools.py` removed (only the Python agent/mcp used it).

## Known gaps (not verified or not reproduced)

* **No Docker image was built and no compose stack was started** (Docker is not available here). Dockerfiles follow
  the recommendation pattern but are untested; `wget`/`curl` presence in `eclipse-temurin:21-jre` was not checked.
* **No integration against real Kafka, Valkey, flagd, collector, Envoy or an LLM.** Tests use embedded Kafka,
  in-process gRPC, H2 (PostgreSQL mode), a fake RESP server and mocks. product-catalog's Postgres tests were run once
  against a throwaway Postgres 18.6 and passed.
* **Not byte-compared with the originals:** currency math (checked against a Python re-implementation of the C++
  algorithm), the email template (derived from the ERB/Tilt source), quote number formatting (PHP semantics read,
  not run), the frontend and flagd-ui CSS.
* **Behavior differences worth knowing** (each service's `PORTING-NOTES.md` has the full list): frontend navigation
  is full page loads rather than SPA; flagd-ui polls instead of using a LiveView websocket; chatbot is not Gradio;
  load-generator exposes a subset of Locust's UI/API; `IPV6_ENABLED` is not honored by Spring gRPC services;
  runtime metric names are JVM rather than .NET/Go/Node; span names for agent/mcp were derived from the Python
  instrumentation source, not recorded traces.
* **Not updated:** `.github/workflows/*` jobs that still lint or test the removed languages, and the
  `test/` tracetesting and `telemetry-schema` metric names (currency emits `demo.exchange.conversions_counter`, as the
  C++ really did, which differs from the documented schema name).
* Spring AI 2.0.2 is the release line built against Boot 4.1; 2.1.x targets Boot 4.2.

## Verified build and test results

Clean `clean bootJar test` on JDK 21 + Gradle 9.9.0, one run per service, all 19 builds succeeded (0 failures, 0 errors).

| Service | Tests | Skipped |
|---|---|---|
| accounting | 36 | 0 |
| ad | 42 | 0 |
| agent | 63 | 0 |
| cart | 71 | 0 |
| chatbot | 19 | 0 |
| checkout | 116 | 0 |
| currency | 60 | 0 |
| email | 23 | 0 |
| flagd-ui | 95 | 0 |
| fraud-detection | 22 | 0 |
| frontend | 66 | 0 |
| image-provider | 35 | 0 |
| load-generator | 99 | 0 |
| mcp | 30 | 0 |
| payment | 46 | 0 |
| product-catalog | 61 | 3 |
| quote | 39 | 0 |
| recommendation | 7 | 0 |
| shipping | 54 | 0 |
| **Total** | **984** | |

product-catalog's 3 skipped tests are opt-in Postgres tests (run once against a throwaway Postgres 18.6; they passed).
