/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-browser user ({@code WebsiteBrowserUser} of the locustfile, only used when
 * {@code LOCUST_BROWSER_TRAFFIC_ENABLED} is on): two equally weighted tasks driving headless Chromium against
 * the frontend, with the {@code synthetic_request=true} baggage header added to every browser request.
 */
public class BrowserUser implements SimulatedUser {

  private static final Logger log = LoggerFactory.getLogger(BrowserUser.class);

  static final String BAGGAGE_PAIR = "synthetic_request=true";

  private final Tracer tracer;
  private final BrowserSessionFactory sessions;
  private final Supplier<String> host;
  private final RandomGenerator random;
  private final RequestStats stats;
  private final List<String> taskNames = List.of("open_cart_page_and_change_currency", "add_product_to_cart");

  public BrowserUser(
      Tracer tracer,
      BrowserSessionFactory sessions,
      Supplier<String> host,
      RandomGenerator random,
      RequestStats stats) {
    this.tracer = tracer;
    this.sessions = sessions;
    this.host = host;
    this.random = random;
    this.stats = stats;
  }

  @Override
  public void run() {
    BrowserSession session;
    try {
      session = sessions.open(host.get());
    } catch (RuntimeException e) {
      log.error("Could not start browser for browser user: {}", e.toString());
      return;
    }
    try {
      while (!Thread.currentThread().isInterrupted()) {
        boolean ok = runTask(taskNames.get(random.nextInt(taskNames.size())), session);
        if (!ok) {
          // The original retried immediately; back off briefly so a broken environment does not spin.
          Thread.sleep(1000);
        }
      }
    } catch (InterruptedException | SimulatedUserStopped stopped) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        session.close();
      } catch (RuntimeException e) {
        log.warn("Error closing browser: {}", e.toString());
      }
    }
  }

  /** Runs one task on a fresh page; returns false if the task failed. */
  boolean runTask(String name, BrowserSession session) {
    long start = System.nanoTime();
    Page page = session.newPage();
    boolean ok = false;
    try {
      ok = "open_cart_page_and_change_currency".equals(name) ? openCartPageAndChangeCurrency(page) : addProductToCart(page);
    } finally {
      stats.log("BROWSER", name, (System.nanoTime() - start) / 1_000_000, 0, ok ? null : "browser task failed");
      try {
        page.close();
      } catch (RuntimeException e) {
        log.debug("Error closing page: {}", e.toString());
      }
    }
    return ok;
  }

  boolean openCartPageAndChangeCurrency(Page page) {
    // context=Context(): a brand new root trace without the user baggage
    Span span = tracer.spanBuilder("browser_change_currency").setNoParent().startSpan();
    try (Scope ignored = Context.root().with(span).makeCurrent()) {
      try {
        page.onConsoleMessage(msg -> System.out.println(msg.text()));
        page.route("**/*", BrowserUser::addBaggageHeader);
        page.navigate("/cart", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.selectOption("[name=\"currency_code\"]", "CHF");
        page.waitForTimeout(2000); // giving the browser time to export the traces
        log.info("Currency changed to CHF");
        return true;
      } catch (RuntimeException e) {
        log.error("Error in change currency task: {}", e.getMessage());
        return false;
      }
    } finally {
      span.end();
    }
  }

  boolean addProductToCart(Page page) {
    Span span = tracer.spanBuilder("browser_add_to_cart").setNoParent().startSpan();
    try (Scope ignored = Context.root().with(span).makeCurrent()) {
      try {
        page.onConsoleMessage(msg -> System.out.println(msg.text()));
        page.route("**/*", BrowserUser::addBaggageHeader);
        // Wait for Roof Binoculars image to load (awaiting successful response in less than 15 seconds)
        page.waitForResponse(
            response -> response.url().contains("/images/products/RoofBinoculars.jpg") && response.status() == 200,
            new Page.WaitForResponseOptions().setTimeout(15000),
            () -> page.navigate("/", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)));
        page.click("p:has-text(\"Roof Binoculars\")");
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
        page.click("button:has-text(\"Add To Cart\")");
        page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(2000); // giving the browser time to export the traces
        log.info("Product added to cart successfully");
        return true;
      } catch (RuntimeException e) {
        log.error("Error in add to cart task: {}", e.getMessage());
        return false;
      }
    } finally {
      span.end();
    }
  }

  /** Route handler: continues every browser request with {@code synthetic_request=true} appended to baggage. */
  static void addBaggageHeader(Route route) {
    route.resume(new Route.ResumeOptions().setHeaders(withBaggage(route.request())));
  }

  static Map<String, String> withBaggage(Request request) {
    Map<String, String> headers = new LinkedHashMap<>(request.headers());
    String existing = headers.getOrDefault("baggage", "");
    headers.put("baggage", existing.isEmpty() ? BAGGAGE_PAIR : existing + ", " + BAGGAGE_PAIR);
    return headers;
  }
}
