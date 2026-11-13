# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/checkout/gradlew* ./src/checkout/settings.gradle* ./src/checkout/build.gradle ./
COPY ./src/checkout/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/checkout/src ./src
COPY ./pb/ ./proto
RUN ./gradlew --no-daemon bootJar -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM ghcr.io/grpc-ecosystem/grpc-health-probe:v0.4.62@sha256:1a900fcfc1b8e680511670908b47d9bc3f8464f88d9159f961c5d55c254b8140 AS grpc-health-probe

FROM docker.io/library/eclipse-temurin:24-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/checkout.jar ./checkout.jar
# compose.yaml health check: /bin/grpc_health_probe -addr=:${CHECKOUT_PORT}
COPY --from=grpc-health-probe /ko-app/grpc-health-probe /bin/grpc_health_probe
ENV JAVA_TOOL_OPTIONS="-Xmx300m -XX:+ExitOnOutOfMemoryError"

ARG CHECKOUT_PORT=5050
EXPOSE ${CHECKOUT_PORT}
ENTRYPOINT [ "java", "-jar", "checkout.jar" ]
