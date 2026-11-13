/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

/** Opens connections to Valkey; one attempt per call. */
@FunctionalInterface
public interface ValkeyLinkFactory {

  ValkeyLink connect() throws Exception;
}
