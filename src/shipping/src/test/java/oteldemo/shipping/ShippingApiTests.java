/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The HTTP contract of the Rust service (routes, JSON shapes, status codes, error behaviour),
 * including the five {@code ship_order} tests of the original shipping_service.rs.
 */
@SpringBootTest(
    properties = {
      "otel.traces.exporter=none",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none",
      "quote.addr=http://quote.test:8090"
    })
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
class ShippingApiTests {

  private static final Pattern UUID_V4 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

  @Autowired private MockMvc mvc;
  @Autowired private MockRestServiceServer quote;
  @MockitoBean private Client featureFlags;

  @BeforeEach
  void setUp() {
    quote.reset();
    flag(0);
  }

  // ---- /get-quote -------------------------------------------------------------------------

  @Test
  void getQuoteReturnsTheCostAsMoney() throws Exception {
    quote
        .expect(requestTo("http://quote.test:8090/getquote"))
        .andExpect(MockRestRequestMatchers.content().string("{\"numberOfItems\":5}"))
        .andRespond(withSuccess("10.99", MediaType.TEXT_PLAIN));

    MvcResult result =
        mvc.perform(
                post("/get-quote")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"items":[{"product_id":"A","quantity":2},{"productId":"B","quantity":3}],
                         "address":{"streetAddress":"1 Main","city":"X","state":"CA","country":"US","zipCode":"1"}}"""))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .isEqualTo("{\"cost_usd\":{\"currency_code\":\"USD\",\"units\":10,\"nanos\":990000000}}");
    quote.verify();
  }

  @Test
  void getQuoteAcceptsARequestWithoutAddressAndWithNoItems() throws Exception {
    quote
        .expect(requestTo("http://quote.test:8090/getquote"))
        .andExpect(MockRestRequestMatchers.content().string("{\"numberOfItems\":0}"))
        .andRespond(withSuccess("0.05", MediaType.TEXT_PLAIN));

    mvc.perform(post("/get-quote").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cost_usd.units").value(0))
        .andExpect(jsonPath("$.cost_usd.nanos").value(50000000));
  }

  @Test
  void getQuoteSumsTheQuantitiesAsAnUnsigned32BitNumber() throws Exception {
    quote
        .expect(requestTo("http://quote.test:8090/getquote"))
        .andExpect(MockRestRequestMatchers.content().string("{\"numberOfItems\":1}"))
        .andRespond(withSuccess("1", MediaType.TEXT_PLAIN));

    // 4294967295 + 2 wraps to 1 like the release build of the Rust service
    mvc.perform(
            post("/get-quote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"items\":[{\"product_id\":\"A\",\"quantity\":4294967295},"
                        + "{\"product_id\":\"B\",\"quantity\":2}]}"))
        .andExpect(status().isOk());
  }

  @Test
  void getQuoteReportsAnUnreachableQuoteServiceAs500() throws Exception {
    quote
        .expect(requestTo("http://quote.test:8090/getquote"))
        .andRespond(
            request -> {
              throw new java.io.IOException("connection refused");
            });

    MvcResult result =
        mvc.perform(
                post("/get-quote").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
            .andExpect(status().isInternalServerError())
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .startsWith("Failed to get quote: status: Unknown, message: \"Failed to call quote service: ")
        .endsWith("\", details: [], metadata: MetadataMap { headers: {} }");
  }

  @Test
  void getQuoteReportsAnUnparsableQuoteAs500() throws Exception {
    quote
        .expect(requestTo("http://quote.test:8090/getquote"))
        .andRespond(withServerError().body("oops"));

    mvc.perform(post("/get-quote").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
        .andExpect(status().isInternalServerError())
        .andExpect(
            content()
                .string(
                    "Failed to get quote: status: Unknown, message: \"Failed to parse quote value as f64\", "
                        + "details: [], metadata: MetadataMap { headers: {} }"));
  }

  @Test
  void invalidRequestBodiesAreRejectedWith400() throws Exception {
    for (String body :
        new String[] {
          "",
          "not json",
          "{}",
          "{\"items\":null}",
          "{\"items\":[{\"product_id\":\"A\"}]}",
          "{\"items\":[{\"quantity\":1}]}",
          "{\"items\":[{\"product_id\":\"A\",\"quantity\":-1}]}",
          "{\"items\":[{\"product_id\":\"A\",\"quantity\":1.5}]}",
          "{\"items\":[{\"product_id\":\"A\",\"quantity\":\"1\"}]}",
          "{\"items\":[{\"product_id\":\"A\",\"quantity\":4294967296}]}",
          "{\"items\":[],\"address\":{\"city\":\"X\"}}"
        }) {
      mvc.perform(post("/get-quote").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
    quote.verify(); // the quote service was never called
  }

  @Test
  void aBodyThatIsNotDeclaredAsJsonIs415() throws Exception {
    mvc.perform(post("/get-quote").contentType(MediaType.TEXT_PLAIN).content("{\"items\":[]}"))
        .andExpect(status().isUnsupportedMediaType());
    mvc.perform(post("/get-quote").content("{\"items\":[]}")).andExpect(status().isUnsupportedMediaType());
  }

  // ---- /ship-order ------------------------------------------------------------------------

  @Test
  void shipOrderWithoutAddress() throws Exception {
    assertTrackingIdReturned("{\"items\":[]}");
    verify(featureFlags, never()).getIntegerDetails(eq("intlShippingSlowdown"), anyInt());
  }

  @Test
  void shipOrderToTheUs() throws Exception {
    assertTrackingIdReturned(order("US"));
    verify(featureFlags, never()).getIntegerDetails(eq("intlShippingSlowdown"), anyInt());
  }

  @Test
  void shipOrderInternationalFlagOff() throws Exception {
    long start = System.nanoTime();
    assertTrackingIdReturned(order("FR"));
    assertThat(elapsedMillis(start)).isLessThan(1000);
    verify(featureFlags).getIntegerDetails("intlShippingSlowdown", 0);
  }

  @Test
  void shipOrderInternationalFlagOnDelaysByTheFlagValue() throws Exception {
    flag(1);
    long start = System.nanoTime();
    assertTrackingIdReturned(order("FR"));
    assertThat(elapsedMillis(start)).isGreaterThanOrEqualTo(1000);
  }

  @Test
  void shipOrderToTheUsIsNeverDelayed() throws Exception {
    flag(10);
    long start = System.nanoTime();
    assertTrackingIdReturned(order("US"));
    assertThat(elapsedMillis(start)).isLessThan(1000);
  }

  @Test
  void usCountryNamesAreMatchedCaseInsensitivelyAndTrimmed() throws Exception {
    flag(10);
    for (String country : new String[] {"us", " USA ", "United States", "united states of america"}) {
      long start = System.nanoTime();
      assertTrackingIdReturned(order(country));
      assertThat(elapsedMillis(start)).as(country).isLessThan(1000);
    }
    verify(featureFlags, never()).getIntegerDetails(eq("intlShippingSlowdown"), anyInt());
  }

  @Test
  void aFailingFlagEvaluationMeansNoDelay() throws Exception {
    when(featureFlags.getIntegerDetails("intlShippingSlowdown", 0))
        .thenReturn(
            FlagEvaluationDetails.<Integer>builder()
                .value(0)
                .errorCode(ErrorCode.PROVIDER_NOT_READY)
                .errorMessage("flagd unavailable")
                .build());
    long start = System.nanoTime();
    assertTrackingIdReturned(order("FR"));
    assertThat(elapsedMillis(start)).isLessThan(1000);
  }

  @Test
  void shipOrderAcceptsCamelCaseAddressAndItemFields() throws Exception {
    assertTrackingIdReturned(
        "{\"address\":{\"streetAddress\":\"1\",\"city\":\"c\",\"state\":\"s\",\"country\":\"US\",\"zipCode\":\"z\"},"
            + "\"items\":[{\"productId\":\"A\",\"quantity\":1}]}");
  }

  @Test
  void shipOrderRequiresItems() throws Exception {
    mvc.perform(post("/ship-order").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ---- other routes ---------------------------------------------------------------------------

  @Test
  void healthIsAnEmpty200() throws Exception {
    MvcResult result = mvc.perform(get("/health")).andExpect(status().isOk()).andReturn();
    assertThat(result.getResponse().getContentAsString()).isEmpty();
  }

  @Test
  void unknownRoutesAreAnEmpty404AndWrongMethodsAnEmpty405() throws Exception {
    MvcResult unknown = mvc.perform(get("/nope")).andExpect(status().isNotFound()).andReturn();
    assertThat(unknown.getResponse().getContentAsString()).isEmpty();
    MvcResult wrongMethod = mvc.perform(get("/get-quote")).andExpect(status().isMethodNotAllowed()).andReturn();
    assertThat(wrongMethod.getResponse().getContentAsString()).isEmpty();
    mvc.perform(post("/health")).andExpect(status().isMethodNotAllowed());
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private void assertTrackingIdReturned(String body) throws Exception {
    mvc.perform(post("/ship-order").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.tracking_id").value(org.hamcrest.Matchers.matchesPattern(UUID_V4)));
  }

  private static String order(String country) {
    return "{\"address\":{\"street_address\":\"123 Main St\",\"city\":\"Anytown\",\"state\":\"CA\","
        + "\"country\":\""
        + country
        + "\",\"zip_code\":\"00000\"},\"items\":[]}";
  }

  private void flag(int seconds) {
    when(featureFlags.getIntegerDetails("intlShippingSlowdown", 0))
        .thenReturn(FlagEvaluationDetails.<Integer>builder().value(seconds).variant("v").build());
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
