/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import oteldemo.Demo.AdRequest;
import oteldemo.Demo.AdResponse;
import oteldemo.Demo.AddItemRequest;
import oteldemo.Demo.Address;
import oteldemo.Demo.Cart;
import oteldemo.Demo.CartItem;
import oteldemo.Demo.CurrencyConversionRequest;
import oteldemo.Demo.EmptyCartRequest;
import oteldemo.Demo.GetCartRequest;
import oteldemo.Demo.GetProductRequest;
import oteldemo.Demo.GetSupportedCurrenciesResponse;
import oteldemo.Demo.ListProductsResponse;
import oteldemo.Demo.ListRecommendationsRequest;
import oteldemo.Demo.ListRecommendationsResponse;
import oteldemo.Demo.OrderItem;
import oteldemo.Demo.OrderResult;
import oteldemo.Demo.PlaceOrderRequest;
import oteldemo.Demo.PlaceOrderResponse;

/** The {@code /api/*} surface: JSON shape, status codes and the calls made to the backends. */
class ApiTests extends AbstractFrontendTest {

  // ---- /api/products ----

  @Test
  void listProductsInUsdIsNotConverted() throws Exception {
    when(productCatalog.listProducts(any()))
        .thenReturn(
            ListProductsResponse.newBuilder()
                .addProducts(
                    product(
                        "OLJCESPC7Z",
                        "Sunglasses",
                        "sunglasses.jpg",
                        money("USD", 19, 990000000),
                        "accessories"))
                .addProducts(
                    product(
                        "66VCHSJNUP",
                        "Telescope",
                        "starsense.jpg",
                        money("USD", 349, 950000000),
                        "telescopes"))
                .build());

    mvc.perform(get("/api/products").param("currencyCode", "USD"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value("OLJCESPC7Z"))
        .andExpect(jsonPath("$[0].picture").value("sunglasses.jpg"))
        .andExpect(jsonPath("$[0].categories[0]").value("accessories"))
        // int64 units are JSON numbers, not strings
        .andExpect(jsonPath("$[0].priceUsd.units").value(19))
        .andExpect(jsonPath("$[0].priceUsd.nanos").value(990000000))
        .andExpect(jsonPath("$[0].priceUsd.currencyCode").value("USD"));
    verify(currency, never()).convert(any());
  }

  @Test
  void listProductsWithoutCurrencyCodeIsNotConverted() throws Exception {
    when(productCatalog.listProducts(any()))
        .thenReturn(
            ListProductsResponse.newBuilder()
                .addProducts(product("A", "A", "a.jpg", money("USD", 1, 0)))
                .build());

    mvc.perform(get("/api/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].priceUsd.currencyCode").value("USD"));
    verify(currency, never()).convert(any());
  }

  @Test
  void listProductsInOtherCurrencyConvertsEveryPrice() throws Exception {
    when(productCatalog.listProducts(any()))
        .thenReturn(
            ListProductsResponse.newBuilder()
                .addProducts(product("A", "A", "a.jpg", money("USD", 10, 0)))
                .addProducts(product("B", "B", "b.jpg", money("USD", 20, 0)))
                .build());
    when(currency.convert(any()))
        .thenAnswer(
            invocation -> {
              CurrencyConversionRequest request = invocation.getArgument(0);
              return money(request.getToCode(), request.getFrom().getUnits() * 2, 0);
            });

    mvc.perform(get("/api/products").param("currencyCode", "EUR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].priceUsd.currencyCode").value("EUR"))
        .andExpect(jsonPath("$[0].priceUsd.units").value(20))
        .andExpect(jsonPath("$[1].priceUsd.units").value(40));
  }

  @Test
  void getProductReturnsTheConvertedProduct() throws Exception {
    when(productCatalog.getProduct(any()))
        .thenReturn(
            product(
                "L9ECAV7KIM",
                "Lens Cleaning Kit",
                "lenscleaning.jpg",
                money("USD", 21, 950000000),
                "accessories"));
    when(currency.convert(any())).thenReturn(money("CAD", 28, 100000000));

    mvc.perform(get("/api/products/L9ECAV7KIM").param("currencyCode", "CAD"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("L9ECAV7KIM"))
        .andExpect(jsonPath("$.name").value("Lens Cleaning Kit"))
        .andExpect(jsonPath("$.description").value("Lens Cleaning Kit description"))
        .andExpect(jsonPath("$.priceUsd.currencyCode").value("CAD"))
        .andExpect(jsonPath("$.priceUsd.units").value(28));

    ArgumentCaptor<GetProductRequest> request = ArgumentCaptor.forClass(GetProductRequest.class);
    verify(productCatalog).getProduct(request.capture());
    assertThat(request.getValue().getId()).isEqualTo("L9ECAV7KIM");
  }

  @Test
  void productWithoutPriceOmitsPriceUsd() throws Exception {
    when(productCatalog.getProduct(any())).thenReturn(product("A", "A", "a.jpg", null));

    mvc.perform(get("/api/products/A"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("A"))
        .andExpect(jsonPath("$.priceUsd").doesNotExist());
  }

  @Test
  void productLookupFailureIs500WithNextStyleBody() throws Exception {
    when(productCatalog.getProduct(any()))
        .thenThrow(new StatusRuntimeException(Status.NOT_FOUND.withDescription("product not found")));

    mvc.perform(get("/api/products/missing"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string("Internal Server Error"));
  }

  @Test
  void productRoutesRejectOtherMethodsWithEmpty405() throws Exception {
    mvc.perform(post("/api/products"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().string(""));
    mvc.perform(put("/api/products/A"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().string(""));
  }

  // ---- /api/cart ----

  @Test
  void getCartAttachesTheProductToEveryItem() throws Exception {
    when(cart.getCart(any()))
        .thenReturn(
            Cart.newBuilder()
                .setUserId("user-1")
                .addItems(CartItem.newBuilder().setProductId("A").setQuantity(2))
                .addItems(CartItem.newBuilder().setProductId("B").setQuantity(1))
                .build());
    when(productCatalog.getProduct(any()))
        .thenAnswer(
            invocation -> {
              GetProductRequest request = invocation.getArgument(0);
              return product(
                  request.getId(),
                  "Name " + request.getId(),
                  request.getId() + ".jpg",
                  money("USD", 5, 0),
                  "cat");
            });

    mvc.perform(get("/api/cart").param("sessionId", "user-1").param("currencyCode", "USD"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value("user-1"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].productId").value("A"))
        .andExpect(jsonPath("$.items[0].quantity").value(2))
        .andExpect(jsonPath("$.items[0].product.name").value("Name A"))
        .andExpect(jsonPath("$.items[1].product.id").value("B"));

    ArgumentCaptor<GetCartRequest> request = ArgumentCaptor.forClass(GetCartRequest.class);
    verify(cart).getCart(request.capture());
    assertThat(request.getValue().getUserId()).isEqualTo("user-1");
  }

  @Test
  void getCartWithoutSessionIdUsesTheEmptyUserId() throws Exception {
    when(cart.getCart(any())).thenReturn(Cart.getDefaultInstance());

    mvc.perform(get("/api/cart"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(""))
        .andExpect(jsonPath("$.items.length()").value(0));

    ArgumentCaptor<GetCartRequest> request = ArgumentCaptor.forClass(GetCartRequest.class);
    verify(cart).getCart(request.capture());
    assertThat(request.getValue().getUserId()).isEmpty();
  }

  @Test
  void addToCartCallsAddItemThenReturnsTheCart() throws Exception {
    when(cart.getCart(any()))
        .thenReturn(
            Cart.newBuilder()
                .setUserId("user-1")
                .addItems(CartItem.newBuilder().setProductId("A").setQuantity(3))
                .build());

    mvc.perform(
            post("/api/cart")
                .param("currencyCode", "USD")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"user-1\",\"item\":{\"productId\":\"A\",\"quantity\":3}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value("user-1"))
        .andExpect(jsonPath("$.items[0].productId").value("A"))
        .andExpect(jsonPath("$.items[0].quantity").value(3));

    ArgumentCaptor<AddItemRequest> request = ArgumentCaptor.forClass(AddItemRequest.class);
    verify(cart).addItem(request.capture());
    assertThat(request.getValue().getUserId()).isEqualTo("user-1");
    assertThat(request.getValue().getItem().getProductId()).isEqualTo("A");
    assertThat(request.getValue().getItem().getQuantity()).isEqualTo(3);
  }

  @Test
  void addToCartAcceptsNegativeDeltaFromTheQuantitySelector() throws Exception {
    when(cart.getCart(any())).thenReturn(Cart.getDefaultInstance());

    mvc.perform(
            post("/api/cart")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"u\",\"item\":{\"productId\":\"A\",\"quantity\":-2}}"))
        .andExpect(status().isOk());

    ArgumentCaptor<AddItemRequest> request = ArgumentCaptor.forClass(AddItemRequest.class);
    verify(cart).addItem(request.capture());
    assertThat(request.getValue().getItem().getQuantity()).isEqualTo(-2);
  }

  @Test
  void addToCartWithMalformedJsonIs400() throws Exception {
    mvc.perform(post("/api/cart").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string("Invalid JSON"));
    verify(cart, never()).addItem(any());
  }

  @Test
  void emptyCartIs204() throws Exception {
    mvc.perform(
            delete("/api/cart")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"user-9\"}"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    ArgumentCaptor<EmptyCartRequest> request = ArgumentCaptor.forClass(EmptyCartRequest.class);
    verify(cart).emptyCart(request.capture());
    assertThat(request.getValue().getUserId()).isEqualTo("user-9");
  }

  @Test
  void cartRejectsOtherMethodsWithEmpty405() throws Exception {
    mvc.perform(put("/api/cart"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().string(""));
  }

  @Test
  void cartBackendFailureIs500() throws Exception {
    when(cart.getCart(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    mvc.perform(get("/api/cart").param("sessionId", "u"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string("Internal Server Error"));
  }

  // ---- /api/currency ----

  @Test
  void currencyListsTheSupportedCodes() throws Exception {
    when(currency.getSupportedCurrencies(any()))
        .thenReturn(
            GetSupportedCurrenciesResponse.newBuilder()
                .addCurrencyCodes("USD")
                .addCurrencyCodes("EUR")
                .build());

    mvc.perform(get("/api/currency"))
        .andExpect(status().isOk())
        .andExpect(content().json("[\"USD\",\"EUR\"]", true));
  }

  @Test
  void currencyRejectsPostWith405() throws Exception {
    mvc.perform(post("/api/currency"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().string(""));
  }

  // ---- /api/data (ads) ----

  private AdRequest captureAdRequest() {
    ArgumentCaptor<AdRequest> request = ArgumentCaptor.forClass(AdRequest.class);
    verify(ad).getAds(request.capture());
    return request.getValue();
  }

  @Test
  void dataSplitsASingleCommaSeparatedValue() throws Exception {
    when(ad.getAds(any()))
        .thenReturn(
            AdResponse.newBuilder()
                .addAds(oteldemo.Demo.Ad.newBuilder().setRedirectUrl("/product/X").setText("Buy X"))
                .build());

    mvc.perform(get("/api/data").param("contextKeys", "binoculars,telescopes"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].redirectUrl").value("/product/X"))
        .andExpect(jsonPath("$[0].text").value("Buy X"));
    assertThat(captureAdRequest().getContextKeysList()).containsExactly("binoculars", "telescopes");
  }

  @Test
  void dataKeepsRepeatedParametersAsGiven() throws Exception {
    when(ad.getAds(any())).thenReturn(AdResponse.getDefaultInstance());

    mvc.perform(get("/api/data").param("contextKeys", "a", "b,c"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    assertThat(captureAdRequest().getContextKeysList()).containsExactly("a", "b,c");
  }

  @Test
  void dataWithoutContextKeysSendsNone() throws Exception {
    when(ad.getAds(any())).thenReturn(AdResponse.getDefaultInstance());

    mvc.perform(get("/api/data")).andExpect(status().isOk());
    assertThat(captureAdRequest().getContextKeysList()).isEmpty();
  }

  @Test
  void dataWithEmptyContextKeysSendsOneEmptyKeyLikeNext() throws Exception {
    when(ad.getAds(any())).thenReturn(AdResponse.getDefaultInstance());

    mvc.perform(get("/api/data").param("contextKeys", "")).andExpect(status().isOk());
    assertThat(captureAdRequest().getContextKeysList()).containsExactly("");
  }

  @Test
  void trailingSlashRedirectsLikeNext() throws Exception {
    mvc.perform(get("/api/data/?contextKeys=a"))
        .andExpect(status().isPermanentRedirect())
        .andExpect(header().string("Location", "/api/data?contextKeys=a"));
    mvc.perform(get("/cart/"))
        .andExpect(status().isPermanentRedirect())
        .andExpect(header().string("Location", "/cart"));
  }

  // ---- /api/recommendations ----

  @Test
  void recommendationsAreLimitedToFourAndPriced() throws Exception {
    when(recommendation.listRecommendations(any()))
        .thenReturn(
            ListRecommendationsResponse.newBuilder()
                .addAllProductIds(List.of("P1", "P2", "P3", "P4", "P5"))
                .build());
    when(productCatalog.getProduct(any()))
        .thenAnswer(
            invocation -> {
              GetProductRequest request = invocation.getArgument(0);
              return product(
                  request.getId(), request.getId(), request.getId() + ".jpg", money("USD", 1, 0));
            });

    mvc.perform(
            get("/api/recommendations")
                .param("productIds", "A,B")
                .param("sessionId", "sess-7")
                .param("currencyCode", "USD"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(4))
        .andExpect(jsonPath("$[0].id").value("P1"))
        .andExpect(jsonPath("$[3].id").value("P4"));

    ArgumentCaptor<ListRecommendationsRequest> request =
        ArgumentCaptor.forClass(ListRecommendationsRequest.class);
    verify(recommendation).listRecommendations(request.capture());
    assertThat(request.getValue().getUserId()).isEqualTo("sess-7");
    assertThat(request.getValue().getProductIdsList()).containsExactly("A", "B");
  }

  @Test
  void recommendationsWithEmptyProductIdsSendNoIds() throws Exception {
    when(recommendation.listRecommendations(any()))
        .thenReturn(ListRecommendationsResponse.getDefaultInstance());

    mvc.perform(get("/api/recommendations").param("productIds", "").param("sessionId", "s"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));

    ArgumentCaptor<ListRecommendationsRequest> request =
        ArgumentCaptor.forClass(ListRecommendationsRequest.class);
    verify(recommendation).listRecommendations(request.capture());
    assertThat(request.getValue().getProductIdsList()).isEmpty();
  }

  @Test
  void recommendationsAcceptRepeatedProductIds() throws Exception {
    when(recommendation.listRecommendations(any()))
        .thenReturn(ListRecommendationsResponse.getDefaultInstance());

    mvc.perform(get("/api/recommendations").param("productIds", "A", "B"))
        .andExpect(status().isOk());

    ArgumentCaptor<ListRecommendationsRequest> request =
        ArgumentCaptor.forClass(ListRecommendationsRequest.class);
    verify(recommendation).listRecommendations(request.capture());
    assertThat(request.getValue().getProductIdsList()).containsExactly("A", "B");
    assertThat(request.getValue().getUserId()).isEmpty();
  }

  // ---- /api/shipping ----

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void shippingQuotesInUsdThenConvertsToTheRequestedCurrency() throws Exception {
    when(shipping.getShippingCost(any(), any())).thenReturn(money("USD", 8, 990000000));
    when(currency.convert(any())).thenReturn(money("EUR", 7, 500000000));

    mvc.perform(
            get("/api/shipping")
                .param("itemList", "[{\"productId\":\"A\",\"quantity\":2}]")
                .param("currencyCode", "EUR")
                .param(
                    "address",
                    "{\"streetAddress\":\"1 Main\",\"city\":\"Town\",\"state\":\"CA\",\"country\":\"US\",\"zipCode\":\"90210\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currencyCode").value("EUR"))
        .andExpect(jsonPath("$.units").value(7))
        .andExpect(jsonPath("$.nanos").value(500000000));

    ArgumentCaptor<List<Map<String, Object>>> items = ArgumentCaptor.forClass((Class) List.class);
    ArgumentCaptor<Map<String, Object>> address = ArgumentCaptor.forClass((Class) Map.class);
    verify(shipping).getShippingCost(items.capture(), address.capture());
    assertThat(items.getValue()).hasSize(1);
    assertThat(items.getValue().get(0)).containsEntry("productId", "A").containsEntry("quantity", 2);
    assertThat(address.getValue())
        .containsEntry("streetAddress", "1 Main")
        .containsEntry("zipCode", "90210");

    ArgumentCaptor<CurrencyConversionRequest> conversion =
        ArgumentCaptor.forClass(CurrencyConversionRequest.class);
    verify(currency).convert(conversion.capture());
    assertThat(conversion.getValue().getToCode()).isEqualTo("EUR");
    assertThat(conversion.getValue().getFrom().getUnits()).isEqualTo(8);
  }

  @Test
  void shippingDefaultsToUsdAndStillCallsTheCurrencyService() throws Exception {
    when(shipping.getShippingCost(any(), any())).thenReturn(money("USD", 5, 0));
    when(currency.convert(any()))
        .thenAnswer(i -> ((CurrencyConversionRequest) i.getArgument(0)).getFrom());

    mvc.perform(get("/api/shipping").param("itemList", "[]").param("address", "{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currencyCode").value("USD"));

    ArgumentCaptor<CurrencyConversionRequest> conversion =
        ArgumentCaptor.forClass(CurrencyConversionRequest.class);
    verify(currency).convert(conversion.capture());
    assertThat(conversion.getValue().getToCode()).isEqualTo("USD");
  }

  @Test
  void shippingWithoutItemListIs500BecauseJsonParseFails() throws Exception {
    mvc.perform(get("/api/shipping")).andExpect(status().isInternalServerError());
  }

  @Test
  void shippingRejectsPostWith405() throws Exception {
    mvc.perform(post("/api/shipping")).andExpect(status().isMethodNotAllowed());
  }

  // ---- /api/checkout ----

  private static final String ORDER_BODY =
      """
      {"userId":"user-1","email":"someone@example.com","userCurrency":"USD",
       "address":{"streetAddress":"1600 Amphitheatre Parkway","city":"Mountain View","state":"CA","country":"United States","zipCode":"94043"},
       "creditCard":{"creditCardNumber":"4432-8015-6152-0454","creditCardCvv":"672","creditCardExpirationYear":"2030","creditCardExpirationMonth":"1"}}
      """;

  @Test
  void checkoutPlacesTheOrderAndReturnsPricedItems() throws Exception {
    when(checkout.placeOrder(any()))
        .thenReturn(
            PlaceOrderResponse.newBuilder()
                .setOrder(
                    OrderResult.newBuilder()
                        .setOrderId("order-1")
                        .setShippingTrackingId("track-1")
                        .setShippingCost(money("USD", 8, 990000000))
                        .setShippingAddress(
                            Address.newBuilder()
                                .setStreetAddress("1600 Amphitheatre Parkway")
                                .setCity("Mountain View")
                                .setZipCode("94043"))
                        .addItems(
                            OrderItem.newBuilder()
                                .setItem(CartItem.newBuilder().setProductId("A").setQuantity(2))
                                .setCost(money("USD", 10, 0))))
                .build());
    when(productCatalog.getProduct(any()))
        .thenReturn(product("A", "Alpha", "a.jpg", money("USD", 10, 0), "cat"));

    mvc.perform(
            post("/api/checkout")
                .param("currencyCode", "USD")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ORDER_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value("order-1"))
        .andExpect(jsonPath("$.shippingTrackingId").value("track-1"))
        .andExpect(jsonPath("$.shippingCost.units").value(8))
        .andExpect(jsonPath("$.shippingAddress.streetAddress").value("1600 Amphitheatre Parkway"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].cost.units").value(10))
        .andExpect(jsonPath("$.items[0].item.productId").value("A"))
        .andExpect(jsonPath("$.items[0].item.quantity").value(2))
        .andExpect(jsonPath("$.items[0].item.product.name").value("Alpha"));

    ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
    verify(checkout).placeOrder(request.capture());
    PlaceOrderRequest sent = request.getValue();
    assertThat(sent.getUserId()).isEqualTo("user-1");
    assertThat(sent.getUserCurrency()).isEqualTo("USD");
    assertThat(sent.getEmail()).isEqualTo("someone@example.com");
    assertThat(sent.getAddress().getZipCode()).isEqualTo("94043");
    assertThat(sent.getAddress().getCountry()).isEqualTo("United States");
    assertThat(sent.getCreditCard().getCreditCardNumber()).isEqualTo("4432-8015-6152-0454");
    // the browser form posts these numeric fields as strings
    assertThat(sent.getCreditCard().getCreditCardCvv()).isEqualTo(672);
    assertThat(sent.getCreditCard().getCreditCardExpirationYear()).isEqualTo(2030);
    assertThat(sent.getCreditCard().getCreditCardExpirationMonth()).isEqualTo(1);
  }

  @Test
  void checkoutAcceptsNumericCardFieldsLikeTheLoadGenerator() throws Exception {
    when(checkout.placeOrder(any())).thenReturn(PlaceOrderResponse.getDefaultInstance());

    mvc.perform(
            post("/api/checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userId\":\"u\",\"creditCard\":{\"creditCardNumber\":\"1\",\"creditCardCvv\":123,\"creditCardExpirationYear\":2031,\"creditCardExpirationMonth\":12}}"))
        .andExpect(status().isOk())
        // no order in the response: the original answers {items: []}
        .andExpect(content().json("{\"items\":[]}", true));
  }

  @Test
  void paymentDeclineIsA422WithPaymentFailedCode() throws Exception {
    when(checkout.placeOrder(any()))
        .thenThrow(
            new StatusRuntimeException(
                Status.INTERNAL.withDescription(
                    "failed to charge card: could not charge the card: rpc error: code = Unknown desc = Invalid token")));

    mvc.perform(
            post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.error")
                .value(
                    "Your payment could not be processed. Please check your card details and try again."))
        .andExpect(jsonPath("$.code").value("PAYMENT_FAILED"));

    SpanData server =
        spans.getFinishedSpanItems().stream()
            .filter(s -> "/api/checkout".equals(s.getAttributes().get(AttributeKey.stringKey("url.path"))))
            .findFirst()
            .orElseThrow();
    assertThat(server.getEvents()).anyMatch(e -> e.getName().equals("exception"));
  }

  @Test
  void paymentServiceOutageIsNotMisclassifiedAsADecline() throws Exception {
    when(checkout.placeOrder(any()))
        .thenThrow(
            new StatusRuntimeException(
                Status.INTERNAL.withDescription(
                    "failed to charge card: could not charge the card: rpc error: code = Unavailable desc = connection refused")));

    mvc.perform(
            post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("Failed to place order."))
        .andExpect(jsonPath("$.code").doesNotExist());
  }

  @Test
  void otherCheckoutFailuresAreAGeneric500() throws Exception {
    when(checkout.placeOrder(any()))
        .thenThrow(
            new StatusRuntimeException(
                Status.INTERNAL.withDescription("failed to prepare order: product catalog failure")));

    mvc.perform(
            post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("Failed to place order."))
        .andExpect(jsonPath("$.code").doesNotExist());
  }

  @Test
  void checkoutRejectsOtherMethodsWithEmpty405() throws Exception {
    mvc.perform(get("/api/checkout"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().string(""));
  }

  @Test
  void productLookupFailureWhileEnrichingTheOrderIs500() throws Exception {
    when(checkout.placeOrder(any()))
        .thenReturn(
            PlaceOrderResponse.newBuilder()
                .setOrder(
                    OrderResult.newBuilder()
                        .setOrderId("o")
                        .addItems(
                            OrderItem.newBuilder()
                                .setItem(CartItem.newBuilder().setProductId("A").setQuantity(1))))
                .build());
    when(productCatalog.getProduct(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    mvc.perform(
            post("/api/checkout").contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
        .andExpect(status().isInternalServerError())
        .andExpect(content().string("Internal Server Error"));
  }

  // ---- instrumentation (InstrumentationMiddleware.ts) ----

  @Test
  void failedRequestsRecordTheExceptionAndMarkTheSpanErrored() throws Exception {
    when(productCatalog.getProduct(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    mvc.perform(get("/api/products/A")).andExpect(status().isInternalServerError());

    SpanData server = serverSpan("/api/products/A");
    assertThat(server.getStatus().getStatusCode())
        .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    assertThat(server.getEvents()).anyMatch(e -> e.getName().equals("exception"));
    assertThat(server.getAttributes().get(AttributeKey.longKey("http.status_code")))
        .isEqualTo(500L);
  }

  @Test
  void successfulRequestsCarryTheStatusCodeAttribute() throws Exception {
    when(currency.getSupportedCurrencies(any()))
        .thenReturn(GetSupportedCurrenciesResponse.getDefaultInstance());

    mvc.perform(get("/api/currency")).andExpect(status().isOk());

    assertThat(serverSpan("/api/currency").getAttributes().get(AttributeKey.longKey("http.status_code")))
        .isEqualTo(200L);
  }

  private SpanData serverSpan(String route) {
    return spans.getFinishedSpanItems().stream()
        .filter(s -> route.equals(s.getAttributes().get(AttributeKey.stringKey("url.path"))))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no server span for " + route + ": " + spans.getFinishedSpanItems()));
  }
}
