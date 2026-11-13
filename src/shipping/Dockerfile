# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21.0.13_9-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07 AS builder
WORKDIR /usr/src/app/

COPY ./src/shipping/gradlew* ./src/shipping/settings.gradle* ./src/shipping/build.gradle ./
COPY ./src/shipping/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/shipping/src ./src
RUN ./gradlew --no-daemon bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:21.0.13_9-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2

WORKDIR /app

COPY --from=builder /usr/src/app/build/libs/shipping.jar ./shipping.jar
# The Rust image shipped a /app/healthcheck binary that compose.yaml runs; this is its equivalent:
# it succeeds when something accepts TCP connections on $SHIPPING_PORT.
COPY --chmod=755 ./src/shipping/healthcheck.sh ./healthcheck
ENV JAVA_TOOL_OPTIONS="-Xmx128m -XX:+ExitOnOutOfMemoryError"

ARG SHIPPING_PORT=50050
EXPOSE ${SHIPPING_PORT}
ENTRYPOINT [ "java", "-jar", "shipping.jar" ]
