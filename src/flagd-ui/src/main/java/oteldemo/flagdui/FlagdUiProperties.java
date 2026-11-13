/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the flagd UI.
 *
 * @param storageFilePath flagd configuration file read and rewritten by the UI (the original
 *     hard-coded {@code /app/data/demo.flagd.json} in production)
 * @param rootPath public path prefix under which the front proxy exposes the UI (the original
 *     Phoenix {@code url: [path: "/feature"]}); it only affects generated links, the application
 *     itself always serves from {@code /}
 */
@ConfigurationProperties("flagd-ui")
public record FlagdUiProperties(String storageFilePath, String rootPath) {

  public FlagdUiProperties {
    if (storageFilePath == null || storageFilePath.isBlank()) {
      storageFilePath = "/app/data/demo.flagd.json";
    }
    if (rootPath == null || rootPath.isBlank()) {
      rootPath = "/feature";
    }
  }

  /** Root path as used by templates: never ends with a slash, empty when served at "/". */
  public String linkPrefix() {
    String path = rootPath.trim();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }
}
