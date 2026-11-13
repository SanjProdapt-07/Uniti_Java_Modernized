/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import static oteldemo.loadgenerator.UserTelemetry.inSpan;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plain HTTP simulated user ({@code WebsiteUser} of the locustfile): same tasks, weights, URLs, payloads,
 * spans, log lines and {@code between(1, 10)} wait time.
 */
public class WebsiteUser implements SimulatedUser {

  private static final Logger log = LoggerFactory.getLogger(WebsiteUser.class);

  static final String FLOOD_FLAG = "loadGeneratorFloodHomepage";
  static final double MIN_WAIT_SECONDS = 1;
  static final double MAX_WAIT_SECONDS = 10;

  /** A weighted task, like a {@code @task(weight)} method. */
  record Task(String name, int weight, Runnable body) {}

  /** Blocking sleep, replaceable in tests. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(double seconds) throws InterruptedException;
  }

  private final Tracer tracer;
  private final UserHttpSession http;
  private final ToIntFunction<String> integerFlag;
  private final RandomGenerator random;
  private final List<Map<String, Object>> people;
  private final String agentBaseUrl;
  private final JsonMapper mapper;
  private final Sleeper sleeper;
  private final List<Task> tasks = new ArrayList<>();
  private final int totalWeight;

  public WebsiteUser(
      Tracer tracer,
      UserHttpSession http,
      ToIntFunction<String> integerFlag,
      RandomGenerator random,
      List<Map<String, Object>> people,
      String agentEndpoint,
      String agentPort,
      JsonMapper mapper) {
    this(tracer, http, integerFlag, random, people, agentEndpoint, agentPort, mapper,
        seconds -> Thread.sleep((long) (seconds * 1000)));
  }

  WebsiteUser(
      Tracer tracer,
      UserHttpSession http,
      ToIntFunction<String> integerFlag,
      RandomGenerator random,
      List<Map<String, Object>> people,
      String agentEndpoint,
      String agentPort,
      JsonMapper mapper,
      Sleeper sleeper) {
    this.tracer = tracer;
    this.http = http;
    this.integerFlag = integerFlag;
    this.random = random;
    this.people = people;
    this.agentBaseUrl = "http://" + agentEndpoint + ":" + agentPort;
    this.mapper = mapper;
    this.sleeper = sleeper;

    tasks.add(new Task("index", 1, this::index));
    tasks.add(new Task("browse_product", 10, this::browseProduct));
    tasks.add(new Task("get_recommendations", 3, this::getRecommendations));
    tasks.add(new Task("get_ads", 3, this::getAds));
    tasks.add(new Task("view_cart", 3, this::viewCart));
    tasks.add(new Task("add_to_cart", 2, () -> addToCart("")));
    tasks.add(new Task("checkout", 1, this::checkout));
    tasks.add(new Task("checkout_multi", 1, this::checkoutMulti));
    tasks.add(new Task("flood_home", 5, this::floodHome));
    tasks.add(new Task("ask_agent", 3, this::askAgent));
    this.totalWeight = tasks.stream().mapToInt(Task::weight).sum();
  }

  List<Task> tasks() {
    return List.copyOf(tasks);
  }

  /** Weighted random task selection ({@code random.choice} over the weight-expanded task list). */
  Task pickTask() {
    int roll = random.nextInt(totalWeight);
    for (Task task : tasks) {
      roll -= task.weight();
      if (roll < 0) {
        return task;
      }
    }
    throw new IllegalStateException("unreachable");
  }

  @Override
  public void run() {
    try (Scope ignored = onStart()) {
      while (!Thread.currentThread().isInterrupted()) {
        runTask(pickTask());
        sleeper.sleep(random.nextDouble(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS));
      }
    } catch (InterruptedException | SimulatedUserStopped stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private void runTask(Task task) {
    try {
      task.body().run();
    } catch (SimulatedUserStopped stopped) {
      throw stopped;
    } catch (RuntimeException e) {
      log.error("Task {} failed", task.name(), e);
    }
  }

  /** Session setup: session id + synthetic request baggage for the whole user lifetime, then visit index. */
  Scope onStart() {
    String sessionId = UUID.randomUUID().toString();
    log.info("Starting user session: {}", sessionId);
    // The baggage context is made current outside of any span scope and stays attached for the user's
    // whole life (see the comment in the original on_start).
    Baggage baggage =
        Baggage.current().toBuilder().put("session.id", sessionId).put("synthetic_request", "true").build();
    Scope scope = Context.current().with(baggage).makeCurrent();
    try {
      inSpan(tracer, "user_session_start", Attributes.empty(), this::index);
    } catch (RuntimeException e) {
      scope.close();
      throw e;
    }
    return scope;
  }

  void index() {
    inSpan(
        tracer,
        "user_index",
        Attributes.empty(),
        () -> {
          log.info("User accessing index page");
          http.get("/");
        });
  }

  void browseProduct() {
    String product = randomProduct();
    inSpan(
        tracer,
        "user_browse_product",
        Attributes.of(AttributeKey.stringKey("demo.product.id"), product),
        () -> {
          log.info("User browsing product: {}", product);
          http.get("/api/products/" + product);
        });
  }

  void getRecommendations() {
    String product = randomProduct();
    inSpan(
        tracer,
        "user_get_recommendations",
        Attributes.of(AttributeKey.stringKey("demo.product.id"), product),
        () -> {
          log.info("User getting recommendations for product: {}", product);
          http.get("/api/recommendations", Map.of("productIds", List.of(product)));
        });
  }

  void getAds() {
    String category = Catalog.CATEGORIES.get(random.nextInt(Catalog.CATEGORIES.size()));
    String categoryText = category == null ? "None" : category; // Python str(None)
    inSpan(
        tracer,
        "user_get_ads",
        Attributes.of(AttributeKey.stringKey("demo.ad.category"), categoryText),
        () -> {
          log.info("User getting ads for category: {}", categoryText);
          Map<String, List<String>> params = new LinkedHashMap<>();
          params.put("contextKeys", java.util.Collections.singletonList(category));
          http.get("/api/data/", params);
        });
  }

  void viewCart() {
    inSpan(
        tracer,
        "user_view_cart",
        Attributes.empty(),
        () -> {
          log.info("User viewing cart");
          http.get("/api/cart");
        });
  }

  /** {@code add_to_cart(user="")}: a blank user means a fresh user id. */
  void addToCart(String user) {
    String userId = user == null || user.isEmpty() ? UUID.randomUUID().toString() : user;
    String product = randomProduct();
    int quantity = Catalog.QUANTITIES.get(random.nextInt(Catalog.QUANTITIES.size()));
    inSpan(
        tracer,
        "user_add_to_cart",
        Attributes.of(
            AttributeKey.stringKey("user.id"), userId,
            AttributeKey.stringKey("demo.product.id"), product,
            AttributeKey.longKey("demo.product.quantity"), (long) quantity),
        () -> {
          log.info("User {} adding {} of product {} to cart", userId, quantity, product);
          http.get("/api/products/" + product);
          Map<String, Object> cartItem = new LinkedHashMap<>();
          cartItem.put("item", orderedMap("productId", product, "quantity", quantity));
          cartItem.put("userId", userId);
          http.post("/api/cart", cartItem);
        });
  }

  void checkout() {
    String user = UUID.randomUUID().toString();
    inSpan(
        tracer,
        "user_checkout_single",
        Attributes.of(AttributeKey.stringKey("user.id"), user),
        () -> {
          addToCart(user);
          http.post("/api/checkout", checkoutPayload(user));
          log.info("Checkout completed for user {}", user);
        });
  }

  void checkoutMulti() {
    String user = UUID.randomUUID().toString();
    int itemCount = Catalog.MULTI_CHECKOUT_ITEM_COUNTS.get(random.nextInt(Catalog.MULTI_CHECKOUT_ITEM_COUNTS.size()));
    inSpan(
        tracer,
        "user_checkout_multi",
        Attributes.of(
            AttributeKey.stringKey("user.id"), user,
            AttributeKey.longKey("demo.cart.items.count"), (long) itemCount),
        () -> {
          for (int i = 0; i < itemCount; i++) {
            addToCart(user);
          }
          http.post("/api/checkout", checkoutPayload(user));
          log.info("Multi-item checkout completed for user {}", user);
        });
  }

  void floodHome() {
    int floodCount = integerFlag.applyAsInt(FLOOD_FLAG);
    if (floodCount > 0) {
      inSpan(
          tracer,
          "user_flood_home",
          Attributes.of(AttributeKey.longKey("demo.request.flood.count"), (long) floodCount),
          () -> {
            log.info("User flooding homepage {} times", floodCount);
            for (int i = 0; i < floodCount; i++) {
              http.get("/");
            }
          });
    }
  }

  void askAgent() {
    String prompt = Catalog.AGENT_PROMPTS.get(random.nextInt(Catalog.AGENT_PROMPTS.size()));
    String inputMessages =
        "[{\"role\": \"user\", \"parts\": [{\"type\": \"text\", \"content\": "
            + mapper.writeValueAsString(prompt)
            + "}]}]";
    inSpan(
        tracer,
        "user_ask_agent",
        Attributes.of(AttributeKey.stringKey("gen_ai.input.messages"), inputMessages),
        () -> {
          log.info("User asking agent: {}", prompt);
          http.post(agentBaseUrl + "/prompt", Map.of("message", prompt));
        });
  }

  private Map<String, Object> checkoutPayload(String user) {
    // copy: the original mutated the shared people entry; a copy avoids cross-user races
    Map<String, Object> person = new LinkedHashMap<>(people.get(random.nextInt(people.size())));
    person.put("userId", user);
    return person;
  }

  private String randomProduct() {
    return Catalog.PRODUCTS.get(random.nextInt(Catalog.PRODUCTS.size()));
  }

  private static Map<String, Object> orderedMap(String k1, Object v1, String k2, Object v2) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(k1, v1);
    map.put(k2, v2);
    return map;
  }
}
