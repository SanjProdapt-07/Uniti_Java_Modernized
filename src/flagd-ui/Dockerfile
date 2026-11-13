# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/flagd-ui/gradlew* ./src/flagd-ui/settings.gradle* ./src/flagd-ui/build.gradle ./
COPY ./src/flagd-ui/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

# The tests run against the flagd configuration that flagd itself serves, at ../flagd/demo.flagd.json
COPY ./src/flagd/demo.flagd.json ../flagd/demo.flagd.json
COPY ./src/flagd-ui/src ./src

# Like the Elixir image, build the release only when the tests pass.
RUN ./gradlew --no-daemon test bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /usr/src/app/build/libs/flagd-ui.jar ./flagd-ui.jar

# compose mounts ./src/flagd at /app/data, where the flagd configuration is read and rewritten.
ENV FLAGD_UI_STORAGE_FILE_PATH=/app/data/demo.flagd.json
ENV JAVA_TOOL_OPTIONS="-Xmx100m -XX:MaxMetaspaceSize=100m -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

ARG FLAGD_UI_PORT=4000
EXPOSE ${FLAGD_UI_PORT}
ENTRYPOINT [ "java", "-jar", "flagd-ui.jar" ]
