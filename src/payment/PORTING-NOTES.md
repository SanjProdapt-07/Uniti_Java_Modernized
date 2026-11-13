# Payment service: porting notes (Node.js to Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/payment` (`index.js`, `charge.js`, `logger.js`, using
`simple-card-validator` 1.1.0). The original had no automated tests.

## Behavior mapping

| Original (Node.js) | Java |
|---|---|
| `index.js` gRPC `PaymentService.Charge` handler | `PaymentService` (`PaymentServiceImplBase`, served by `spring-boot-starter-grpc-server`) |
| `charge.js` `charge()` | `ChargeService.charge()` |
| `simple-card-validator` `getCardDetails()` | `CardValidator`: same card types, same order, same prefixes/lengths, Luhn, BIN ranges (rupay), space/dash stripping |
| `PAYMENT_PORT` | `spring.grpc.server.port: ${PAYMENT_PORT:50051}` |
| gRPC health `grpc.health.v1` (SERVING for `""`) | Spring gRPC health service (auto-configured, SERVING) |
| `paymentFailure` number flag (default 0) | `getDoubleValue("paymentFailure", 0.0)`; `random < value` fails with `Payment request failed. Invalid token. demo.user_context.loyalty_level=gold` and sets loyalty level `gold` |
| `emitRawPii` boolean flag | adds `demo.payment.card_number` (string) and `demo.payment.card_cvv` (long) to the `charge` span |
| `paymentUnreachable` flag | Not used by payment in the original either; it is evaluated in checkout (`chargeCard`). Nothing to port here |
| Accepted cards | only `visa` and `mastercard`; any other valid type gives `Sorry, we cannot process <type> credit cards. Only VISA or MasterCard is accepted.`; unknown/bad checksum/bad length gives `Credit card info is invalid.` |
| Expiry | `(currentYear*12+currentMonth) > (year*12+month)` gives `The credit card (ending NNNN) expired on M/YYYY.` (month/year unpadded, local clock) |
| Transaction id | `UUID.randomUUID()` (v4) |
| Server span attribute `demo.payment.amount` | `(units + nanos/1e9).toFixed(2)` reproduced with `BigDecimal(double).setScale(2, HALF_UP)` (same rounding of the exact binary value as JS) |
| Span `charge` (tracer `payment`, started but not made current) | same name/tracer, attributes `demo.payment.card_type`, `demo.payment.card_valid`, `demo.user_context.loyalty_level` (random of platinum/gold/silver/bronze), `demo.payment.charged`, `user_agent.synthetic.type=test`, `enduser.id` (from baggage) |
| Baggage `synthetic_request=true` | not charged (`demo.payment.charged=false`) |
| Failure handling | `charge` span: recordException, status ERROR(message), `error.type`; server span: status ERROR(message) + `error.type`; gRPC status `UNKNOWN` with the message as description (what grpc-js did for a plain Error) |
| Counter `demo.payment.transactions` `{transaction}`, attribute `demo.payment.currency` | same (meter `payment`) |
| pino JSON logs (`level`, `service.name`, structured fields `transactionId`, `cardType`, `lastFourDigits`, `amount`, `loyalty_level`) | Spring Boot structured logging, ECS JSON (`logging.structured.format.console=ecs`), key-values added with the slf4j fluent API, trace/span ids included. Field names follow ECS (`log.level`, `service.name`, `message`), not pino's exactly |
| OTel auto-instrumentation image layer | `opentelemetry-spring-boot-starter` + `opentelemetry-grpc-1.6` server interceptor (`GrpcTelemetryConfiguration`); `OTEL_*` variables work as before |
| OpenFeature + flagd provider | `dev.openfeature:sdk` + flagd provider + OTel `TracesHook` (`FeatureFlagConfiguration`, same as recommendation). The Node.js service had no traces hook |

## Deviations

* **IPV6_ENABLED:** the Node.js service bound `0.0.0.0` by default and `[::]` when `IPV6_ENABLED=true`. Spring gRPC 4.1.2
  cannot express either reliably (it mis-parses an explicit IPv6 address: with `spring.grpc.server.address=::` a real run
  listened on port 9090 instead of `PAYMENT_PORT`; this was observed by starting the jar). No address is configured, so the
  server listens on the wildcard address (IPv4 and IPv6) regardless of `IPV6_ENABLED`. A superset of both original behaviors.
* **`demo.payment.amount` with a zero part:** proto3 omits zero `units`/`nanos`. In Node.js the missing field was `undefined`,
  which most likely made the attribute `NaN` for any amount with zero nanos (not run; reasoned from proto-loader's default
  `defaults: false`). The Java version records the correct number (e.g. `45.00`).
* **Malformed requests:** a request without `amount`, without `credit_card` or with an empty card number failed in Node.js
  with a JavaScript `TypeError`. The Java version returns `UNKNOWN` with the equivalent TypeError message text and records
  `error.type=TypeError`. A request with `credit_card_expiration_year` or `_month` equal to 0 is never reported as expired
  (the Node.js sum was `NaN`, and `x > NaN` is false), which is kept.
* A thrown non-Error (the card library throws a string for an empty number) cannot happen here because an empty number is
  rejected earlier with the TypeError above, as in the original.
* Startup log lines differ (`payment gRPC server started on ...` is Spring's own `gRPC Server started` line).
* The loyalty level and failure roll use `RandomGenerator` (equivalent distribution to `Math.random`).

## Not verified

* The Docker image was **not built** (no Docker here). The `Dockerfile` follows the recommendation pattern with
  `eclipse-temurin:21-jdk`/`21-jre` and no digest pins (digests could not be looked up offline).
* No run against a real flagd, collector, checkout or frontend. Flags are tested with the OpenFeature in-memory provider
  and mocks only; the flagd provider wiring is identical to recommendation's but was not exercised against flagd.
  Numeric flag evaluation with `getDoubleValue` for integer variants (e.g. `paymentFailure` variant `1`) against real flagd is
  assumed to work and not tested.
* The jar was started locally once with exporters disabled (it boots and registers the payment, health and reflection
  services); gRPC calls were tested in-process only (`AutoConfigureTestGrpcTransport`), not over TCP.
* `CardValidator` was ported by reading `simple-card-validator` 1.1.0's source; it was not run against the JS library
  side by side. Regex/length/Luhn behavior is covered by unit tests with well-known test numbers.
* Memory: `compose.yaml` limits payment to 140M, which a Spring Boot JVM will not fit (see the report's requested shared
  changes). The image sets `-Xmx300m` like recommendation.
* The container healthcheck in `compose.yaml` runs `/nodejs/bin/node`, which does not exist in the Java image.
