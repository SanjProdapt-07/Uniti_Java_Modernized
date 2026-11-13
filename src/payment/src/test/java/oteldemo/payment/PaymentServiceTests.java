/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.client.GrpcChannelFactory;
import oteldemo.Demo.ChargeRequest;
import oteldemo.Demo.ChargeResponse;
import oteldemo.Demo.CreditCardInfo;
import oteldemo.Demo.Money;
import oteldemo.PaymentServiceGrpc;

@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none"
    })
@AutoConfigureTestGrpcTransport
class PaymentServiceTests {

  private static final String VISA = "4111111111111111";

  @Autowired private GrpcChannelFactory channels;
  @Autowired private InMemorySpanExporter spans;
  @Autowired private InMemoryMetricReader metrics;
  @Autowired private InMemoryProvider featureFlags;
  @Autowired private GrpcServerProperties serverProperties;

  @BeforeEach
  void setUp() {
    OpenFeatureAPI.getInstance().setProviderAndWait(featureFlags);
    setFlags(0, false);
    spans.reset();
  }

  @AfterEach
  void tearDown() {
    setFlags(0, false);
  }

  @Test
  void chargesAValidCardAndRecordsTheTelemetry() {
    ChargeResponse response = charge(request(VISA, 2030, 1, 45, 990_000_000));

    assertThat(response.getTransactionId()).hasSize(36);

    SpanData server = spanNamed("oteldemo.PaymentService/Charge", SpanKind.SERVER);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.payment.amount")))
        .isEqualTo("45.99");
    SpanData charge = spanNamed("charge", SpanKind.INTERNAL);
    assertThat(charge.getParentSpanId()).isEqualTo(server.getSpanId());
    assertThat(charge.getAttributes().get(AttributeKey.stringKey("demo.payment.card_type")))
        .isEqualTo("visa");
    assertThat(charge.getAttributes().get(AttributeKey.booleanKey("demo.payment.charged"))).isTrue();

    long counted =
        metrics.collectAllMetrics().stream()
            .filter(metric -> metric.getName().equals("demo.payment.transactions"))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .mapToLong(point -> point.getValue())
            .sum();
    assertThat(counted).isGreaterThanOrEqualTo(1);
  }

  @Test
  void roundsTheAmountAttributeLikeToFixed() {
    charge(request(VISA, 2030, 1, 0, 5_000_000));

    SpanData server = spanNamed("oteldemo.PaymentService/Charge", SpanKind.SERVER);
    // 0.005 is stored as 0.005000000000000000104..., so JavaScript's toFixed(2) gives 0.01
    assertThat(server.getAttributes().get(AttributeKey.stringKey("demo.payment.amount")))
        .isEqualTo("0.01");
  }

  @Test
  void answersUnknownWithTheMessageForABusinessFailure() {
    assertThatThrownBy(() -> charge(request("378282246310005", 2030, 1, 10, 0)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
              assertThat(e.getStatus().getDescription())
                  .isEqualTo(
                      "Sorry, we cannot process amex credit cards. Only VISA or MasterCard is accepted.");
            });

    SpanData server = spanNamed("oteldemo.PaymentService/Charge", SpanKind.SERVER);
    assertThat(server.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(server.getAttributes().get(AttributeKey.stringKey("error.type"))).isEqualTo("Error");
    SpanData charge = spanNamed("charge", SpanKind.INTERNAL);
    assertThat(charge.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(charge.getEvents()).anyMatch(event -> event.getName().equals("exception"));
  }

  @Test
  void failsEveryChargeWhenThePaymentFailureFlagIsOneHundredPercent() {
    setFlags(1, false);

    assertThatThrownBy(() -> charge(request(VISA, 2030, 1, 10, 0)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e ->
                assertThat(e.getStatus().getDescription())
                    .isEqualTo(
                        "Payment request failed. Invalid token. demo.user_context.loyalty_level=gold"));
  }

  @Test
  void emitsTheRawCardAttributesWhenEmitRawPiiIsOn() {
    setFlags(0, true);

    charge(request(VISA, 2030, 1, 10, 0));

    SpanData charge = spanNamed("charge", SpanKind.INTERNAL);
    assertThat(charge.getAttributes().get(AttributeKey.stringKey("demo.payment.card_number")))
        .isEqualTo(VISA);
    assertThat(charge.getAttributes().get(AttributeKey.longKey("demo.payment.card_cvv")))
        .isEqualTo(123L);
  }

  @Test
  void rejectsARequestWithoutAnAmount() {
    ChargeRequest noAmount =
        ChargeRequest.newBuilder().setCreditCard(CreditCardInfo.newBuilder().setCreditCardNumber(VISA)).build();

    assertThatThrownBy(() -> charge(noAmount))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e -> {
              assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
              assertThat(e.getStatus().getDescription()).contains("reading 'units'");
            });
  }

  @Test
  void servesTheStandardGrpcHealthCheck() {
    HealthCheckResponse response =
        HealthGrpc.newBlockingStub(channels.createChannel("payment"))
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .check(HealthCheckRequest.newBuilder().setService("").build());

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
  }

  @Test
  void listensOnPaymentPortWithTheDefaultOf50051() {
    assertThat(serverProperties.getPort()).isEqualTo(50051);
    assertThat(serverProperties.getAddress()).isNull();
  }

  private ChargeResponse charge(ChargeRequest request) {
    return PaymentServiceGrpc.newBlockingStub(channels.createChannel("payment"))
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .charge(request);
  }

  private static ChargeRequest request(String number, int year, int month, long units, int nanos) {
    return ChargeRequest.newBuilder()
        .setAmount(Money.newBuilder().setCurrencyCode("USD").setUnits(units).setNanos(nanos))
        .setCreditCard(
            CreditCardInfo.newBuilder()
                .setCreditCardNumber(number)
                .setCreditCardCvv(123)
                .setCreditCardExpirationYear(year)
                .setCreditCardExpirationMonth(month))
        .build();
  }

  // The server span ends after the client already has its response, so give it a moment.
  private SpanData spanNamed(String name, SpanKind kind) {
    return await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> findSpan(name, kind), Optional::isPresent)
        .orElseThrow();
  }

  private Optional<SpanData> findSpan(String name, SpanKind kind) {
    return spans.getFinishedSpanItems().stream()
        .filter(span -> span.getName().equals(name) && span.getKind() == kind)
        .findFirst();
  }

  private void setFlags(double paymentFailure, boolean emitRawPii) {
    Map<String, Flag<?>> flags = new HashMap<>();
    flags.put(
        "paymentFailure",
        Flag.<Double>builder()
            .variant("rate", paymentFailure)
            .defaultVariant("rate")
            .build());
    flags.put(
        "emitRawPii",
        Flag.<Boolean>builder().variant("v", emitRawPii).defaultVariant("v").build());
    featureFlags.updateFlags(flags);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    InMemoryMetricReader inMemoryMetricReader() {
      return InMemoryMetricReader.create();
    }

    @Bean
    AutoConfigurationCustomizerProvider inMemoryTelemetry(
        InMemorySpanExporter spanExporter, InMemoryMetricReader metricReader) {
      return customizer ->
          customizer
              .addTracerProviderCustomizer(
                  (tracerProvider, config) ->
                      tracerProvider.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
              .addMeterProviderCustomizer(
                  (meterProvider, config) -> meterProvider.registerMetricReader(metricReader));
    }

    @Bean
    @Primary
    InMemoryProvider inMemoryFeatureProvider() {
      return new InMemoryProvider(new HashMap<>());
    }
  }
}
