# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/agent/gradlew* ./src/agent/settings.gradle* ./src/agent/build.gradle ./
COPY ./src/agent/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/agent/src ./src
RUN ./gradlew --no-daemon bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:24-jre

# The working directory is /app: the VCR cassettes are read from ./fixtures/vcr_cassettes, which
# compose.agent.yaml bind-mounts from src/agent/fixtures to /app/fixtures.
WORKDIR /app

COPY --from=builder /usr/src/app/build/libs/agent.jar ./agent.jar
COPY ./src/agent/fixtures ./fixtures
ENV JAVA_TOOL_OPTIONS="-Xmx300m -XX:+ExitOnOutOfMemoryError"

ARG AGENT_PORT=8010
EXPOSE ${AGENT_PORT}
ENTRYPOINT [ "java", "-jar", "agent.jar" ]
