# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/email/gradlew* ./src/email/settings.gradle* ./src/email/build.gradle ./
COPY ./src/email/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/email/src ./src
COPY ./pb/ ./proto
RUN ./gradlew --no-daemon bootJar -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/email.jar ./email.jar
ENV JAVA_TOOL_OPTIONS="-Xmx300m -XX:+ExitOnOutOfMemoryError"

ARG EMAIL_PORT=6060
EXPOSE ${EMAIL_PORT}
ENTRYPOINT [ "java", "-jar", "email.jar" ]
