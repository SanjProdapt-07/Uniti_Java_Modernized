/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import oteldemo.CurrencyServiceGrpc;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.Empty;
import oteldemo.Demo.GetSupportedCurrenciesResponse;
import oteldemo.Demo.Money;

/**
 * Port of the C++ {@code CurrencyService}. The original opened one SERVER span per call named
 * {@code Currency/<Method>}; here the SERVER span is the one the gRPC instrumentation already
 * started (so trace context extraction is shared), which is renamed and decorated to carry the
 * same name, attributes, events and status.
 */
@Service
public class CurrencyService extends CurrencyServiceGrpc.CurrencyServiceImplBase {

  static final String METER_NAME = "demo.exchange.conversions";
  // initIntCounter(name, version) in meter_common.h appends "_counter" to the instrument name.
  static final String COUNTER_NAME = "demo.exchange.conversions_counter";

  private static final AttributeKey<String> RPC_SYSTEM_NAME = AttributeKey.stringKey("rpc.system.name");
  private static final AttributeKey<String> RPC_METHOD = AttributeKey.stringKey("rpc.method");
  private static final AttributeKey<String> RPC_RESPONSE_STATUS_CODE =
      AttributeKey.stringKey("rpc.response.status.code");
  private static final AttributeKey<String> EXCHANGE_FROM = AttributeKey.stringKey("demo.exchange.from");
  private static final AttributeKey<String> EXCHANGE_TO = AttributeKey.stringKey("demo.exchange.to");
  private static final AttributeKey<String> LOG_CURRENCY_FROM = AttributeKey.stringKey("currency.from");
  private static final AttributeKey<String> LOG_CURRENCY_TO = AttributeKey.stringKey("currency.to");

  private final CurrencyConverter converter;
  private final CurrencyEvents events;
  private final LongCounter conversions;

  public CurrencyService(
      CurrencyConverter converter,
      CurrencyEvents events,
      OpenTelemetry openTelemetry,
      @Value("${currency.version:unknown}") String version) {
    this.converter = converter;
    this.events = events;
    this.conversions =
        openTelemetry
            .getMeterProvider()
            .meterBuilder(METER_NAME)
            .setInstrumentationVersion(version)
            .build()
            .counterBuilder(COUNTER_NAME)
            .build();
  }

  @Override
  public void getSupportedCurrencies(
      Empty request, StreamObserver<GetSupportedCurrenciesResponse> responseObserver) {
    Span span = startServerSpan("GetSupportedCurrencies");
    span.addEvent("Processing supported currencies request");

    GetSupportedCurrenciesResponse response =
        GetSupportedCurrenciesResponse.newBuilder()
            .addAllCurrencyCodes(converter.supportedCurrencies())
            .build();

    span.addEvent("Currencies fetched, response sent back");
    span.setStatus(StatusCode.OK);

    events.info("currency.get_supported_currencies", "GetSupportedCurrencies successful");

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void convert(CurrencyConversionRequest request, StreamObserver<Money> responseObserver) {
    Span span = startServerSpan("Convert");
    span.addEvent("Processing currency conversion request");

    try {
      String fromCode = request.getFrom().getCurrencyCode();
      String toCode = request.getToCode();
      Money converted = converter.convert(request.getFrom(), toCode);

      span.setAttribute(EXCHANGE_FROM, fromCode);
      span.setAttribute(EXCHANGE_TO, toCode);

      conversions.add(1, Attributes.of(EXCHANGE_TO, toCode));

      span.addEvent("Conversion successful, response sent back");
      span.setStatus(StatusCode.OK);

      events.info(
          "currency.conversion",
          "conversion successful",
          Attributes.of(LOG_CURRENCY_FROM, fromCode, LOG_CURRENCY_TO, toCode));

      responseObserver.onNext(converted);
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      span.addEvent("Conversion failed");
      span.setStatus(StatusCode.ERROR);

      events.error("currency.conversion_failed", "conversion failure");

      // Status::CANCELLED without a message, as in the original.
      responseObserver.onError(Status.CANCELLED.asRuntimeException());
    }
  }

  private static Span startServerSpan(String method) {
    Span span = Span.current();
    span.updateName("Currency/" + method);
    span.setAttribute(RPC_SYSTEM_NAME, "grpc");
    span.setAttribute(RPC_METHOD, "oteldemo.CurrencyService/" + method);
    span.setAttribute(RPC_RESPONSE_STATUS_CODE, "OK");
    return span;
  }
}
