# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/mcp/gradlew* ./src/mcp/settings.gradle* ./src/mcp/build.gradle ./
COPY ./src/mcp/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/mcp/src ./src
RUN ./gradlew --no-daemon bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:24-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/mcp.jar ./mcp.jar
ENV JAVA_TOOL_OPTIONS="-Xmx300m -XX:+ExitOnOutOfMemoryError"

ARG MCP_PORT=8011
EXPOSE ${MCP_PORT}
ENTRYPOINT [ "java", "-jar", "mcp.jar" ]
