/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import oteldemo.Demo.Address;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.Money;
import oteldemo.Demo.OrderResult;
import oteldemo.checkout.TestHttpServer.Response;

/** Tests of the shipping and email HTTP clients against an in-JVM HTTP server. */
class HttpClientsTests {

  private static TestHttpServer server;
  private static ShippingClient shipping;
  private static EmailClient email;

  private static final Address ADDRESS =
      Address.newBuilder()
          .setStreetAddress("1600 Amphitheatre Parkway")
          .setCity("Mountain View")
          .setState("CA")
          .setCountry("United States")
          .setZipCode("94043")
          .build();
  private static final List<CartItem> ITEMS =
      List.of(CartItem.newBuilder().setProductId("OLJCESPC7Z").setQuantity(2).build());

  @BeforeAll
  static void startServer() {
    server = new TestHttpServer(null);
    HttpClientConfiguration configuration = new HttpClientConfiguration();
    shipping = configuration.shippingClient(RestClient.builder(), server.baseUrl());
    email = configuration.emailClient(RestClient.builder(), server.baseUrl());
  }

  @AfterAll
  static void stopServer() {
    server.close();
  }

  @BeforeEach
  void reset() {
    server.reset();
  }

  @Test
  void quotesShippingWithTheGoJsonPayload() {
    server.on(
        "/get-quote",
        request ->
            new Response(200, "{\"cost_usd\":{\"currency_code\":\"USD\",\"units\":8,\"nanos\":990000000}}"));

    Money quote = shipping.quoteShipping(ADDRESS, ITEMS);

    assertThat(quote.getCurrencyCode()).isEqualTo("USD");
    assertThat(quote.getUnits()).isEqualTo(8);
    assertThat(quote.getNanos()).isEqualTo(990_000_000);
    TestHttpServer.Request request = server.requests().get(0);
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.header("Content-Type")).startsWith("application/json");
    assertThat(request.body())
        .isEqualTo(
            "{\"address\":{\"street_address\":\"1600 Amphitheatre Parkway\",\"city\":\"Mountain View\","
                + "\"state\":\"CA\",\"country\":\"United States\",\"zip_code\":\"94043\"},"
                + "\"items\":[{\"product_id\":\"OLJCESPC7Z\",\"quantity\":2}]}");
  }

  @Test
  void quoteRejectsNon200AndMissingCost() {
    server.on("/get-quote", request -> new Response(500, ""));
    assertThatThrownBy(() -> shipping.quoteShipping(ADDRESS, ITEMS))
        .isInstanceOf(StepFailure.class)
        .hasMessage("failed POST to shipping service: expected 200, got 500");

    server.on("/get-quote", request -> new Response(200, "{}"));
    assertThatThrownBy(() -> shipping.quoteShipping(ADDRESS, ITEMS))
        .hasMessage("shipping quote missing cost_usd field");

    server.on("/get-quote", request -> new Response(200, "not json"));
    assertThatThrownBy(() -> shipping.quoteShipping(ADDRESS, ITEMS))
        .hasMessageStartingWith("failed to unmarshal shipping quote: ");
  }

  @Test
  void shipsOrderAndReturnsTheTrackingId() {
    server.on("/ship-order", request -> new Response(200, "{\"tracking_id\":\"TRACK-1\"}"));

    assertThat(shipping.shipOrder(ADDRESS, ITEMS)).isEqualTo("TRACK-1");
    assertThat(server.requests().get(0).path()).isEqualTo("/ship-order");
  }

  @Test
  void shipOrderRejectsNon200AndMissingTrackingId() {
    server.on("/ship-order", request -> new Response(503, ""));
    assertThatThrownBy(() -> shipping.shipOrder(ADDRESS, ITEMS))
        .hasMessage("failed POST to shipping service: expected 200, got 503");

    server.on("/ship-order", request -> new Response(200, "{\"tracking_id\":\"\"}"));
    assertThatThrownBy(() -> shipping.shipOrder(ADDRESS, ITEMS))
        .hasMessage("ship order response missing tracking_id field");
  }

  @Test
  void shippingFailsWhenTheServiceIsUnreachable() {
    ShippingClient unreachable =
        new HttpClientConfiguration().shippingClient(RestClient.builder(), "http://127.0.0.1:1");

    assertThatThrownBy(() -> unreachable.shipOrder(ADDRESS, ITEMS))
        .isInstanceOf(StepFailure.class)
        .hasMessageStartingWith("failed POST to shipping service: Post \"http://127.0.0.1:1/ship-order\"");
  }

  @Test
  void sendsTheOrderConfirmation() {
    server.on("/send_order_confirmation", request -> new Response(200, ""));

    email.sendOrderConfirmation(
        "test@example.com", OrderResult.newBuilder().setOrderId("o-1").build());

    TestHttpServer.Request request = server.requests().get(0);
    assertThat(request.body()).isEqualTo("{\"email\":\"test@example.com\",\"order\":{\"order_id\":\"o-1\"}}");
  }

  @Test
  void emailRejectsNon200() {
    server.on("/send_order_confirmation", request -> new Response(500, ""));

    assertThatThrownBy(() -> email.sendOrderConfirmation("test@example.com", OrderResult.getDefaultInstance()))
        .isInstanceOf(StepFailure.class)
        .hasMessage("failed POST to email service: expected 200, got 500");
  }

  /** Port of TestSendOrderConfirmationHasBoundedTimeout: the email request is bounded to 1s. */
  @Test
  void sendOrderConfirmationHasBoundedTimeout() {
    assertThat(EmailClient.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(1));
    server.on(
        "/send_order_confirmation",
        request -> {
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new Response(200, "");
        });

    long start = System.nanoTime();
    assertThatThrownBy(() -> email.sendOrderConfirmation("test@example.com", OrderResult.getDefaultInstance()))
        .isInstanceOf(StepFailure.class)
        .hasMessageStartingWith("failed POST to email service: ");
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(elapsed).isBetween(Duration.ofMillis(800), Duration.ofSeconds(3));
  }

  @Test
  void sendOrderConfirmationSucceedsQuicklyWithEmptyBody() {
    server.on("/send_order_confirmation", request -> new Response(200, ""));

    // mirrors the Go test: an email service answering 200 with an empty body is a success
    email.sendOrderConfirmation("test@example.com", OrderResult.getDefaultInstance());

    assertThat(server.requests()).hasSize(1);
  }
}
