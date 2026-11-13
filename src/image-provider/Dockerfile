# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:21-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/image-provider/gradlew* ./src/image-provider/settings.gradle* ./src/image-provider/build.gradle ./
COPY ./src/image-provider/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/image-provider/src ./src
RUN ./gradlew --no-daemon bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/image-provider.jar ./image-provider.jar
COPY ./src/image-provider/static/ ./static/

# nginx wrote its access log to stdout; do the same with the embedded Tomcat access log.
ENV SERVER_TOMCAT_ACCESSLOG_ENABLED=true \
    SERVER_TOMCAT_ACCESSLOG_DIRECTORY=/dev \
    SERVER_TOMCAT_ACCESSLOG_PREFIX=stdout \
    SERVER_TOMCAT_ACCESSLOG_SUFFIX= \
    SERVER_TOMCAT_ACCESSLOG_FILE_DATE_FORMAT= \
    SERVER_TOMCAT_ACCESSLOG_BUFFERED=false \
    SERVER_TOMCAT_ACCESSLOG_PATTERN=combined \
    SERVER_TOMCAT_ACCESSLOG_ROTATE=false

ENV JAVA_TOOL_OPTIONS="-Xmx64m -XX:+ExitOnOutOfMemoryError"

ARG IMAGE_PROVIDER_PORT=8081
EXPOSE ${IMAGE_PROVIDER_PORT}
ENTRYPOINT [ "java", "-jar", "image-provider.jar" ]
