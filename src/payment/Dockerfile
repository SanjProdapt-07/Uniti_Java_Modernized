# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM --platform=${BUILDPLATFORM} docker.io/library/eclipse-temurin:24-jdk AS builder
WORKDIR /usr/src/app/

COPY ./src/payment/gradlew* ./src/payment/settings.gradle* ./src/payment/build.gradle ./
COPY ./src/payment/gradle ./gradle

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon downloadRepos

COPY ./src/payment/src ./src
COPY ./pb/ ./proto
RUN ./gradlew --no-daemon bootJar -PprotoSourceDir=./proto

# -----------------------------------------------------------------------------

FROM docker.io/library/eclipse-temurin:24-jre

WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/payment.jar ./payment.jar
ENV JAVA_TOOL_OPTIONS="-Xmx300m -XX:+ExitOnOutOfMemoryError"

ARG PAYMENT_PORT=50051
EXPOSE ${PAYMENT_PORT}
ENTRYPOINT [ "java", "-jar", "payment.jar" ]
