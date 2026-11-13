/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

/** Opens browser sessions; the production implementation is backed by Playwright for Java. */
@FunctionalInterface
public interface BrowserSessionFactory {

  BrowserSession open(String baseUrl);
}
