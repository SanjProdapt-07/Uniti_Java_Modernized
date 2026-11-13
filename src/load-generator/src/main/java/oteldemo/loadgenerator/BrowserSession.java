/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import com.microsoft.playwright.Page;

/** A browser kept alive for the whole life of one browser user. */
public interface BrowserSession extends AutoCloseable {

  /** Opens a new page whose relative URLs resolve against the load generator host. */
  Page newPage();

  @Override
  void close();
}
