/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Server-rendered pages: structure, DOM hooks, window.ENV bootstrap, error pages and static assets. */
class PageTests extends AbstractFrontendTest {

  private String html(String url) throws Exception {
    return mvc.perform(get(url))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void homePageRendersTheShell() throws Exception {
    String page = html("/");
    assertThat(page)
        .contains("<title>Otel Demo - Home</title>")
        .contains("data-cy=\"home-page\"")
        .contains("data-cy=\"hot-products\"")
        .contains("id=\"hot-products\"")
        .contains("data-cy=\"product-list\"")
        .contains("data-cy=\"currency-switcher\"")
        .contains("name=\"currency_code\"")
        .contains("data-cy=\"cart-icon\"")
        .contains("data-cy=\"session-id\"")
        .contains("The best telescopes to see the world closer")
        .contains("This website is hosted for demo purpose only. It is not an actual shop.")
        .contains("OpenTelemetry (<a href=\"https://github.com/open-telemetry/opentelemetry-demo\">Source Code</a>)")
        .contains("@ " + Year.now().getValue())
        .contains("id=\"__next\"")
        .contains("data-page=\"home\"")
        .contains("/assets/css/frontend.css")
        .contains("/assets/js/app.js")
        .contains("/assets/js/vendor/frontend-otel.js")
        .contains("fonts.googleapis.com/css2?family=Open+Sans");
  }

  @Test
  void windowEnvCarriesThePlatformServiceNameAndPublicTracesEndpoint() throws Exception {
    String page = html("/");
    assertThat(page)
        .contains("window.ENV")
        .contains("\"NEXT_PUBLIC_PLATFORM\"")
        .contains("\"NEXT_PUBLIC_OTEL_SERVICE_NAME\":\"frontend-web\"")
        .contains("http:\\/\\/localhost:8080\\/otlp-http\\/v1\\/traces")
        .contains("\"IS_SYNTHETIC_REQUEST\":\"false\"");
  }

  @Test
  void syntheticRequestsSendBrowserTracesToTheCollectorDirectly() throws Exception {
    String page =
        mvc.perform(get("/").header("baggage", "synthetic_request=true"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(page)
        .contains("http:\\/\\/otel-collector-test:4318\\/v1\\/traces")
        .contains("\"IS_SYNTHETIC_REQUEST\":\"true\"");
  }

  @Test
  void productPageRendersTheShellWithProductDetailAndAd() throws Exception {
    String page = html("/product/OLJCESPC7Z");
    assertThat(page)
        .contains("<title>Otel Demo - Product</title>")
        .contains("data-cy=\"product-detail\"")
        .contains("data-cy=\"ad\"")
        .contains("data-page=\"product\"");
  }

  @Test
  void cartPageRendersTheShell() throws Exception {
    assertThat(html("/cart"))
        .contains("<title>Otel Demo - Cart</title>")
        .contains("data-page=\"cart\"")
        .contains("id=\"cart\"");
  }

  @Test
  void checkoutPageRendersTheShell() throws Exception {
    assertThat(html("/cart/checkout/some-order-id?order=%7B%7D"))
        .contains("<title>Otel Demo - Checkout</title>")
        .contains("data-page=\"checkout\"")
        .contains("data-cy=\"ad\"");
  }

  @Test
  void the404PageMatchesPages404Tsx() throws Exception {
    String page =
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(page)
        .contains("<title>Otel Demo - Page Not Found</title>")
        .contains("<h1 class=\"error-code\">404</h1>")
        .contains("This page could not be found.")
        .contains("href=\"/\">Go to homepage");
    // like pages/404.tsx the page has no header / footer
    assertThat(page).doesNotContain("data-cy=\"cart-icon\"");
  }

  @Test
  void the500PageMatchesPages500Tsx() throws Exception {
    String page =
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
            .andExpect(status().isInternalServerError())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(page)
        .contains("<title>Otel Demo - Server Error</title>")
        .contains("<h1 class=\"error-code\">500</h1>")
        .contains("A server-side error occurred. Please try again.")
        .contains("Go to homepage");
  }

  @Test
  void staticAssetsAreServedAtTheOriginalPublicPaths() throws Exception {
    for (String path :
        List.of(
            "/favicon.ico",
            "/icons/Cart.svg",
            "/icons/CartIcon.svg",
            "/icons/Check.svg",
            "/icons/Chevron.svg",
            "/assets/css/frontend.css",
            "/assets/js/app.js",
            "/assets/js/session.js",
            "/assets/js/flags.js",
            "/assets/js/currency-symbols.js",
            "/assets/js/vendor/frontend-otel.js")) {
      mvc.perform(get(path)).andExpect(status().isOk());
    }
  }

  /** Every data-cy hook the Cypress specs use must still exist in the templates or the page script. */
  @Test
  void everyCypressHookExistsInTheMarkup() throws IOException {
    String enumSource = Files.readString(Path.of("utils/enums/CypressFields.ts"));
    Matcher matcher = Pattern.compile("=\\s*'([a-z-]+)'").matcher(enumSource);
    StringBuilder markup = new StringBuilder();
    try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        markup.append(Files.readString(file));
      }
    }
    markup.append(Files.readString(Path.of("src/main/resources/static/assets/js/app.js")));

    int hooks = 0;
    while (matcher.find()) {
      hooks++;
      String hook = matcher.group(1);
      // cart-dropdown-item-quantity is declared in the enum but was never rendered by the original either
      if (hook.equals("cart-dropdown-item-quantity")) {
        continue;
      }
      assertThat(markup.toString()).as("data-cy=%s", hook).containsPattern("(data-cy=\"|dataCy: ')" + hook + "[\"']");
    }
    assertThat(hooks).isEqualTo(23);
  }

  @Test
  void checkoutFormKeepsTheOriginalFieldNamesAndIds() throws IOException {
    String app = Files.readString(Path.of("src/main/resources/static/assets/js/app.js"));
    for (String fragment :
        List.of(
            "name: 'email'",
            "id: 'street_address', name: 'streetAddress'",
            "id: 'zip_code', name: 'zipCode'",
            "id: 'city', name: 'city'",
            "id: 'state', name: 'state'",
            "id: 'country', name: 'country'",
            "id: 'credit_card_number'",
            "name: 'creditCardNumber'",
            "id: 'credit_card_expiration_month', name: 'creditCardExpirationMonth'",
            "id: 'credit_card_expiration_year', name: 'creditCardExpirationYear'",
            "id: 'credit_card_cvv', name: 'creditCardCvv'")) {
      assertThat(app).contains(fragment);
    }
  }
}
