# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24.0.2_12-jdk@sha256:7493205ffe6caa8074fa8a06a276bb1c5ac41d3dd0fd43a0db66d7f776e80b3e AS builder
WORKDIR /usr/src/app/

COPY ./src/product-catalog/gradlew* ./src/product-catalog/settings.gradle* ./src/product-catalog/build.gradle ./
COPY ./src/product-catalog/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/product-catalog/src ./src
COPY ./pb/ ./proto
RUN ./gradlew --no-daemon bootJar -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM ghcr.io/grpc-ecosystem/grpc-health-probe:v0.4.62@sha256:1a900fcfc1b8e680511670908b47d9bc3f8464f88d9159f961c5d55c254b8140 AS grpc-health-probe

FROM docker.io/library/eclipse-temurin:24.0.2_12-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/product-catalog.jar ./product-catalog.jar
# compose.yaml runs /bin/grpc_health_probe as the health check of this service
COPY --from=grpc-health-probe /ko-app/grpc-health-probe /bin/grpc_health_probe
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:+ExitOnOutOfMemoryError"

ARG PRODUCT_CATALOG_PORT=3550
EXPOSE ${PRODUCT_CATALOG_PORT}
ENTRYPOINT [ "java", "-jar", "product-catalog.jar" ]
