# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24.0.2_12-jdk@sha256:7493205ffe6caa8074fa8a06a276bb1c5ac41d3dd0fd43a0db66d7f776e80b3e AS builder
WORKDIR /usr/src/app/

COPY ./src/shipping/gradlew* ./src/shipping/settings.gradle* ./src/shipping/build.gradle ./
COPY ./src/shipping/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/shipping/src ./src
RUN ./gradlew --no-daemon bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:24.0.2_12-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2

WORKDIR /app

COPY --from=builder /usr/src/app/build/libs/shipping.jar ./shipping.jar
# The Rust image shipped a /app/healthcheck binary that compose.yaml runs; this is its equivalent:
# it succeeds when something accepts TCP connections on $SHIPPING_PORT.
COPY --chmod=755 ./src/shipping/healthcheck.sh ./healthcheck
ENV JAVA_TOOL_OPTIONS="-Xmx128m -XX:+ExitOnOutOfMemoryError"

ARG SHIPPING_PORT=50050
EXPOSE ${SHIPPING_PORT}
ENTRYPOINT [ "java", "-jar", "shipping.jar" ]
