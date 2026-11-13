/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Launches one headless Chromium per browser user (like the original's {@code headless = True}
 * PlaywrightUser, "each one keeps its browser process alive for its whole lifetime"). Playwright for Java
 * objects are bound to the thread that created them, so every user creates its own Playwright instance.
 * Browsers are located via {@code PLAYWRIGHT_BROWSERS_PATH} (installed in the Dockerfile).
 */
public class PlaywrightBrowserSessionFactory implements BrowserSessionFactory {

  @Override
  public BrowserSession open(String baseUrl) {
    Playwright playwright = Playwright.create();
    try {
      Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
      BrowserContext context = browser.newContext(new Browser.NewContextOptions().setBaseURL(baseUrl));
      return new BrowserSession() {
        @Override
        public Page newPage() {
          return context.newPage();
        }

        @Override
        public void close() {
          try {
            browser.close();
          } finally {
            playwright.close();
          }
        }
      };
    } catch (RuntimeException e) {
      playwright.close();
      throw e;
    }
  }
}
