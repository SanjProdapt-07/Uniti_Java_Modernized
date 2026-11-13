# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/cart/gradlew* ./src/cart/settings.gradle* ./src/cart/build.gradle ./
COPY ./src/cart/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/cart/src ./src
COPY ./pb/ ./proto
# Runs the unit tests (no Valkey or Docker needed) before packaging, like the original image
# did with its xunit tests.
RUN ./gradlew --no-daemon bootJar test -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM ghcr.io/grpc-ecosystem/grpc-health-probe:v0.4.62@sha256:1a900fcfc1b8e680511670908b47d9bc3f8464f88d9159f961c5d55c254b8140 AS grpc-health-probe

FROM docker.io/library/eclipse-temurin:24-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/cart.jar ./cart.jar
COPY --from=grpc-health-probe /ko-app/grpc-health-probe /bin/grpc_health_probe
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:+ExitOnOutOfMemoryError"

ARG CART_PORT=7070
EXPOSE ${CART_PORT}
ENTRYPOINT [ "java", "-jar", "cart.jar" ]
