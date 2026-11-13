/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import oteldemo.shipping.QuoteClient.QuoteException;
import oteldemo.shipping.ShippingTypes.Quote;

class QuoteClientTests {

  @RegisterExtension static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

  private static final String QUOTE_ADDR = "http://quote.test:8090";

  private MockRestServiceServer server;
  private QuoteClient quoteClient;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    quoteClient = new QuoteClient(builder, otel.getOpenTelemetry(), QUOTE_ADDR);
  }

  @Test
  void postsTheItemCountToGetquoteAndParsesTheBareNumber() {
    server
        .expect(requestTo(QUOTE_ADDR + "/getquote"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Content-Type", "application/json"))
        .andExpect(content().string("{\"numberOfItems\":3}"))
        .andRespond(withSuccess("10.99", MediaType.TEXT_PLAIN));

    Quote quote = quoteClient.createQuoteFromCount(3);

    assertThat(quote.dollars()).isEqualTo(BigInteger.TEN);
    assertThat(quote.cents()).isEqualTo(99);
    server.verify();
  }

  @Test
  void anyHttpStatusIsAcceptedAndOnlyTheBodyDecides() {
    server
        .expect(requestTo(QUOTE_ADDR + "/getquote"))
        .andRespond(withServerError().body("8.99"));

    Quote quote = quoteClient.createQuoteFromCount(1);

    assertThat(quote).hasToString("8.99");
  }

  @Test
  void anErrorStatusWithANonNumericBodyFailsToParse() {
    server
        .expect(requestTo(QUOTE_ADDR + "/getquote"))
        .andRespond(withServerError().body("internal error"));

    assertThatThrownBy(() -> quoteClient.createQuoteFromCount(1))
        .isInstanceOf(QuoteException.class)
        .hasMessage("Failed to parse quote value as f64");
  }

  @Test
  void aBodyWithWhitespaceAroundTheNumberDoesNotParseLikeInRust() {
    server.expect(requestTo(QUOTE_ADDR + "/getquote")).andRespond(withSuccess("10.99\n", MediaType.TEXT_PLAIN));

    assertThatThrownBy(() -> quoteClient.createQuoteFromCount(1))
        .isInstanceOf(QuoteException.class)
        .hasMessage("Failed to parse quote value as f64");
  }

  @Test
  void aBodyThatIsNotUtf8IsRejected() {
    server
        .expect(requestTo(QUOTE_ADDR + "/getquote"))
        .andRespond(withSuccess(new byte[] {(byte) 0xff, (byte) 0xfe}, MediaType.TEXT_PLAIN));

    assertThatThrownBy(() -> quoteClient.createQuoteFromCount(1))
        .isInstanceOf(QuoteException.class)
        .hasMessage("Failed to parse quote service response as UTF-8");
  }

  @Test
  void aConnectionFailureIsReportedAsAFailedCall() {
    server
        .expect(requestTo(QUOTE_ADDR + "/getquote"))
        .andRespond(
            request -> {
              throw new IOException("connection refused");
            });

    assertThatThrownBy(() -> quoteClient.createQuoteFromCount(1))
        .isInstanceOf(QuoteException.class)
        .hasMessageStartingWith("Failed to call quote service: ")
        .hasMessageContaining("connection refused");
  }

  @Test
  void recordsTheItemsShippedCounterOnlyForSuccessfulQuotes() {
    server.expect(requestTo(QUOTE_ADDR + "/getquote")).andRespond(withSuccess("1.00", MediaType.TEXT_PLAIN));
    quoteClient.createQuoteFromCount(4);
    assertThat(itemsShipped()).isEqualTo(4);

    server.reset();
    server.expect(requestTo(QUOTE_ADDR + "/getquote")).andRespond(withServerError().body("x"));
    assertThatThrownBy(() -> quoteClient.createQuoteFromCount(9)).isInstanceOf(QuoteException.class);
    assertThat(itemsShipped()).isEqualTo(4);
  }

  @Test
  void addsTheCostEventAndAttributeToTheActiveSpan() {
    server.expect(requestTo(QUOTE_ADDR + "/getquote")).andRespond(withSuccess("35.96", MediaType.TEXT_PLAIN));

    Span span = otel.getOpenTelemetry().getTracer("test").spanBuilder("request").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      quoteClient.createQuoteFromCount(2);
    } finally {
      span.end();
    }

    List<SpanData> spans = otel.getSpans();
    assertThat(spans).hasSize(1);
    SpanData data = spans.get(0);
    assertThat(data.getAttributes().get(AttributeKey.stringKey("demo.shipping.cost.total")))
        .isEqualTo("35.96");
    assertThat(data.getEvents()).hasSize(1);
    assertThat(data.getEvents().get(0).getName()).isEqualTo("shipping.quote.received");
    assertThat(
            data.getEvents().get(0).getAttributes().get(AttributeKey.stringKey("demo.shipping.cost.total")))
        .isEqualTo("35.96");
  }

  private long itemsShipped() {
    return otel.getMetrics().stream()
        .filter(metric -> metric.getName().equals("demo.shipping.items_shipped"))
        .map(MetricData::getLongSumData)
        .flatMap(sum -> sum.getPoints().stream())
        .mapToLong(LongPointData::getValue)
        .sum();
  }
}
