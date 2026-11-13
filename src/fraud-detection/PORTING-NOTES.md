# Porting notes: fraud-detection (Kotlin -> Java 21 / Spring Boot 4.1)

Original: `opentelemetry-demo/src/fraud-detection` (`src/main/kotlin/frauddetection/main.kt`, `log4j2.xml`,
`build.gradle.kts`, Dockerfile with the OpenTelemetry Java agent). All Kotlin sources and the Gradle Kotlin
DSL files were removed; the Gradle build is now Groovy DSL.

## Behavior mapping

| Original (Kotlin) | Java |
|---|---|
| `KAFKA_ADDR` missing: prints `KAFKA_ADDR is not supplied`, exit code 1 | same, in `FraudDetectionApplication.main` |
| Topic `KAFKA_TOPIC`, default `orders` | `fraud-detection.topic: ${KAFKA_TOPIC:orders}` |
| Consumer: group `fraud-detection`, `String` key / `byte[]` value, `auto.offset.reset=earliest` (read from the start so a freshly joined group still sees earlier orders), `max.poll.records=30` | `application.yaml` (same properties, same comments); `@KafkaListener` in `OrderListener` |
| Record: flag `kafkaQueueProblems` (int, default 0) evaluated per record; if > 0 logs `FeatureFlag 'kafkaQueueProblems' is enabled, sleeping {ms}ms` and sleeps | `OrderListener.onRecord`, same flag, log message and per-record sleep |
| Record: `OrderResult.parseFrom`, logs `Consumed record with orderId: {id}, and updated total count to: {n}` with a running total incremented per record | same message; total kept in an `AtomicLong` |
| Flag `kafkaConsumerDead` (boolean, default false) checked every loop: when on, logs `FeatureFlag 'kafkaConsumerDead' is enabled, unsubscribing from '{topic}' (group goes Empty, members -> 0)`, unsubscribes, no polling, re-check every 1000 ms; when off again logs `... is disabled, re-subscribing to '{topic}' (rejoining group)` and subscribes again | `ConsumerGroupMembership` (a lifecycle thread, `tick()` per iteration): stops the listener container, which closes the Kafka consumer so the member leaves the group (group Empty, 0 members), re-checks every 1000 ms; starts the container again (new consumer rejoins) when the flag is off; flag re-checked every 100 ms otherwise (the poll timeout of the Kotlin loop). Same log messages. The consumer only joins the group once the flag has been read as off, as before. |
| `getFeatureFlagValue` / `getBooleanFeatureFlagValue`: client evaluation context with a random `session` UUID per lookup, defaults 0 / false | `FeatureFlags`: same flag keys, defaults and a random `session` UUID in the evaluation context of each lookup |
| flagd provider with `withGlobalTelemetry(true)`, no hooks; telemetry through the Java agent | `FeatureFlagConfiguration`: flagd provider with the OpenTelemetry instance + OTel `TracesHook` (as in recommendation). `FLAGD_HOST` / `FLAGD_PORT` are read by the provider as before |
| log4j2 layout `%d{yyyy-MM-dd HH:mm:ss} - %logger{36} - %msg trace_id=%X{trace_id} span_id=%X{span_id} trace_flags=%X{trace_flags} %n`, logger name `fraud-detection` | `logback-spring.xml` with the identical pattern; the OpenTelemetry logback MDC appender fills `trace_id` / `span_id` / `trace_flags`; logger name `fraud-detection` |
| Logs/traces/metrics exported by the Java agent | OpenTelemetry Spring Boot starter, no agent; logs exported by the OpenTelemetry logback appender; Kafka consumer instrumentation through `opentelemetry-spring-kafka-2.7` (auto-configured by the starter) |
| compose: `OTEL_INSTRUMENTATION_KAFKA_EXPERIMENTAL_SPAN_ATTRIBUTES=true`, `OTEL_INSTRUMENTATION_MESSAGING_EXPERIMENTAL_RECEIVE_TELEMETRY_ENABLED=true` | honored by the starter's Kafka instrumentation (the integration test sets the equivalent properties and sees `kafka.*` span attributes and the producer context as a span link) |
| `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_*`, ... | read by the starter (same names) |
| Dockerfile: Gradle 9.8 / JDK 17 build, distroless Java 17 runtime plus `ADD` of the Java agent | multi-stage Temurin 21 JDK / JRE (recommendation pattern), no agent, `-Xmx180m` kept; the unused `OTEL_JAVA_AGENT_VERSION` build arg passed by compose is simply ignored |

## Deviations

1. **Offset commits.** The Kotlin consumer used the Kafka client's default 5 s auto commit. With
   `enable.auto.commit=true` a Spring Kafka container that was stopped right after processing an order
   re-read that order after it rejoined (seen while testing), so the offsets are committed by Spring Kafka
   after each processed poll batch instead (`AckMode BATCH`, `enable.auto.commit` not set). Consumption
   from the committed offset after a `kafkaConsumerDead` cycle is therefore exact.
2. **Unprocessable records.** In Kotlin an unparseable record threw out of `main`, ended the process and relied on
   the restart policy. Now the record is logged at error level (`Skipping record at topic-partition@offset ...`)
   and skipped, without retries (`KafkaErrorHandlingConfiguration`).
3. **Spans.** The agent produced a `receive` span per poll with per-record `process` spans below it. Spring Kafka's
   record interceptor produces one `{topic} process` CONSUMER span per record. With receive telemetry enabled it
   starts its own trace and links to the producer's span; no separate `receive` span exists. Span attributes are
   those of the OpenTelemetry Spring Kafka instrumentation library.
4. The flag value is passed as a per call evaluation context instead of being set on the client before each call
   (same evaluation, safe for concurrent use).
5. Added the OpenTelemetry OpenFeature `TracesHook` (not present in the Kotlin service) following the migration standard.

## Tests

`./gradlew test`: 22 tests, no Docker (embedded Kafka from spring-kafka-test, in-memory OpenFeature provider).
No tests existed for the original service. Covered: flag lookups and defaults (Mockito + in-memory provider),
listener logging/total/throttling, `ConsumerGroupMembership` state transitions and log messages (Mockito),
and end-to-end with a real (embedded) broker: consumer configuration, running total, `kafkaQueueProblems`
throttling, `kafkaConsumerDead` leaving the group (verified through the Kafka admin API: members go 1 -> 0 -> 1)
without losing orders produced meanwhile, skipping a poison record, and consumer span / producer link /
experimental attributes.

## Not verified

* The Docker image was **not** built (no Docker available here).
* No real flagd: flag behavior was exercised with the OpenFeature in-memory provider. The flagd provider is wired
  as in the recommendation service, but connection to a real flagd, the `session` targeting context against real
  flag definitions and `FLAGD_HOST` / `FLAGD_PORT` handling were not tested.
* `auto.offset.reset=earliest` is asserted on the consumer configuration only; a cold-start test with a pre-filled
  topic and a brand new group was not run.
* Only the embedded KRaft broker was used, not the demo's Kafka image; OTLP export to the collector was not exercised.
* The built `bootJar` was started against an unreachable broker (stays alive, flagd in error state), and without
  `KAFKA_ADDR` (prints the message, exit code 1).

## Shared changes needed (not made here)

* None required. `compose.full.yaml` `fraud-detection` keeps working: the 300M memory limit should be sufficient for (not measured)
  `-Xmx180m`; the `OTEL_JAVA_AGENT_VERSION` build arg is now unused and the `OTEL_INSTRUMENTATION_*` variables keep their names (the integration test exercises the two used by compose through their property equivalents).
