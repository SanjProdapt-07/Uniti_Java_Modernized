# Fraud Detection Service

This service receives new orders by a Kafka topic and returns cases which are
suspected of fraud.

It is a Spring Boot 4 (Java 21) application using Spring for Apache Kafka
(`@KafkaListener`). Telemetry comes from the OpenTelemetry Spring Boot starter
(no Java agent), and the feature flags are read through OpenFeature with the
flagd provider. It replaces the former Kotlin implementation with the same
behavior.

## Configuration

| Variable | Meaning |
|---|---|
| `KAFKA_ADDR` | Kafka bootstrap servers (required, the service exits with `KAFKA_ADDR is not supplied` otherwise) |
| `KAFKA_TOPIC` | Topic to consume, default `orders` |
| `FLAGD_HOST`, `FLAGD_PORT` | flagd address (read by the flagd provider) |
| `OTEL_*` | Standard OpenTelemetry SDK configuration |

The consumer group is `fraud-detection` and a freshly joined group starts at the
earliest offset.

## Feature flags

* `kafkaQueueProblems` (integer): sleep this many milliseconds before handling each record.
* `kafkaConsumerDead` (boolean): while on, the consumer leaves the group (the
  group goes Empty) and stops polling; when it is switched off again the
  consumer rejoins.

## Local Build

Requires JDK 21 or newer. The protobuf classes are generated from
`../../pb/demo.proto` as part of the Gradle build:

```sh
./gradlew build
```

The tests run offline: they use an embedded Kafka broker and an in-memory
feature flag provider.

## Docker Build

To build using Docker run from the repo root:

```sh
docker build -f ./src/fraud-detection/Dockerfile .
```

See [PORTING-NOTES.md](PORTING-NOTES.md) for the behavior mapping from the
Kotlin service.
