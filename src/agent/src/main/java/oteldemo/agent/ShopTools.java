/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.agent;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Astronomy Shop tools: Java port of {@code src/shared/tools.py}. Every tool calls the frontend
 * HTTP API at {@code http://APPLICATION_ENDPOINT/api/...} and returns either the parsed JSON body
 * or an error string (tools never raise on HTTP/transport errors, exactly like the original).
 */
final class ShopTools {

  /** Raised for failures the Python original lets propagate out of the tool function. */
  static final class ToolFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ToolFailure(String message) {
      super(message);
    }
  }

  @FunctionalInterface
  interface Handler {
    Object call(Map<String, Object> args);
  }

  /** A tool: name, docstring (cleaned, as FastMCP reports it), JSON schema and implementation. */
  record Tool(String name, String description, Map<String, Object> inputSchema, Handler handler) {}

  /** HTTP response snapshot. */
  private record Resp(int status, String reason, String url, String location, byte[] body) {
    boolean success() {
      return status >= 200 && status < 300;
    }

    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private static final AttributeKey<String> GEN_AI_TOOL_NAME =
      AttributeKey.stringKey("gen_ai.tool.name");

  private final RestClient client;
  private final String baseUrl;
  private final LongCounter toolCallCounter;
  private final Map<String, Tool> tools = new LinkedHashMap<>();

  ShopTools(RestClient client, String applicationEndpoint, OpenTelemetry openTelemetry) {
    this.client = client;
    this.baseUrl = "http://" + applicationEndpoint;
    this.toolCallCounter =
        openTelemetry
            .getMeter("oteldemo.agent.tools")
            .counterBuilder("demo.tool.calls")
            .setUnit("{call}")
            .setDescription(
                "Number of Astronomy Shop tool calls made by the agent and MCP services")
            .build();
    register();
  }

  List<Tool> tools() {
    return List.copyOf(tools.values());
  }

  Tool tool(String name) {
    return tools.get(name);
  }

  /**
   * Validates the arguments like pydantic does for the Python signature, counts the call
   * ({@code demo.tool.calls}) and runs the tool.
   */
  Object invoke(String name, Map<String, Object> rawArgs) {
    Tool tool = tools.get(name);
    if (tool == null) {
      throw new IllegalArgumentException("Unknown tool: " + name);
    }
    Map<String, Object> args = ToolArguments.validate(tool, rawArgs == null ? Map.of() : rawArgs);
    toolCallCounter.add(1, Attributes.of(GEN_AI_TOOL_NAME, name));
    return tool.handler().call(args);
  }

  // ---------------------------------------------------------------------------------------------
  // Tool registry. Order, names, descriptions and schemas follow the FastMCP registration order.
  // ---------------------------------------------------------------------------------------------

  private void register() {
    add(
        "add_to_cart",
        "Add a product (product_id) to the shopping cart for a user (user_id).",
        schema(
            List.of(
                prop("user_id", "string"),
                prop("product_id", "string"),
                prop("quantity", "integer", 1)),
            List.of("user_id", "product_id")),
        this::addToCart);
    add(
        "checkout",
        "Checkout the user's cart and create an order.\n"
            + "Takes request in the format {string user_id, string userCurrency, Address address,"
            + " string email, CreditCardInfo creditCard}\n"
            + "Where Address is {string streetAddress, string city, string state, string country,"
            + " string zipCode} and\n"
            + "CreditCardInfo is {string creditCardNumber, int32 creditCardCvv, int32"
            + " creditCardExpirationYear, int32 creditCardExpirationMonth}",
        schema(List.of(prop("checkout_person", null)), List.of("checkout_person")),
        this::checkout);
    add(
        "empty_cart",
        "Empty the shopping cart for a user.",
        schema(List.of(prop("user_id", "string")), List.of("user_id")),
        this::emptyCart);
    add(
        "get_ads",
        "Fetch promotional ads for Astronomy Shop homepage.\n"
            + "Eg : category: `telescopes` or `travel`",
        schema(List.of(prop("category", "string")), List.of("category")),
        this::getAds);
    add(
        "get_cart",
        "Retrieve the current contents of a user's cart.",
        schema(List.of(prop("user_id", "string")), List.of("user_id")),
        this::getCart);
    add(
        "get_product",
        "Get detailed information about a product using its ID.",
        schema(List.of(prop("product_id", "string")), List.of("product_id")),
        this::getProduct);
    add(
        "get_recommendations",
        "Get product recommendations for a user.",
        schema(List.of(prop("product_id", "string")), List.of("product_id")),
        this::getRecommendations);
    add(
        "get_shipping_quote",
        "Get estimated shipping cost for a given address.\n"
            + "`items`: list of {productId, quantity} (a single dict is also accepted).\n"
            + "`currency_code`: ISO 4217 code, e.g. \"USD\".\n"
            + "`address`: {streetAddress, city, state, country, zipCode}.",
        schema(
            List.of(prop("items", null), prop("currency_code", null), prop("address", null)),
            List.of("items", "currency_code", "address")),
        this::getShippingQuote);
    add(
        "get_supported_currencies",
        "List supported currencies in Astronomy Shop.",
        schema(List.of(), List.of()),
        a -> getSupportedCurrencies());
    add(
        "list_products",
        "List all products available in the Astronomy Shop.",
        schema(List.of(), List.of()),
        a -> listProducts());
  }

  private void add(String name, String description, Map<String, Object> schema, Handler handler) {
    tools.put(name, new Tool(name, description, schema, handler));
  }

  private record Prop(String name, Map<String, Object> schema) {}

  private static Prop prop(String name, String type) {
    Map<String, Object> s = new LinkedHashMap<>();
    if (type != null) {
      s.put("type", type);
    }
    return new Prop(name, s);
  }

  private static Prop prop(String name, String type, Object defaultValue) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("default", defaultValue);
    s.put("type", type);
    return new Prop(name, s);
  }

  /** JSON schema as FastMCP derives it from the Python signature (property order preserved). */
  private static Map<String, Object> schema(List<Prop> props, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("additionalProperties", false);
    Map<String, Object> properties = new LinkedHashMap<>();
    for (Prop p : props) {
      properties.put(p.name(), p.schema());
    }
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    schema.put("type", "object");
    return schema;
  }

  // ---------------------------------------------------------------------------------------------
  // Tool implementations (1:1 with src/shared/tools.py)
  // ---------------------------------------------------------------------------------------------

  private Object getAds(Map<String, Object> a) {
    String category = (String) a.get("category");
    try {
      URI uri = withParam("/api/data", "contextKeys", category);
      return json(raiseForStatus(send(HttpMethod.GET, uri, null)));
    } catch (Exception e) {
      return "Error fetching ads: " + message(e);
    }
  }

  private Object addToCart(Map<String, Object> a) {
    ObjectNode item = JsonNodeFactory.instance.objectNode();
    item.put("productId", (String) a.get("product_id"));
    item.set("quantity", PyJson.toNode(a.get("quantity")));
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.set("item", item);
    data.put("userId", (String) a.get("user_id"));
    try {
      return json(
          raiseForStatus(
              send(HttpMethod.POST, URI.create(baseUrl + "/api/cart"), data.toString())));
    } catch (Exception e) {
      return "Error while adding product to cart: " + message(e);
    }
  }

  private Object getCart(Map<String, Object> a) {
    try {
      URI uri = withParam("/api/cart", "user_id", (String) a.get("user_id"));
      return json(raiseForStatus(send(HttpMethod.GET, uri, null)));
    } catch (Exception e) {
      return "Error while fetching cart: " + message(e);
    }
  }

  private Object emptyCart(Map<String, Object> a) {
    String userId = (String) a.get("user_id");
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("userId", userId);
    try {
      Resp res =
          raiseForStatus(
              send(HttpMethod.DELETE, URI.create(baseUrl + "/api/cart"), payload.toString()));
      if (res.status() == 204 || res.body().length == 0) {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "success");
        ok.put("message", "Cart emptied for user " + userId);
        return ok;
      }
      return json(res);
    } catch (Exception e) {
      return "Error while emptying cart: " + message(e);
    }
  }

  private Object listProducts() {
    try {
      return json(
          raiseForStatus(send(HttpMethod.GET, URI.create(baseUrl + "/api/products"), null)));
    } catch (Exception e) {
      return "Error while fetching product list: " + message(e);
    }
  }

  private Object getProduct(Map<String, Object> a) {
    String productId = (String) a.get("product_id");
    try {
      URI uri =
          URI.create(
              baseUrl
                  + "/api/products/"
                  + UriUtils.encodePath(productId, StandardCharsets.UTF_8));
      return json(raiseForStatus(send(HttpMethod.GET, uri, null)));
    } catch (Exception e) {
      return "Error while fetching product " + productId + ": " + message(e);
    }
  }

  private Object checkout(Map<String, Object> a) {
    Object person = a.get("checkout_person");
    try {
      Resp res =
          send(
              HttpMethod.POST,
              URI.create(baseUrl + "/api/checkout"),
              PyJson.toNode(person).toString());
      if (!res.success()) {
        String body = res.text().strip();
        if (body.isEmpty()) {
          body = "<empty body>";
        }
        if (!(person instanceof Map<?, ?> m)) {
          // Python: checkout_person.get(...) raises AttributeError inside the try block
          throw new ToolFailure(
              "'" + PyJson.pyTypeName(person) + "' object has no attribute 'get'");
        }
        Object userId = m.containsKey("userId") ? m.get("userId") : "<unknown>";
        return "Checkout failed with HTTP "
            + res.status()
            + " for user "
            + (userId == null ? "None" : userId)
            + ": "
            + body
            + ". Note: the user's cart must contain at least one item before calling checkout;"
            + " call add_to_cart first.";
      }
      return json(res);
    } catch (Exception e) {
      return "Error while performing checkout: " + message(e);
    }
  }

  private Object getSupportedCurrencies() {
    try {
      return json(
          raiseForStatus(send(HttpMethod.GET, URI.create(baseUrl + "/api/currency"), null)));
    } catch (Exception e) {
      return "Error while fetching currency list: " + message(e);
    }
  }

  private Object getRecommendations(Map<String, Object> a) {
    try {
      URI uri = withParam("/api/recommendations", "productIds", (String) a.get("product_id"));
      return json(raiseForStatus(send(HttpMethod.GET, uri, null)));
    } catch (Exception e) {
      return "Error fetching recommendations: " + message(e);
    }
  }

  private Object getShippingQuote(Map<String, Object> a) {
    Object items = a.get("items");
    Object currencyCode = a.get("currency_code");
    Object address = a.get("address");

    if (items instanceof Map) {
      items = List.of(items);
    }
    List<Object> iterable;
    if (items instanceof List<?> l) {
      iterable = new ArrayList<>(l);
    } else if (items instanceof String s) {
      // Python iterates a str character by character; each char is a non-dict -> invalid item
      iterable = new ArrayList<>();
      s.codePoints().forEach(cp -> iterable.add(new String(Character.toChars(cp))));
    } else {
      throw new ToolFailure("'" + PyJson.pyTypeName(items) + "' object is not iterable");
    }

    List<Object> normalised = new ArrayList<>();
    for (Object it : iterable) {
      if (!(it instanceof Map<?, ?> m)) {
        return "Error fetching shipping quote: invalid item " + PyJson.repr(it);
      }
      Object productId = truthy(m.get("productId")) ? m.get("productId") : m.get("product_id");
      Object quantity = m.containsKey("quantity") ? m.get("quantity") : 1;
      if (!truthy(productId)) {
        return "Error fetching shipping quote: each item must include productId";
      }
      Map<String, Object> n = new LinkedHashMap<>();
      n.put("productId", productId);
      n.put("quantity", quantity);
      normalised.add(n);
    }

    try {
      UriComponentsBuilder b =
          UriComponentsBuilder.fromUriString(baseUrl + "/api/shipping")
              .queryParam("itemList", "{itemList}");
      Map<String, Object> vars = new LinkedHashMap<>();
      vars.put("itemList", PyJson.dumps(normalised, true));
      if (currencyCode != null) {
        b.queryParam("currencyCode", "{currencyCode}");
        vars.put(
            "currencyCode",
            currencyCode instanceof String ? currencyCode : PyJson.repr(currencyCode));
      }
      b.queryParam("address", "{address}");
      vars.put("address", PyJson.dumps(address, true));
      URI uri = b.encode().buildAndExpand(vars).toUri();
      Resp res = send(HttpMethod.GET, uri, null);
      if (!res.success()) {
        String body = res.text().strip();
        if (body.isEmpty()) {
          body = "<empty body>";
        }
        return "Shipping quote failed with HTTP " + res.status() + ": " + body + ". ";
      }
      return json(res);
    } catch (Exception e) {
      return "Error fetching shipping quote: " + message(e);
    }
  }

  private static boolean truthy(Object o) {
    if (o == null) {
      return false;
    }
    if (o instanceof String s) {
      return !s.isEmpty();
    }
    if (o instanceof Number n) {
      return n.doubleValue() != 0;
    }
    if (o instanceof Boolean b) {
      return b;
    }
    if (o instanceof Map<?, ?> m) {
      return !m.isEmpty();
    }
    if (o instanceof List<?> l) {
      return !l.isEmpty();
    }
    return true;
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP helpers (httpx-like semantics: no redirects, status handled by the caller)
  // ---------------------------------------------------------------------------------------------

  private URI withParam(String path, String name, String value) {
    return UriComponentsBuilder.fromUriString(baseUrl + path)
        .queryParam(name, "{v}")
        .encode()
        .buildAndExpand(value)
        .toUri();
  }

  private Resp send(HttpMethod method, URI uri, String jsonBody) {
    RestClient.RequestBodySpec spec = client.method(method).uri(uri);
    if (jsonBody != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
    }
    return spec.exchange(
        (req, res) -> {
          int code = res.getStatusCode().value();
          HttpStatus hs = HttpStatus.resolve(code);
          String reason = hs != null ? hs.getReasonPhrase() : "";
          String location = res.getHeaders().getFirst("Location");
          return new Resp(code, reason, uri.toString(), location, res.getBody().readAllBytes());
        });
  }

  /** httpx {@code Response.raise_for_status()} message format. */
  private static Resp raiseForStatus(Resp res) {
    if (res.success()) {
      return res;
    }
    int code = res.status();
    String kind;
    if (code >= 100 && code < 200) {
      kind = "Informational response";
    } else if (code >= 300 && code < 400) {
      kind = "Redirect response";
    } else if (code >= 400 && code < 500) {
      kind = "Client error";
    } else {
      kind = "Server error";
    }
    StringBuilder sb =
        new StringBuilder(kind)
            .append(" '")
            .append(code)
            .append(' ')
            .append(res.reason())
            .append("' for url '")
            .append(res.url())
            .append("'");
    if (code >= 300 && code < 400 && res.location() != null) {
      sb.append("\nRedirect location: '").append(res.location()).append("'");
    }
    sb.append("\nFor more information check: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/")
        .append(code);
    throw new ToolFailure(sb.toString());
  }

  private static JsonNode json(Resp res) {
    try {
      JsonNode node = PyJson.MAPPER.readTree(res.body());
      if (node == null || node.isMissingNode()) {
        throw new ToolFailure("Expecting value: line 1 column 1 (char 0)");
      }
      return node;
    } catch (JacksonException e) {
      throw new ToolFailure("Expecting value: line 1 column 1 (char 0)");
    }
  }

  /** Approximates {@code str(exception)} of the httpx/anyio exceptions the original reports. */
  static String message(Throwable e) {
    if (e instanceof ToolFailure) {
      return e.getMessage();
    }
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    if (root instanceof HttpConnectTimeoutException || root instanceof HttpTimeoutException) {
      return "";
    }
    if (root instanceof ConnectException
        || root instanceof UnresolvedAddressException
        || root instanceof java.net.UnknownHostException
        || root instanceof java.nio.channels.ClosedChannelException) {
      return "All connection attempts failed";
    }
    if (root instanceof IOException) {
      return "Server disconnected without sending a response.";
    }
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }
}
