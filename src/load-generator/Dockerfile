# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/load-generator/gradlew* ./src/load-generator/settings.gradle* ./src/load-generator/build.gradle ./
COPY ./src/load-generator/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/load-generator/src ./src
RUN ./gradlew --no-daemon bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:24-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/load-generator.jar ./load-generator.jar

# Chromium for LOCUST_BROWSER_TRAFFIC_ENABLED (Playwright for Java). The browsers and their OS dependencies are
# installed at build time so the container does not download anything when it starts.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
RUN java -Dloader.main=com.microsoft.playwright.CLI -cp load-generator.jar \
        org.springframework.boot.loader.launch.PropertiesLauncher install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/*
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Locust web UI port
ARG LOCUST_WEB_PORT=8089
EXPOSE ${LOCUST_WEB_PORT}

ENV JAVA_TOOL_OPTIONS="-Xmx400m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT [ "java", "-jar", "load-generator.jar" ]
