/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.Money;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CurrencyServiceUnitTests {

  @Mock private CurrencyConverter converter;
  @Mock private CurrencyEvents events;
  @Mock private StreamObserver<Money> observer;

  private static CurrencyConversionRequest request() {
    return CurrencyConversionRequest.newBuilder()
        .setFrom(Money.newBuilder().setCurrencyCode("USD").setUnits(1))
        .setToCode("EUR")
        .build();
  }

  @Test
  void aFailingConversionIsCancelledWithoutMessageAndRecordedOnTheSpan() {
    when(converter.convert(any(), eq("EUR"))).thenThrow(new IllegalStateException("boom"));
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
            .build();
    CurrencyService service = new CurrencyService(converter, events, sdk, "test");

    Span span = sdk.getTracer("test").spanBuilder("server").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      service.convert(request(), observer);
    } finally {
      span.end();
    }

    ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(error.capture());
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
    StatusRuntimeException status = (StatusRuntimeException) error.getValue();
    assertThat(status.getStatus().getCode()).isEqualTo(Status.Code.CANCELLED);
    assertThat(status.getStatus().getDescription()).isNull();

    verify(events).error("currency.conversion_failed", "conversion failure");
    verify(events, never()).info(any(), any(), any(Attributes.class));

    SpanData data = exporter.getFinishedSpanItems().get(0);
    assertThat(data.getName()).isEqualTo("Currency/Convert");
    assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(data.getEvents())
        .extracting(EventData::getName)
        .containsExactly("Processing currency conversion request", "Conversion failed");
  }

  @Test
  void aSuccessfulConversionCompletesTheCallWithTheConvertedMoney() {
    Money converted = Money.newBuilder().setCurrencyCode("EUR").setUnits(0).setNanos(884564352).build();
    when(converter.convert(any(), eq("EUR"))).thenReturn(converted);
    CurrencyService service =
        new CurrencyService(converter, events, io.opentelemetry.api.OpenTelemetry.noop(), "test");

    service.convert(request(), observer);

    verify(observer).onNext(converted);
    verify(observer).onCompleted();
    verify(observer, never()).onError(any());
    verify(events).info(eq("currency.conversion"), eq("conversion successful"), any(Attributes.class));
  }
}
