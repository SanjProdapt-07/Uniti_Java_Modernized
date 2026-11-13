# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0

FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/accounting/gradlew* ./src/accounting/settings.gradle* ./src/accounting/build.gradle ./
COPY ./src/accounting/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/accounting/src ./src
COPY ./pb/ ./proto
RUN ./gradlew --no-daemon bootJar -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/accounting.jar ./accounting.jar
ENV JAVA_TOOL_OPTIONS="-Xmx128m -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT [ "java", "-jar", "accounting.jar" ]
