/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Behaviour of the ten shop tools against a fake frontend (ports of src/shared/tools.py). */
class ShopToolsTests {

  private HttpServer frontend;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile Function<String, int[]> statusFor = path -> new int[] {200};
  private volatile Function<String, String> bodyFor = path -> "{\"ok\":true}";
  private InMemoryMetricReader metrics;
  private ShopTools tools;

  @BeforeEach
  void setUp() throws IOException {
    frontend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    frontend.createContext(
        "/",
        exchange -> {
          String body;
          try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
          String line =
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI()
                  + (body.isEmpty() ? "" : " " + body);
          requests.add(line);
          int status = statusFor.apply(exchange.getRequestURI().toString())[0];
          byte[] bytes =
              bodyFor.apply(exchange.getRequestURI().toString()).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
          } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    frontend.start();
    metrics = InMemoryMetricReader.create();
    OpenTelemetrySdk otel =
        OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build();
    tools =
        new ShopTools(
            RestClient.create(), "127.0.0.1:" + frontend.getAddress().getPort(), otel);
  }

  @AfterEach
  void tearDown() {
    frontend.stop(0);
  }

  private Object call(String name, Map<String, Object> args) {
    return tools.invoke(name, args);
  }

  @Test
  void registersTheTenOriginalToolsInOrder() {
    assertThat(tools.tools().stream().map(ShopTools.Tool::name))
        .containsExactly(
            "add_to_cart",
            "checkout",
            "empty_cart",
            "get_ads",
            "get_cart",
            "get_product",
            "get_recommendations",
            "get_shipping_quote",
            "get_supported_currencies",
            "list_products");
  }

  @Test
  void getAdsQueriesContextKeys() {
    bodyFor = p -> "[{\"redirectUrl\":\"/x\"}]";
    Object result = call("get_ads", Map.of("category", "binoculars"));

    assertThat(requests).containsExactly("GET /api/data?contextKeys=binoculars");
    assertThat(result).isInstanceOf(JsonNode.class);
    assertThat(result.toString()).isEqualTo("[{\"redirectUrl\":\"/x\"}]");
  }

  @Test
  void addToCartUsesDefaultQuantityAndCoercesQuantity() {
    call("add_to_cart", Map.of("user_id", "u", "product_id", "p"));
    call("add_to_cart", Map.of("user_id", "u", "product_id", "p", "quantity", "3"));

    assertThat(requests)
        .containsExactly(
            "POST /api/cart {\"item\":{\"productId\":\"p\",\"quantity\":1},\"userId\":\"u\"}",
            "POST /api/cart {\"item\":{\"productId\":\"p\",\"quantity\":3},\"userId\":\"u\"}");
  }

  @Test
  void getCartAndRecommendationsAndProduct() {
    call("get_cart", Map.of("user_id", "a b&c"));
    call("get_recommendations", Map.of("product_id", "P1"));
    call("get_product", Map.of("product_id", "P1"));
    call("list_products", Map.of());
    call("get_supported_currencies", Map.of());

    assertThat(requests)
        .containsExactly(
            "GET /api/cart?user_id=a%20b%26c",
            "GET /api/recommendations?productIds=P1",
            "GET /api/products/P1",
            "GET /api/products",
            "GET /api/currency");
  }

  @Test
  void emptyCartReturnsSuccessMessageForNoContent() {
    statusFor = p -> new int[] {204};
    Object result = call("empty_cart", Map.of("user_id", "u9"));

    assertThat(requests).containsExactly("DELETE /api/cart {\"userId\":\"u9\"}");
    assertThat(result).isEqualTo(Map.of("status", "success", "message", "Cart emptied for user u9"));
  }

  @Test
  void emptyCartReturnsBodyWhenPresent() {
    bodyFor = p -> "{\"emptied\":1}";
    assertThat(call("empty_cart", Map.of("user_id", "u9")).toString()).isEqualTo("{\"emptied\":1}");
  }

  @Test
  void httpErrorsBecomeErrorStrings() {
    statusFor = p -> new int[] {500};
    bodyFor = p -> "boom";

    assertThat((String) call("list_products", Map.of()))
        .startsWith("Error while fetching product list: Server error '500 Internal Server Error' for url '")
        .endsWith(
            "/api/products'\nFor more information check: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/500");
    assertThat((String) call("get_ads", Map.of("category", "x")))
        .startsWith("Error fetching ads: Server error '500 Internal Server Error'");
    assertThat((String) call("get_cart", Map.of("user_id", "x")))
        .startsWith("Error while fetching cart: ");
    assertThat((String) call("get_product", Map.of("product_id", "x")))
        .startsWith("Error while fetching product x: ");
    assertThat((String) call("get_supported_currencies", Map.of()))
        .startsWith("Error while fetching currency list: ");
    assertThat((String) call("get_recommendations", Map.of("product_id", "x")))
        .startsWith("Error fetching recommendations: ");
    assertThat((String) call("add_to_cart", Map.of("user_id", "u", "product_id", "p")))
        .startsWith("Error while adding product to cart: ");
    assertThat((String) call("empty_cart", Map.of("user_id", "u")))
        .startsWith("Error while emptying cart: ");
  }

  @Test
  void checkoutFailureExplainsThatCartMustNotBeEmpty() {
    statusFor = p -> new int[] {500};
    bodyFor = p -> " cart is empty ";

    Object result = call("checkout", Map.of("checkout_person", Map.of("userId", "u1", "email", "e")));

    assertThat(result)
        .isEqualTo(
            "Checkout failed with HTTP 500 for user u1: cart is empty. Note: the user's cart must"
                + " contain at least one item before calling checkout; call add_to_cart first.");
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0))
        .startsWith("POST /api/checkout {")
        .contains("\"userId\":\"u1\"")
        .contains("\"email\":\"e\"");
  }

  @Test
  void checkoutFailureWithoutUserIdAndEmptyBody() {
    statusFor = p -> new int[] {400};
    bodyFor = p -> "";
    assertThat(call("checkout", Map.of("checkout_person", Map.of())))
        .isEqualTo(
            "Checkout failed with HTTP 400 for user <unknown>: <empty body>. Note: the user's cart"
                + " must contain at least one item before calling checkout; call add_to_cart first.");
  }

  @Test
  void checkoutSuccessReturnsOrder() {
    bodyFor = p -> "{\"orderId\":\"o-1\"}";
    assertThat(call("checkout", Map.of("checkout_person", Map.of("userId", "u1"))).toString())
        .isEqualTo("{\"orderId\":\"o-1\"}");
  }

  @Test
  void shippingQuoteNormalisesItemsAndEncodesPythonJson() {
    bodyFor = p -> "{\"costUsd\":{\"units\":8}}";
    Object result =
        call(
            "get_shipping_quote",
            Map.of(
                "items",
                Map.of("product_id", "P1"),
                "currency_code",
                "USD",
                "address",
                Map.of("city", "Zürich")));

    assertThat(result.toString()).isEqualTo("{\"costUsd\":{\"units\":8}}");
    // itemList=[{"productId": "P1", "quantity": 1}], address={"city": "Zürich"} (json.dumps)
    assertThat(requests)
        .containsExactly(
            "GET /api/shipping?itemList=%5B%7B%22productId%22%3A%20%22P1%22%2C%20%22quantity%22%3A%201%7D%5D"
                + "&currencyCode=USD&address=%7B%22city%22%3A%20%22Z%5Cu00fcrich%22%7D");
  }

  @Test
  void shippingQuoteValidatesItems() {
    assertThat(
            call(
                "get_shipping_quote",
                Map.of("items", List.of("abc"), "currency_code", "USD", "address", Map.of())))
        .isEqualTo("Error fetching shipping quote: invalid item 'abc'");
    assertThat(
            call(
                "get_shipping_quote",
                Map.of(
                    "items",
                    List.of(Map.of("quantity", 2)),
                    "currency_code",
                    "USD",
                    "address",
                    Map.of())))
        .isEqualTo("Error fetching shipping quote: each item must include productId");
    assertThatThrownBy(
            () ->
                call(
                    "get_shipping_quote",
                    Map.of("items", 5, "currency_code", "USD", "address", Map.of())))
        .isInstanceOf(ShopTools.ToolFailure.class)
        .hasMessage("'int' object is not iterable");
    assertThat(requests).isEmpty();
  }

  @Test
  void shippingQuoteHttpErrorIncludesStatusAndBody() {
    statusFor = p -> new int[] {422};
    bodyFor = p -> "bad address";
    assertThat(
            call(
                "get_shipping_quote",
                Map.of(
                    "items",
                    List.of(Map.of("productId", "P1", "quantity", 2)),
                    "currency_code",
                    "USD",
                    "address",
                    Map.of())))
        .isEqualTo("Shipping quote failed with HTTP 422: bad address. ");
  }

  @Test
  void connectionFailuresAreReportedAsText() {
    frontend.stop(0);
    assertThat((String) call("list_products", Map.of()))
        .isEqualTo("Error while fetching product list: All connection attempts failed");
  }

  @Test
  void invalidJsonBodyIsReportedLikeJsonDecodeError() {
    bodyFor = p -> "<html>";
    assertThat((String) call("list_products", Map.of()))
        .isEqualTo(
            "Error while fetching product list: Expecting value: line 1 column 1 (char 0)");
  }

  @Test
  void argumentValidationFollowsPydantic() {
    assertThatThrownBy(() -> call("get_cart", Map.of()))
        .isInstanceOf(ToolArguments.InvalidArguments.class)
        .hasMessageStartingWith("1 validation error for call[get_cart]\nuser_id\n  Missing required argument");
    assertThatThrownBy(() -> call("get_cart", Map.of("user_id", 5)))
        .hasMessageContaining("Input should be a valid string [type=string_type, input_value=5, input_type=int]");
    assertThatThrownBy(
            () -> call("add_to_cart", Map.of("user_id", "u", "product_id", "p", "quantity", "x")))
        .hasMessageContaining("Input should be a valid integer, unable to parse string as an integer");
    assertThat(requests).isEmpty();
  }

  @Test
  void toolCallCounterRecordsToolNameAfterValidation() {
    call("list_products", Map.of());
    call("list_products", Map.of());
    assertThatThrownBy(() -> call("get_cart", Map.of())).isInstanceOf(RuntimeException.class);

    assertThat(metrics.collectAllMetrics())
        .anySatisfy(
            m -> {
              assertThat(m.getName()).isEqualTo("demo.tool.calls");
              assertThat(m.getUnit()).isEqualTo("{call}");
              assertThat(m.getDescription())
                  .isEqualTo("Number of Astronomy Shop tool calls made by the agent and MCP services");
              List<LongPointData> points = List.copyOf(m.getLongSumData().getPoints());
              assertThat(points).hasSize(1);
              assertThat(points.get(0).getValue()).isEqualTo(2);
              assertThat(points.get(0).getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
                  .isEqualTo("list_products");
            });
  }
}
