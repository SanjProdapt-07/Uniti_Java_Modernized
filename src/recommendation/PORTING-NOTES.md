# Porting notes: recommendation

This service was already Java 21 / Spring Boot 4.1.2 with `spring-boot-starter-grpc-server`/`-client`, the OTel
Spring Boot starter and OpenFeature + flagd, so no code was ported.

Changes made for the migration standard:

* `Dockerfile`: builder `eclipse-temurin:21-jdk` and runtime `eclipse-temurin:21-jre` (was temurin 24 pinned by digest).
* `build.gradle`: added `org.mockito:mockito-junit-jupiter` so the test stack is JUnit 5 + Mockito explicitly.

Verified: clean `bootJar test` passes (7 tests). Not verified: Docker build, runs against real flagd/product-catalog.
