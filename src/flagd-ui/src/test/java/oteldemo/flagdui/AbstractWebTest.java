/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.flagdui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full application with a real HTTP port and MockMvc, running against a copy of the demo flagd
 * configuration. Every web test extends this class so that a single application context is shared.
 * The original flagd configuration is restored and the scheduler stopped after each test, as the
 * ExUnit tests did in {@code on_exit}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"otel.sdk.disabled=true", "flagd-ui.root-path=/"})
@AutoConfigureMockMvc
abstract class AbstractWebTest {

  @Autowired protected MockMvc mvc;
  @Autowired protected Storage storage;
  @Autowired protected Scheduler scheduler;

  @LocalServerPort protected int port;

  private String original;

  @DynamicPropertySource
  static void storageProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "flagd-ui.storage-file-path",
        () -> TestFixtures.sharedStorageFile().toAbsolutePath().toString());
  }

  @BeforeEach
  void rememberState() {
    original = FlagJson.encode(storage.read());
  }

  @AfterEach
  void restoreState() {
    scheduler.stopSchedule();
    storage.replace(original);
  }
}
