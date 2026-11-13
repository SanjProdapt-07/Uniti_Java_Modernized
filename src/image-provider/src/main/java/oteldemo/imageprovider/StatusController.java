/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.imageprovider;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Equivalent of nginx {@code location /status { stub_status on; }}; used by the compose healthcheck. */
@RestController
class StatusController {

  private final StubStatus stubStatus;

  StatusController(StubStatus stubStatus) {
    this.stubStatus = stubStatus;
  }

  @GetMapping("/status")
  ResponseEntity<String> status() {
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(stubStatus.render());
  }
}
