/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagJsonTest {

  @Test
  void decodesObjectsIntoKeySortedMaps() throws Exception {
    Object decoded = FlagJson.decode("{\"b\":1,\"a\":{\"z\":true,\"y\":null},\"c\":[1.5,\"x\"]}");

    assertEquals("[a, b, c]", ((Map<?, ?>) decoded).keySet().toString());
    assertEquals(
        "{\"a\":{\"y\":null,\"z\":true},\"b\":1,\"c\":[1.5,\"x\"]}", FlagJson.encode(decoded));
  }

  @Test
  void rejectsInvalidAndEmptyDocuments() {
    assertThrows(FlagJson.InvalidJsonException.class, () -> FlagJson.decode("not valid json"));
    assertThrows(FlagJson.InvalidJsonException.class, () -> FlagJson.decode(""));
    assertThrows(FlagJson.InvalidJsonException.class, () -> FlagJson.decode("   "));
    assertThrows(FlagJson.InvalidJsonException.class, () -> FlagJson.decode("{} {}"));
    assertThrows(FlagJson.InvalidJsonException.class, () -> FlagJson.decode("{\"a\":}"));
  }

  @Test
  void acceptsAnyJsonValueAtTheTopLevel() throws Exception {
    assertEquals(List.of(1L, 2L), FlagJson.decode("[1,2]"));
    assertEquals("x", FlagJson.decode("\"x\""));
    assertEquals(12L, FlagJson.decode(" 12 "));
  }

  @Test
  void prettyOutputMatchesJasonFormat() throws Exception {
    Object value =
        FlagJson.decode("{\"flags\":{\"a\":{\"on\":[],\"off\":{},\"list\":[1,{\"k\":\"v\"}]}}}");

    String expected =
        String.join(
            "\n",
            "{",
            "  \"flags\": {",
            "    \"a\": {",
            "      \"list\": [",
            "        1,",
            "        {",
            "          \"k\": \"v\"",
            "        }",
            "      ],",
            "      \"off\": {},",
            "      \"on\": []",
            "    }",
            "  }",
            "}");
    assertEquals(expected, FlagJson.encodePretty(value));
  }

  @Test
  void escapesStringsLikeJason() {
    assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001/é\"", FlagJson.encode("a\"b\\c\n\t\u0001/é"));
  }

  @Test
  void printsFloatsAndIntegersWithoutLosingTheirKind() throws Exception {
    assertEquals(
        "[0.1,0.95,1.0,100,12345678901234567890]",
        FlagJson.encode(FlagJson.decode("[0.1, 0.95, 1.0, 100, 12345678901234567890]")));
    assertEquals("1.0e-5", FlagJson.formatDouble(1.0e-5));
    assertEquals("12345678.0", FlagJson.formatDouble(12345678.0));
  }
}
