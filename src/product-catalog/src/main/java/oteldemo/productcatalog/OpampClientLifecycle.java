/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.productcatalog;

import io.opentelemetry.opamp.client.OpampClient;
import io.opentelemetry.opamp.client.OpampClientBuilder;
import io.opentelemetry.opamp.client.internal.connectivity.websocket.OkHttpWebSocket;
import io.opentelemetry.opamp.client.internal.request.service.WebSocketRequestService;
import io.opentelemetry.opamp.client.internal.response.MessageData;
import io.opentelemetry.opamp.client.request.service.RequestService;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.BiFunction;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import opamp.proto.ComponentHealth;
import opamp.proto.ServerErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Reports the health of this agent to the demo's OpAMP server over WebSocket, like startOpAMPClient
 * in opamp.go. It is only active when {@code OPAMP_SERVER_ENDPOINT} is set, and never prevents the
 * service from starting: failures are logged.
 */
@Component
@ConditionalOnExpression("!'${opamp.server.endpoint:}'.isEmpty()")
class OpampClientLifecycle implements SmartLifecycle {

  private static final Logger logger = LoggerFactory.getLogger(OpampClientLifecycle.class);

  // like the Go code: these resource attributes identify the agent, all others only describe it
  private static final Set<String> IDENTIFYING = Set.of("service.name", "service.instance.id", "service.namespace");

  private final String endpoint;
  private final String tlsInsecureSkipVerify;
  private final String instanceUidHex;
  private final Attributes resourceAttributes;
  private final BiFunction<String, Boolean, RequestService> requestServices;

  private volatile OpampClient client;

  @Autowired
  OpampClientLifecycle(
      @Value("${opamp.server.endpoint}") String endpoint,
      @Value("${opamp.server.tls-insecure-skip-verify:false}") String tlsInsecureSkipVerify,
      @Value("${opamp.instance-uid:}") String instanceUidHex,
      // OpenTelemetry is injected so that the SDK (and with it the resource) exists first
      OpenTelemetry openTelemetry,
      ResourceCapture resource) {
    this(
        endpoint,
        tlsInsecureSkipVerify,
        instanceUidHex,
        resource.attributes(),
        OpampClientLifecycle::webSocketRequestService);
  }

  OpampClientLifecycle(
      String endpoint,
      String tlsInsecureSkipVerify,
      String instanceUidHex,
      Attributes resourceAttributes,
      BiFunction<String, Boolean, RequestService> requestServices) {
    this.endpoint = endpoint;
    this.tlsInsecureSkipVerify = tlsInsecureSkipVerify;
    this.instanceUidHex = instanceUidHex;
    this.resourceAttributes = resourceAttributes;
    this.requestServices = requestServices;
  }

  @Override
  public void start() {
    try {
      client = startClient();
      logger.info("Started OpAMP client");
    } catch (RuntimeException e) {
      logger.error("Failed to start OpAMP client: {}", e.toString());
    }
  }

  OpampClient startClient() {
    // an unparsable value is an error of the OpAMP client only, as in the Go service
    boolean skipTlsVerification = parseBoolean(tlsInsecureSkipVerify);
    byte[] uid =
        instanceUidHex == null || instanceUidHex.isEmpty()
            ? OpampIdentity.randomInstanceUid()
            : HexFormat.of().parseHex(instanceUidHex);

    OpampClientBuilder builder =
        OpampClient.builder().setRequestService(requestServices.apply(endpoint, skipTlsVerification)).setInstanceUid(uid);
    resourceAttributes.forEach(
        (key, value) -> {
          String text = String.valueOf(value);
          if (IDENTIFYING.contains(key.getKey())) {
            builder.putIdentifyingAttribute(key.getKey(), text);
          } else {
            builder.putNonIdentifyingAttribute(key.getKey(), text);
          }
        });
    // healthy, started now; this also announces the ReportsHealth capability
    builder.enableHealthReporting(
        new ComponentHealth.Builder()
            .healthy(true)
            .start_time_unix_nano(nowNanos())
            .build());

    return builder.build(new LoggingCallbacks());
  }

  @Override
  public void stop() {
    OpampClient current = client;
    client = null;
    if (current == null) {
      return;
    }
    try {
      current.close();
      logger.info("Stopped OpAMP client");
    } catch (IOException | RuntimeException e) {
      logger.error("Error stopping OpAMP client: {}", e.toString());
    }
  }

  @Override
  public boolean isRunning() {
    return client != null;
  }

  private static long nowNanos() {
    Instant now = Instant.now();
    return now.getEpochSecond() * 1_000_000_000L + now.getNano();
  }

  private static boolean parseBoolean(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    // strconv.ParseBool
    return switch (value) {
      case "1", "t", "T", "TRUE", "true", "True" -> true;
      case "0", "f", "F", "FALSE", "false", "False" -> false;
      default -> throw new IllegalArgumentException(
          "parse OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY: invalid syntax: \"" + value + "\"");
    };
  }

  /** WebSocket transport; with the Go option InsecureSkipVerify when asked for (self-signed demo certificate). */
  private static RequestService webSocketRequestService(String url, boolean skipTlsVerification) {
    OkHttpClient.Builder http = new OkHttpClient.Builder();
    if (skipTlsVerification) {
      try {
        X509TrustManager trustAll =
            new X509TrustManager() {
              @Override
              public void checkClientTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public void checkServerTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
              }
            };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustAll}, null);
        HostnameVerifier any = (hostname, session) -> true;
        http.sslSocketFactory(context.getSocketFactory(), trustAll).hostnameVerifier(any);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("cannot configure OpAMP TLS", e);
      }
    }
    return WebSocketRequestService.create(OkHttpWebSocket.create(url, http.build()));
  }

  private static final class LoggingCallbacks implements OpampClient.Callbacks {

    @Override
    public void onConnect(OpampClient client) {
      logger.debug("OpAMP connected");
    }

    @Override
    public void onConnectFailed(OpampClient client, Throwable throwable) {
      logger.error("OpAMP connection failed: {}", String.valueOf(throwable));
    }

    @Override
    public void onErrorResponse(OpampClient client, ServerErrorResponse errorResponse) {
      logger.error("OpAMP server error response: {}", errorResponse.error_message);
    }

    @Override
    public void onMessage(OpampClient client, MessageData messageData) {
      logger.debug("OpAMP message received");
    }
  }
}
