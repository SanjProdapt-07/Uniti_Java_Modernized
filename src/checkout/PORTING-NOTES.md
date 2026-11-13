# Porting notes: checkout (Go 1.26 to Java 21 / Spring Boot 4.1)

Source of truth for behavior: `opentelemetry-demo/src/checkout` (`main.go`, `kafka/producer.go`,
`money/`, `flags/flags_gen.go`, `main_test.go`, `money/money_test.go`).

## Behavior mapping

| Original (Go) | Java |
|---|---|
| `main.go` `checkout.PlaceOrder` | `CheckoutService.placeOrder` (same step order, status codes and messages) |
| `money/money.go` | `MoneyOps` |
| `mustCreateClient` + `pb.New*ServiceClient` for cart, product-catalog, currency, payment | `@ImportGrpcClients` blocking stubs (`spring-boot-starter-grpc-client`), channels named in `application.yaml` |
| shipping `/get-quote`, `/ship-order` and email `/send_order_confirmation` over `net/http` + `otelhttp` | `ShippingClient`, `EmailClient` over `RestClient` (HTTP/1.1, instrumented by the OpenTelemetry starter) |
| `kafka/producer.go` + `sendToPostProcessor` + `createProducerSpan` (Sarama `AsyncProducer`) | `OrderPublisher` over Spring Kafka `KafkaTemplate`, manual PRODUCER span |
| `flags` (`emitRawPii`, `kafkaQueueProblems`, `paymentUnreachable`) | OpenFeature client from `FeatureFlagConfiguration`, same keys and defaults |
| `otelgrpc` server handler with `filters.Not(HealthCheck())` | `GrpcTelemetry` server interceptor wrapped so `grpc.health.v1.Health/*` is not traced |
| `grpc/health` server | Spring gRPC health service (`grpc.health.v1`) |
| `uuid.NewUUID()` (version 1 UUID) | `UuidV1` (own RFC 4122 v1 generator, the JDK has none) |

### PlaceOrder flow (unchanged order)

1. span attributes `user.id`, `demo.user_context.selected_currency`; `emitRawPii` adds `user.email`,
   `demo.payment.card_number`, `demo.payment.card_cvv`; baggage `synthetic_request=true` adds
   `user_agent.synthetic.type=test`; log `[PlaceOrder]` (`user_id`, `user_currency`)
2. order id (v1 UUID)
3. internal span `prepareOrderItemsAndShippingQuoteFromCart`: cart (`GetCart`), per item
   `GetProduct` + `Convert`, HTTP `POST /get-quote`, `Convert` of the shipping cost; attributes
   `demo.shipping.amount`, `demo.cart.items.count`, `demo.order.items.count`; failure -> `INTERNAL` with the
   original wrapped messages (`cart failure: ...`, `failed to prepare order: ...`,
   `shipping quote failure: ...`, `failed to convert shipping cost to currency: ...`); event `prepared`
4. total = shipping + sum of `MultiplySlow(cost, quantity)`
5. charge (`paymentUnreachable` charges through a throw-away channel to `badAddress:50051`); failure ->
   `INTERNAL` `failed to charge card: could not charge the card: rpc error: code = X desc = Y`;
   event `charged` (`demo.payment.transaction.id`), log `payment went through`
6. HTTP `POST /ship-order`; failure -> `UNAVAILABLE` `shipping error: ...`; event `shipped`
7. empty cart, failure ignored
8. span attributes `demo.order.id`, `demo.shipping.amount`, `demo.order.amount`, `demo.order.items.count`,
   `demo.shipping.tracking.id`; log `order placed` with the same attributes
9. confirmation email (1 second bound); failure only logged as a warning
10. Kafka only if `KAFKA_ADDR` is set: span `<topic> publish` (PRODUCER; `peer.service`, `network.transport`,
    `messaging.system`, `messaging.destination.name`, `messaging.operation`,
    `messaging.kafka.destination.partition`, then `messaging.kafka.producer.success`,
    `messaging.kafka.producer.duration_ms`, `messaging.kafka.message.offset`), `traceparent`/`baggage`
    injected into the record headers, protobuf `OrderResult` payload, topic `KAFKA_TOPIC` (default
    `orders`); `kafkaQueueProblems = N` sends N extra concurrent copies (virtual threads)

Wire formats kept: the JSON bodies for shipping/email follow Go's `encoding/json` rendering of the protobuf
structs (snake_case names, zero values omitted, `"address": null` for a missing address, `"items": null`
for an empty cart, int64 as a number), see `JsonPayloads`.

## Ported tests

| Go test | Java |
|---|---|
| `money_test.go` (IsValid, IsZero, IsPositive, IsNegative, AreSameCurrency, AreEquals, Negate, Must pass/panic, Sum) | `MoneyOpsTests` (all cases, plus `multiplySlow`) |
| `TestSendOrderConfirmationHasBoundedTimeout` | `HttpClientsTests.sendOrderConfirmationHasBoundedTimeout` (a stalled server is cut off after about 1 second) and `CheckoutServiceTests.aSlowConfirmationEmailIsCutOffAfterOneSecond` |
| `TestHealthCheck` | `CheckoutServiceTests.reportsServingThroughTheStandardHealthService` |
| `TestServerStartupAndGracefulStop` | not ported 1:1: server start is exercised by every integration test and by a manual `java -jar` start; graceful stop is Spring gRPC's lifecycle and is not tested |

New tests (116 in total: `MoneyOpsTests` 61 cases, `SupportTests` 7, `HttpClientsTests` 9,
`OrderPublisherTests` 9, `CheckoutServiceUnitTests` 11, `CheckoutServiceTests` 19). The integration tests run the real
service over Spring's in-process gRPC transport with fake cart/catalog/currency/payment services, an in-JVM HTTP
server for shipping/email, an in-memory OpenFeature provider and an **embedded Kafka broker**
(`spring-kafka-test`, no Docker). They cover step order, totals, currency conversion, every error code/message,
ignored failures (empty cart, email), spans/events/attributes, log attributes and trace correlation,
`traceparent` propagation to HTTP and into the Kafka headers, `emitRawPii`, the synthetic baggage,
`kafkaQueueProblems`, and the health service.

## Deviations (known, deliberate or unavoidable)

* **Money mismatch or overflow**: Go `money.Must` panics (and, without a recovery interceptor, takes the whole
  process down). Java throws `MoneyException`, which gRPC reports as `UNKNOWN` for that request.
* **Order id**: still a version 1 UUID, produced by an own generator with a random node id (as `google/uuid` does
  without a MAC address).
* **Kafka client**: Spring Kafka (Apache Kafka client) instead of Sarama. `acks=0` is kept (Sarama `NoResponse`).
  The producer connects lazily on the first send, Sarama connected at startup. With `acks=0` the Kafka client
  reports no real offset, so `messaging.kafka.message.offset` is what the client returns (expected to be -1, not
  asserted by a test), not Sarama's value. The Kafka client instrumentation of the OpenTelemetry starter is
  switched off (`otel.instrumentation.kafka.enabled=false`) so only the demo's own producer span exists.
  Sarama's Info logger and the competing `Errors()` consumer goroutine are gone.
* **Request deadline/cancellation**: gRPC calls inherit the incoming deadline natively (gRPC `Context`), the Kafka
  wait honours cancellation/deadline like the Go `ctx.Done()` branches (tested with a cancellable context). The two
  HTTP calls do **not** inherit the incoming deadline (the email call has its own 1 second bound).
* **Error text from the network layer**: for HTTP transport errors the prefix is the original
  (`failed POST to shipping service: Post "<url>": ...`) but the cause text is Java's, and JSON decoding error
  texts differ from Go's. gRPC errors are rendered in grpc-go style (`rpc error: code = Unavailable desc = ...`).
* **Logs**: emitted through SLF4J/Logback to the console and, by the OpenTelemetry Logback appender, to the SDK
  with the same bodies and key/value attributes (checked in a test). The Go `service config: %+v` dump is replaced
  by a summary line with the same addresses. Log severities follow the original.
* **Metrics**: the Go runtime metrics (`runtime.Start`) are replaced by the JVM runtime metrics of the starter
  (different metric names). No custom checkout metrics existed in the original.
* **Traces**: the gRPC server span name/attributes come from the Java gRPC instrumentation (same convention as
  otelgrpc); exception events carry the Java exception type. Resource attributes are those of the Java starter.
* **gRPC**: besides `grpc.health.v1.Health` (now with a working `Watch`) Spring also registers the gRPC server
  reflection service.
* **Startup validation**: the required variables (`CHECKOUT_PORT`, `*_ADDR`) fail startup with a Spring "could not
  resolve placeholder" error instead of the Go panic text.
* `kafkaQueueProblems` is read as a Java `Integer` (Go `int64`).
* `osv-scanner.toml` (Go vulnerability ignore) and the Go `genproto/` and `flags/` code were removed. `flags.json`
  (flag definitions) is kept as documentation.

## Not verified

* The `Dockerfile` was written per the standard but **not built** (no Docker here). The base images are the
  unpinned `eclipse-temurin:21-jdk/-jre` tags; `grpc_health_probe` is copied in as before because `compose.yaml`
  uses `/bin/grpc_health_probe` for the health check.
* No run against the real stack: real flagd (the jar was started once locally, the gRPC server and health service
  came up, the flagd provider reported "not ready" because no flagd was running), real Kafka broker, real
  shipping (Rust), email (Ruby), cart, currency, payment, product-catalog services, the OpenTelemetry Collector.
  The shipping/email JSON was compared with their source (`shipping_types.rs`, `email_server.rb`), not exercised.
* `paymentUnreachable` is tested with a mocked channel factory and an in-process channel without a server; a real
  DNS failure for `badAddress:50051` through Spring's channel factory was not exercised.
* The embedded Kafka test uses a single in-process broker; behavior under broker restarts (the reason for
  `acks=0`) is untested.
* Memory: the JVM does not fit the 20M container limit in `compose.yaml` (see below).

## Shared changes requested (not made)

* `compose.yaml` `checkout`: raise `deploy.resources.limits.memory` (20M is Go-sized; the Dockerfile sets
  `-Xmx300m`, give it about 400M+); `GOMEMLIMIT` and `OTEL_GO_X_OBSERVABILITY` are no longer used and can go;
  optionally `OTEL_EXPERIMENTAL_SDK_TELEMETRY_VERSION`/`_JAVA_OPTIONS` like recommendation. `KAFKA_ADDR` and
  `KAFKA_TOPIC` are only passed to checkout by `compose.full.yaml`/`.env` as before: no change needed.
* `.github/dependabot.yml`: the `src-checkout` entries (`/src/checkout/**`) are Go module entries; they should
  become a Gradle entry like the other Java services.
* `Makefile` line `rm -rf ./src/{checkout,product-catalog}/genproto/oteldemo/`: checkout no longer has a
  `genproto` directory (harmless, can drop `checkout`).
