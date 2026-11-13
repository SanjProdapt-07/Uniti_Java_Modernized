# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/currency/gradlew* ./src/currency/settings.gradle* ./src/currency/build.gradle ./
COPY ./src/currency/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/currency/src ./src
COPY ./pb/ ./proto
RUN ./gradlew --no-daemon bootJar -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/currency.jar ./currency.jar
ENV JAVA_TOOL_OPTIONS="-Xmx200m -XX:+ExitOnOutOfMemoryError"

ARG CURRENCY_PORT=7001
EXPOSE ${CURRENCY_PORT}
ENTRYPOINT [ "java", "-jar", "currency.jar" ]
