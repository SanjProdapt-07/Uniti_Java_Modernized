/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The OpAMP instance identity (prepareOpAMPIdentity in opamp.go): when an OpAMP server endpoint is
 * configured, a random 16 byte instance UID is generated, and unless a {@code
 * service.instance.id} resource attribute is configured already, the hex form of that UID becomes
 * the {@code service.instance.id} of the telemetry resource, so that the OpAMP agent and the
 * telemetry it describes share one identity.
 *
 * @param instanceUid the OpAMP instance UID
 * @param additionalResourceAttributes the resource attributes to add: {@code service.instance.id}
 *     or nothing when it was configured
 */
record OpampIdentity(byte[] instanceUid, Map<String, String> additionalResourceAttributes) {

  static final String SERVICE_INSTANCE_ID = "service.instance.id";

  private static final SecureRandom RANDOM = new SecureRandom();

  static byte[] randomInstanceUid() {
    byte[] uid = new byte[16];
    RANDOM.nextBytes(uid);
    return uid;
  }

  /**
   * @param endpoint {@code OPAMP_SERVER_ENDPOINT}; blank disables OpAMP
   * @param configuredServiceInstanceId the {@code service.instance.id} already configured for the
   *     resource (from {@code OTEL_RESOURCE_ATTRIBUTES} or {@code otel.resource.attributes.*}), or
   *     null/blank
   * @param uids source of the instance UID
   * @return the identity, or empty when OpAMP is not enabled
   */
  static Optional<OpampIdentity> prepare(
      String endpoint, String configuredServiceInstanceId, Supplier<byte[]> uids) {
    if (endpoint == null || endpoint.isEmpty()) {
      return Optional.empty();
    }
    byte[] uid = uids.get();
    if (configuredServiceInstanceId != null && !configuredServiceInstanceId.isEmpty()) {
      return Optional.of(new OpampIdentity(uid, Map.of()));
    }
    return Optional.of(
        new OpampIdentity(uid, Map.of(SERVICE_INSTANCE_ID, HexFormat.of().formatHex(uid))));
  }

  /** The {@code service.instance.id} in an {@code OTEL_RESOURCE_ATTRIBUTES} style list. */
  static String serviceInstanceIdFrom(String resourceAttributes) {
    if (resourceAttributes == null) {
      return null;
    }
    for (String pair : resourceAttributes.split(",")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).strip().equals(SERVICE_INSTANCE_ID)) {
        return pair.substring(eq + 1).strip();
      }
    }
    return null;
  }
}
