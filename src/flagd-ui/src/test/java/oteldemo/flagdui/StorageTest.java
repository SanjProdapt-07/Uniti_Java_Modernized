/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {

  @TempDir Path dir;

  @SuppressWarnings("unchecked")
  private static Map<String, Object> flag(Storage storage, String name) {
    Map<String, Object> flags =
        (Map<String, Object>) ((Map<String, Object>) storage.read()).get("flags");
    return (Map<String, Object>) flags.get(name);
  }

  @Test
  void startsFromTheFileContent() {
    Storage storage = new Storage(TestFixtures.copyTo(dir));

    assertEquals("off", flag(storage, "adFailure").get("defaultVariant"));
  }

  @Test
  void startsEmptyWhenTheFileIsMissingEmptyOrInvalid() throws Exception {
    assertEquals(Map.of(), new Storage(dir.resolve("missing.json")).read());

    Path empty = dir.resolve("empty.json");
    Files.writeString(empty, "");
    assertEquals(Map.of(), new Storage(empty).read());

    Path invalid = dir.resolve("invalid.json");
    Files.writeString(invalid, "{not json");
    assertEquals(Map.of(), new Storage(invalid).read());
  }

  @Test
  void replaceWithInvalidJsonIgnoresItAndKeepsPriorStateAndFileContent() throws Exception {
    Path file = TestFixtures.copyTo(dir);
    Storage storage = new Storage(file);
    Object stateBefore = storage.read();
    String fileContentBefore = Files.readString(file);

    assertFalse(storage.replace("not valid json"));

    assertEquals(stateBefore, storage.read());
    assertEquals(fileContentBefore, Files.readString(file));
  }

  @Test
  void replaceWritesTheTextVerbatimAndUpdatesTheState() throws Exception {
    Path file = TestFixtures.copyTo(dir);
    Storage storage = new Storage(file);
    long versionBefore = storage.version();

    String text = "{\"flags\":{\"x\":{\"defaultVariant\":\"a\",\"variants\":{\"a\":1}}}}";
    assertTrue(storage.replace(text));

    assertEquals(text, Files.readString(file));
    assertEquals("a", flag(storage, "x").get("defaultVariant"));
    assertTrue(storage.version() > versionBefore);
  }

  @Test
  void writeSetsTheDefaultVariantAndPersistsPrettyJson() throws Exception {
    Path file = TestFixtures.copyTo(dir);
    Storage storage = new Storage(file);

    storage.write("adFailure", "on");

    assertEquals("on", flag(storage, "adFailure").get("defaultVariant"));
    String written = Files.readString(file, StandardCharsets.UTF_8);
    assertTrue(written.startsWith("{\n  \"$schema\": "), written.substring(0, 40));
    assertEquals(storage.read(), new Storage(file).read());
    assertFalse(
        Files.exists(dir.resolve("demo.flagd.json.tmp")), "temporary file must be renamed away");
  }

  @Test
  @SuppressWarnings("unchecked")
  void writeSwitchesAThreeElementTargetingRuleInsteadOfTheDefault() {
    Storage storage = new Storage(TestFixtures.copyTo(dir));
    Map<String, Object> before = flag(storage, "productCatalogFailure");
    List<Object> ruleBefore =
        (List<Object>) ((Map<String, Object>) before.get("targeting")).get("if");
    assertEquals(3, ruleBefore.size());

    storage.write("productCatalogFailure", "on");

    Map<String, Object> after = flag(storage, "productCatalogFailure");
    List<Object> rule = (List<Object>) ((Map<String, Object>) after.get("targeting")).get("if");
    assertEquals("on", rule.get(1));
    assertEquals(ruleBefore.get(0), rule.get(0));
    assertEquals(ruleBefore.get(2), rule.get(2));
    assertEquals(before.get("defaultVariant"), after.get("defaultVariant"));
  }

  @Test
  void writeForAnUnknownFlagLeavesTheFlagsUntouched() {
    Storage storage = new Storage(TestFixtures.copyTo(dir));
    String before = FlagJson.encode(storage.read());

    storage.write("noSuchFlag", "on");

    assertEquals(before, FlagJson.encode(storage.read()));
  }

  @Test
  void writeWithoutFlagsCreatesAnEmptyFlagsObject() throws Exception {
    Path file = dir.resolve("none.json");
    Files.writeString(file, "{}");
    Storage storage = new Storage(file);

    storage.write("adFailure", "on");

    assertEquals(Map.of("flags", Map.of()), storage.read());
    assertEquals("{\n  \"flags\": {}\n}", Files.readString(file));
    assertNotEquals(0, storage.version());
  }
}
