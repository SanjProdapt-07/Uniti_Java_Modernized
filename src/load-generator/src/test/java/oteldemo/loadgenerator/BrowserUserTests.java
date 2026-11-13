/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@SuppressWarnings("unchecked")
class BrowserUserTests {

  private TestTelemetry telemetry;
  private Page page;
  private BrowserSession session;
  private BrowserUser user;

  @BeforeEach
  void setUp() {
    telemetry = new TestTelemetry();
    page = mock(Page.class);
    session = mock(BrowserSession.class);
    when(session.newPage()).thenReturn(page);
    user =
        new BrowserUser(
            telemetry.sdk.getTracer("locustfile"),
            baseUrl -> session,
            () -> "http://frontend-proxy:8080",
            RandomGeneratorFactory.of("L64X128MixRandom").create(3),
            telemetry.stats);
  }

  @AfterEach
  void tearDown() {
    telemetry.close();
  }

  @Test
  void changeCurrencyTaskDrivesCartPage() {
    boolean ok = user.runTask("open_cart_page_and_change_currency", session);

    assertThat(ok).isTrue();
    InOrder order = inOrder(page);
    order.verify(page).route(eq("**/*"), any());
    ArgumentCaptor<Page.NavigateOptions> options = ArgumentCaptor.forClass(Page.NavigateOptions.class);
    order.verify(page).navigate(eq("/cart"), options.capture());
    assertThat(options.getValue().waitUntil).isEqualTo(WaitUntilState.DOMCONTENTLOADED);
    order.verify(page).selectOption("[name=\"currency_code\"]", "CHF");
    order.verify(page).waitForTimeout(2000);
    verify(page).close();
    SpanData span = telemetry.span("browser_change_currency");
    assertThat(span.getParentSpanContext().isValid()).isFalse();
  }

  @Test
  void changeCurrencyTaskSwallowsErrorsLikeTheOriginal() {
    when(page.navigate(eq("/cart"), any())).thenThrow(new PlaywrightException("net::ERR_CONNECTION_REFUSED"));

    boolean ok = user.runTask("open_cart_page_and_change_currency", session);

    assertThat(ok).isFalse();
    verify(page, never()).selectOption(any(String.class), any(String.class));
    assertThat(telemetry.stats.total().numFailures()).isEqualTo(1);
  }

  @Test
  void addToCartTaskWaitsForRoofBinocularsImageThenClicksThrough() {
    when(page.waitForResponse(any(Predicate.class), any(Page.WaitForResponseOptions.class), any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(2)).run();
              return mock(Response.class);
            });

    boolean ok = user.runTask("add_product_to_cart", session);

    assertThat(ok).isTrue();
    ArgumentCaptor<Page.WaitForResponseOptions> options = ArgumentCaptor.forClass(Page.WaitForResponseOptions.class);
    ArgumentCaptor<Predicate<Response>> predicate = ArgumentCaptor.forClass(Predicate.class);
    InOrder order = inOrder(page);
    order.verify(page).waitForResponse(predicate.capture(), options.capture(), any(Runnable.class));
    assertThat(options.getValue().timeout).isEqualTo(15000.0);
    order.verify(page).click("p:has-text(\"Roof Binoculars\")");
    order.verify(page).waitForLoadState(LoadState.DOMCONTENTLOADED);
    order.verify(page).click("button:has-text(\"Add To Cart\")");
    order.verify(page).waitForLoadState(LoadState.DOMCONTENTLOADED);
    order.verify(page).waitForTimeout(2000);
    verify(page).navigate(eq("/"), any(Page.NavigateOptions.class));

    Response good = mock(Response.class);
    when(good.url()).thenReturn("http://x/images/products/RoofBinoculars.jpg");
    when(good.status()).thenReturn(200);
    Response bad = mock(Response.class);
    when(bad.url()).thenReturn("http://x/images/products/RoofBinoculars.jpg");
    when(bad.status()).thenReturn(404);
    Response other = mock(Response.class);
    when(other.url()).thenReturn("http://x/images/products/SolarFilter.jpg");
    when(other.status()).thenReturn(200);
    assertThat(predicate.getValue().test(good)).isTrue();
    assertThat(predicate.getValue().test(bad)).isFalse();
    assertThat(predicate.getValue().test(other)).isFalse();
    assertThat(telemetry.span("browser_add_to_cart").getParentSpanContext().isValid()).isFalse();
  }

  @Test
  void addToCartTaskSwallowsTimeoutLikeTheOriginal() {
    when(page.waitForResponse(any(Predicate.class), any(Page.WaitForResponseOptions.class), any(Runnable.class)))
        .thenThrow(new PlaywrightException("Timeout 15000ms exceeded"));

    assertThat(user.runTask("add_product_to_cart", session)).isFalse();
    verify(page, never()).click(any(String.class));
  }

  @Test
  void routeHandlerAppendsSyntheticRequestBaggage() {
    Request request = mock(Request.class);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("accept", "text/html");
    headers.put("baggage", "session.id=abc");
    when(request.headers()).thenReturn(headers);
    Route route = mock(Route.class);
    when(route.request()).thenReturn(request);

    BrowserUser.addBaggageHeader(route);

    ArgumentCaptor<Route.ResumeOptions> captor = ArgumentCaptor.forClass(Route.ResumeOptions.class);
    verify(route).resume(captor.capture());
    assertThat(captor.getValue().headers)
        .containsEntry("accept", "text/html")
        .containsEntry("baggage", "session.id=abc, synthetic_request=true");
  }

  @Test
  void routeHandlerSetsBaggageWhenAbsent() {
    Request request = mock(Request.class);
    when(request.headers()).thenReturn(Map.of("accept", "*/*"));
    assertThat(BrowserUser.withBaggage(request)).containsEntry("baggage", "synthetic_request=true");
  }

  @Test
  void runLoopOpensBrowserOnceAndClosesItOnInterrupt() throws Exception {
    AtomicReference<String> baseUrl = new AtomicReference<>();
    BrowserSessionFactory factory =
        url -> {
          baseUrl.set(url);
          return session;
        };
    // make tasks cheap and stop after a few pages
    when(session.newPage())
        .thenAnswer(
            invocation -> {
              Thread.currentThread().interrupt();
              return page;
            });
    BrowserUser looping =
        new BrowserUser(
            telemetry.sdk.getTracer("locustfile"),
            factory,
            (Supplier<String>) () -> "http://frontend-proxy:8080",
            RandomGeneratorFactory.of("L64X128MixRandom").create(3),
            telemetry.stats);

    Thread thread = Thread.ofVirtual().start(looping::run);
    thread.join(10_000);

    assertThat(thread.isAlive()).isFalse();
    assertThat(baseUrl.get()).isEqualTo("http://frontend-proxy:8080");
    verify(session).close();
  }

  @Test
  void userEndsQuietlyWhenBrowserCannotStart() {
    BrowserUser broken =
        new BrowserUser(
            telemetry.sdk.getTracer("locustfile"),
            url -> {
              throw new PlaywrightException("Executable doesn't exist");
            },
            () -> "http://x",
            RandomGeneratorFactory.of("L64X128MixRandom").create(3),
            telemetry.stats);

    broken.run(); // returns instead of throwing

    assertThat(telemetry.stats.total().numRequests()).isZero();
  }
}
