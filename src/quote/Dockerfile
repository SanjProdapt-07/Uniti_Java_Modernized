# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/quote/gradlew* ./src/quote/settings.gradle* ./src/quote/build.gradle ./
COPY ./src/quote/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/quote/src ./src
RUN ./gradlew --no-daemon bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/quote.jar ./quote.jar
ENV JAVA_TOOL_OPTIONS="-Xmx128m -XX:+ExitOnOutOfMemoryError"

ARG QUOTE_PORT=8090
EXPOSE ${QUOTE_PORT}
ENTRYPOINT [ "java", "-jar", "quote.jar" ]
