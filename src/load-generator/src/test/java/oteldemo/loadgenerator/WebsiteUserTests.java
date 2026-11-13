/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class WebsiteUserTests {

  private FakeSite site;
  private FakeSite agent;
  private TestTelemetry telemetry;
  private final Map<String, Integer> flags = new HashMap<>();
  private WebsiteUser user;
  private List<Map<String, Object>> people;

  @BeforeEach
  void setUp() throws IOException {
    site = new FakeSite();
    agent = new FakeSite();
    telemetry = new TestTelemetry();
    people = Catalog.loadPeople(telemetry.mapper);
    user = newUser(seeded(1), (seconds) -> {});
  }

  @AfterEach
  void tearDown() {
    site.close();
    agent.close();
    telemetry.close();
  }

  private static RandomGenerator seeded(long seed) {
    return RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
  }

  private WebsiteUser newUser(RandomGenerator random, WebsiteUser.Sleeper sleeper) {
    ToIntFunction<String> flag = key -> flags.getOrDefault(key, 0);
    return new WebsiteUser(
        telemetry.sdk.getTracer("locustfile"),
        telemetry.session(site.url()),
        flag,
        random,
        people,
        "localhost",
        String.valueOf(agent.port()),
        telemetry.mapper,
        sleeper);
  }

  private List<String> paths() {
    return site.requests().stream().map(FakeSite.Recorded::uri).collect(Collectors.toList());
  }

  private static String attr(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  @Test
  void taskTableMatchesOriginalWeights() {
    Map<String, Integer> weights = new java.util.LinkedHashMap<>();
    user.tasks().forEach(t -> weights.put(t.name(), t.weight()));
    assertThat(weights)
        .containsExactly(
            Map.entry("index", 1),
            Map.entry("browse_product", 10),
            Map.entry("get_recommendations", 3),
            Map.entry("get_ads", 3),
            Map.entry("view_cart", 3),
            Map.entry("add_to_cart", 2),
            Map.entry("checkout", 1),
            Map.entry("checkout_multi", 1),
            Map.entry("flood_home", 5),
            Map.entry("ask_agent", 3));
  }

  @Test
  void taskSelectionFollowsWeights() {
    Map<String, Integer> counts = new HashMap<>();
    int total = 32_000;
    for (int i = 0; i < total; i++) {
      counts.merge(user.pickTask().name(), 1, Integer::sum);
    }
    user.tasks()
        .forEach(
            t -> assertThat(counts.get(t.name()) / (double) total).isCloseTo(t.weight() / 32.0, org.assertj.core.data.Offset.offset(0.01)));
  }

  @Test
  void indexRequestsHomepageInSpan() {
    user.index();

    assertThat(paths()).containsExactly("/");
    assertThat(telemetry.span("user_index")).isNotNull();
  }

  @Test
  void browseProductRequestsProductWithAttribute() {
    user.browseProduct();

    String uri = paths().get(0);
    assertThat(uri).startsWith("/api/products/");
    String product = uri.substring("/api/products/".length());
    assertThat(Catalog.PRODUCTS).contains(product);
    assertThat(attr(telemetry.span("user_browse_product"), "demo.product.id")).isEqualTo(product);
  }

  @Test
  void recommendationsPassProductIdsParam() {
    user.getRecommendations();

    String uri = paths().get(0);
    assertThat(uri).startsWith("/api/recommendations?productIds=");
    String product = uri.substring(uri.indexOf('=') + 1);
    assertThat(Catalog.PRODUCTS).contains(product);
    assertThat(attr(telemetry.span("user_get_recommendations"), "demo.product.id")).isEqualTo(product);
  }

  @Test
  void adsUseContextKeysOrNothingForNoneCategory() {
    Map<String, Integer> seen = new HashMap<>();
    for (int i = 0; i < 300; i++) {
      telemetry.spans.reset();
      site.requests().clear();
      user.getAds();
      String uri = paths().get(0);
      String category = attr(telemetry.span("user_get_ads"), "demo.ad.category");
      seen.merge(category, 1, Integer::sum);
      if (category.equals("None")) {
        assertThat(uri).isEqualTo("/api/data/");
      } else {
        assertThat(uri).isEqualTo("/api/data/?contextKeys=" + category);
      }
    }
    assertThat(seen.keySet()).containsExactlyInAnyOrder("binoculars", "telescopes", "accessories", "assembly", "travel", "books", "None");
  }

  @Test
  void viewCartRequestsCart() {
    user.viewCart();
    assertThat(paths()).containsExactly("/api/cart");
    assertThat(telemetry.span("user_view_cart")).isNotNull();
  }

  @Test
  void addToCartGetsProductThenPostsCartItem() {
    user.addToCart("");

    assertThat(site.requests()).extracting(FakeSite.Recorded::method).containsExactly("GET", "POST");
    FakeSite.Recorded post = site.requests().get(1);
    assertThat(post.uri()).isEqualTo("/api/cart");
    JsonNode body = telemetry.mapper.readTree(post.body());
    String product = body.get("item").get("productId").asString();
    int quantity = body.get("item").get("quantity").asInt();
    assertThat(site.requests().get(0).uri()).isEqualTo("/api/products/" + product);
    assertThat(Catalog.PRODUCTS).contains(product);
    assertThat(Catalog.QUANTITIES).contains(quantity);
    String userId = body.get("userId").asString();
    assertThat(userId).hasSize(36);

    SpanData span = telemetry.span("user_add_to_cart");
    assertThat(attr(span, "user.id")).isEqualTo(userId);
    assertThat(attr(span, "demo.product.id")).isEqualTo(product);
    assertThat(span.getAttributes().get(AttributeKey.longKey("demo.product.quantity"))).isEqualTo((long) quantity);
  }

  @Test
  void addToCartKeepsGivenUser() {
    user.addToCart("fixed-user");
    assertThat(telemetry.mapper.readTree(site.requests().get(1).body()).get("userId").asString()).isEqualTo("fixed-user");
  }

  @Test
  void singleCheckoutAddsToCartThenPostsPersonWithUserId() {
    user.checkout();

    assertThat(paths()).hasSize(3);
    assertThat(site.requests()).extracting(FakeSite.Recorded::path).containsExactly(
        site.requests().get(0).path(), "/api/cart", "/api/checkout");
    JsonNode checkout = telemetry.mapper.readTree(site.requests().get(2).body());
    String userId = checkout.get("userId").asString();
    assertThat(telemetry.mapper.readTree(site.requests().get(1).body()).get("userId").asString()).isEqualTo(userId);
    assertThat(people.stream().map(p -> p.get("email")).toList()).contains(checkout.get("email").asString());
    assertThat(checkout.has("address")).isTrue();
    assertThat(checkout.has("creditCard")).isTrue();
    assertThat(checkout.has("userCurrency")).isTrue();

    SpanData span = telemetry.span("user_checkout_single");
    assertThat(attr(span, "user.id")).isEqualTo(userId);
    // user_add_to_cart is a child of the checkout span
    assertThat(telemetry.span("user_add_to_cart").getParentSpanId()).isEqualTo(span.getSpanId());
  }

  @Test
  void checkoutDoesNotMutateSharedPeople() {
    user.checkout();
    assertThat(people).allSatisfy(p -> assertThat(p).doesNotContainKey("userId"));
  }

  @Test
  void multiCheckoutAddsTwoToFourItemsForOneUser() {
    Map<Integer, Integer> sizes = new HashMap<>();
    for (int i = 0; i < 60; i++) {
      site.requests().clear();
      telemetry.spans.reset();
      user.checkoutMulti();
      List<FakeSite.Recorded> posts = site.requests().stream().filter(r -> r.method().equals("POST")).toList();
      int items = posts.size() - 1;
      assertThat(posts.get(posts.size() - 1).path()).isEqualTo("/api/checkout");
      assertThat(posts.subList(0, items)).allSatisfy(p -> assertThat(p.path()).isEqualTo("/api/cart"));
      String userId = telemetry.mapper.readTree(posts.get(0).body()).get("userId").asString();
      assertThat(posts).allSatisfy(p -> assertThat(telemetry.mapper.readTree(p.body()).get("userId").asString()).isEqualTo(userId));
      SpanData span = telemetry.span("user_checkout_multi");
      assertThat(span.getAttributes().get(AttributeKey.longKey("demo.cart.items.count"))).isEqualTo((long) items);
      sizes.merge(items, 1, Integer::sum);
    }
    assertThat(sizes.keySet()).containsExactlyInAnyOrder(2, 3, 4);
  }

  @Test
  void floodHomeDoesNothingWhenFlagIsZero() {
    flags.put("loadGeneratorFloodHomepage", 0);
    user.floodHome();
    assertThat(site.requests()).isEmpty();
    assertThat(telemetry.spanData()).isEmpty();
  }

  @Test
  void floodHomeRequestsHomepageFlagTimes() {
    flags.put("loadGeneratorFloodHomepage", 7);
    user.floodHome();

    assertThat(paths()).containsExactlyElementsOf(java.util.Collections.nCopies(7, "/"));
    SpanData span = telemetry.span("user_flood_home");
    assertThat(span.getAttributes().get(AttributeKey.longKey("demo.request.flood.count"))).isEqualTo(7L);
  }

  @Test
  void askAgentPostsPromptToAgentWithGenAiAttribute() {
    user.askAgent();

    assertThat(site.requests()).isEmpty();
    FakeSite.Recorded request = agent.requests().get(0);
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.path()).isEqualTo("/prompt");
    String prompt = telemetry.mapper.readTree(request.body()).get("message").asString();
    assertThat(Catalog.AGENT_PROMPTS).contains(prompt);
    assertThat(attr(telemetry.span("user_ask_agent"), "gen_ai.input.messages"))
        .isEqualTo("[{\"role\": \"user\", \"parts\": [{\"type\": \"text\", \"content\": \"" + prompt + "\"}]}]");
  }

  @Test
  void sessionStartAttachesBaggageAndVisitsIndexInsideSessionSpan() {
    try (Scope ignored = user.onStart()) {
      user.viewCart();
    }

    assertThat(paths()).containsExactly("/", "/api/cart");
    for (FakeSite.Recorded request : site.requests()) {
      String baggage = request.header("baggage");
      assertThat(baggage).contains("synthetic_request=true").containsPattern("session\\.id=[0-9a-f-]{36}");
    }
    // same session id for the whole life of the user
    assertThat(site.requests().get(0).header("baggage")).isEqualTo(site.requests().get(1).header("baggage"));
    SpanData session = telemetry.span("user_session_start");
    assertThat(telemetry.span("user_index").getParentSpanId()).isEqualTo(session.getSpanId());
  }

  @Test
  void differentUsersGetDifferentSessions() {
    String first;
    try (Scope ignored = user.onStart()) {
      first = site.requests().get(0).header("baggage");
    }
    site.requests().clear();
    try (Scope ignored = newUser(seeded(2), s -> {}).onStart()) {
      assertThat(site.requests().get(0).header("baggage")).isNotEqualTo(first);
    }
  }

  @Test
  void runLoopWaitsBetweenOneAndTenSecondsAfterEachTask() {
    List<Double> waits = new ArrayList<>();
    AtomicInteger count = new AtomicInteger();
    WebsiteUser looping =
        newUser(
            seeded(5),
            seconds -> {
              waits.add(seconds);
              if (count.incrementAndGet() >= 25) {
                throw new InterruptedException();
              }
            });

    looping.run();

    assertThat(waits).hasSize(25).allSatisfy(w -> assertThat(w).isBetween(1.0, 10.0));
    assertThat(waits.stream().distinct().count()).isGreaterThan(20);
    // session start index + 25 tasks (task bodies issue >= 0 requests each)
    assertThat(site.requests()).isNotEmpty();
    assertThat(site.requests().get(0).uri()).isEqualTo("/");
    assertThat(Thread.interrupted()).isTrue(); // run() restores the interrupt flag; clear it for other tests
  }

  @Test
  void aFailingTaskDoesNotEndTheUser() {
    // host is unreachable: every request fails, the user keeps going
    site.close();
    AtomicInteger waits = new AtomicInteger();
    WebsiteUser looping =
        newUser(seeded(9), seconds -> {
          if (waits.incrementAndGet() >= 5) {
            throw new InterruptedException();
          }
        });
    looping.run();
    assertThat(waits.get()).isEqualTo(5);
    assertThat(telemetry.stats.total().numFailures()).isGreaterThan(0);
    assertThat(Thread.interrupted()).isTrue();
  }
}
