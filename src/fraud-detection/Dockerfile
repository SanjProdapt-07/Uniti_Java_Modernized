# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/fraud-detection/gradlew* ./src/fraud-detection/settings.gradle* ./src/fraud-detection/build.gradle ./
COPY ./src/fraud-detection/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/fraud-detection/src ./src
COPY ./pb/ ./proto
RUN ./gradlew --no-daemon bootJar -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/fraud-detection.jar ./fraud-detection.jar
ENV JAVA_TOOL_OPTIONS="-Xmx180m -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT [ "java", "-jar", "fraud-detection.jar" ]
