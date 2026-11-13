# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21.0.13_9-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07 AS builder
WORKDIR /usr/src/app/

COPY ./src/product-catalog/gradlew* ./src/product-catalog/settings.gradle* ./src/product-catalog/build.gradle ./
COPY ./src/product-catalog/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/product-catalog/src ./src
COPY ./pb/ ./proto
RUN ./gradlew --no-daemon bootJar -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM ghcr.io/grpc-ecosystem/grpc-health-probe:v0.4.61@sha256:9df7b8dfbaf01d20eaedcd6de084c557074046f87cfc0413b7939ae18087c249 AS grpc-health-probe

FROM docker.io/library/eclipse-temurin:21.0.13_9-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/product-catalog.jar ./product-catalog.jar
# compose.yaml runs /bin/grpc_health_probe as the health check of this service
COPY --from=grpc-health-probe /ko-app/grpc-health-probe /bin/grpc_health_probe
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:+ExitOnOutOfMemoryError"

ARG PRODUCT_CATALOG_PORT=3550
EXPOSE ${PRODUCT_CATALOG_PORT}
ENTRYPOINT [ "java", "-jar", "product-catalog.jar" ]
