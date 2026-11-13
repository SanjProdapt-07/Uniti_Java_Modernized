# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/chatbot/gradlew* ./src/chatbot/settings.gradle* ./src/chatbot/build.gradle ./
COPY ./src/chatbot/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/chatbot/src ./src
RUN ./gradlew --no-daemon bootJar

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:24-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/chatbot.jar ./chatbot.jar
ENV JAVA_TOOL_OPTIONS="-Xmx300m -XX:+ExitOnOutOfMemoryError"

ARG CHATBOT_PORT=7860
EXPOSE ${CHATBOT_PORT}
ENTRYPOINT [ "java", "-jar", "chatbot.jar" ]
