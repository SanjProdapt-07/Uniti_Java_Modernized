/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Access to the flagd configuration used as the test fixture (the same file flagd serves). */
final class TestFixtures {

  private static final Path FIXTURE = locateFixture();
  private static Path sharedStorageFile;

  private TestFixtures() {}

  private static Path locateFixture() {
    for (String candidate : new String[] {"../flagd/demo.flagd.json", "src/flagd/demo.flagd.json"}) {
      Path path = Path.of(candidate);
      if (Files.exists(path)) {
        return path;
      }
    }
    throw new IllegalStateException("demo.flagd.json fixture not found (expected at ../flagd)");
  }

  /** A fresh copy of the fixture inside the given directory. */
  static Path copyTo(Path dir) {
    try {
      Path target = dir.resolve("demo.flagd.json");
      Files.copy(FIXTURE, target, StandardCopyOption.REPLACE_EXISTING);
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Storage file shared by every Spring test (all of them use one cached application context). */
  static synchronized Path sharedStorageFile() {
    if (sharedStorageFile == null) {
      try {
        Path dir = Files.createTempDirectory("flagd-ui-test");
        dir.toFile().deleteOnExit();
        sharedStorageFile = copyTo(dir);
        sharedStorageFile.toFile().deleteOnExit();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return sharedStorageFile;
  }
}
