/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The constant data of the original locustfile: categories, products, agent prompts and checkout people. */
public final class Catalog {

  /** Ad categories; {@code null} is the "no context key" case of the original. */
  public static final List<String> CATEGORIES =
      Arrays.asList("binoculars", "telescopes", "accessories", "assembly", "travel", "books", null);

  public static final List<String> PRODUCTS =
      List.of(
          "0PUK6V6EV0",
          "1YMWWN1N4O",
          "2ZYFJ3GM2N",
          "66VCHSJNUP",
          "6E92ZMYYFZ",
          "9SIQT8TOJO",
          "L9ECAV7KIM",
          "LS4PSXUNUM",
          "OLJCESPC7Z",
          "HQTGWGPNH4");

  /** Prompts the agent's bundled VCR fixtures have canned responses for. */
  public static final List<String> AGENT_PROMPTS =
      List.of(
          "Show all available products in the store.",
          "What currencies are supported by the Astronomy Shop?",
          "What current promotions are available on binoculars?");

  public static final List<Integer> QUANTITIES = List.of(1, 2, 3, 4, 5, 10);

  public static final List<Integer> MULTI_CHECKOUT_ITEM_COUNTS = List.of(2, 3, 4);

  private Catalog() {}

  /** Loads {@code people.json} (checkout payloads) from the classpath. */
  public static List<Map<String, Object>> loadPeople(JsonMapper mapper) {
    try (InputStream in = new ClassPathResource("people.json").getInputStream()) {
      return mapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read people.json", e);
    }
  }
}
