/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.opamp.client.OpampClient;
import io.opentelemetry.opamp.client.OpampClientBuilder;
import io.opentelemetry.opamp.client.internal.connectivity.websocket.OkHttpWebSocket;
import io.opentelemetry.opamp.client.internal.request.service.WebSocketRequestService;
import io.opentelemetry.opamp.client.internal.response.MessageData;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Starts the OpAMP client when {@code OPAMP_SERVER_ENDPOINT} is set (the {@code OpAmpClientSetup}
 * of the original): a WebSocket connection to the OpAMP server that reports the telemetry
 * resource as the agent description. A failure to start is logged and ignored.
 */
final class OpAmpClientManager implements DisposableBean {

  private static final Logger logger = LoggerFactory.getLogger(OpAmpClientManager.class);

  private static final Set<String> IDENTIFYING_KEYS =
      Set.of("service.name", "service.instance.id", "service.namespace");

  private OpampClient client;

  OpAmpClientManager(String endpoint, Resource resource, boolean skipTlsCertificateVerification) {
    if (endpoint == null || endpoint.isBlank()) {
      return;
    }
    try {
      client = start(endpoint, resource, skipTlsCertificateVerification);
    } catch (Exception | LinkageError e) {
      logger.warn("Failed to start OpAMP client", e);
      client = null;
    }
  }

  boolean isRunning() {
    return client != null;
  }

  private static OpampClient start(
      String endpoint, Resource resource, boolean skipTlsCertificateVerification)
      throws Exception {
    OkHttpClient httpClient =
        skipTlsCertificateVerification ? insecureClient() : new OkHttpClient.Builder().build();

    OpampClientBuilder builder =
        OpampClient.builder()
            .setRequestService(
                WebSocketRequestService.create(OkHttpWebSocket.create(endpoint, httpClient)));
    addAttributes(builder, resource);
    return builder.build(new LoggingCallbacks());
  }

  /** Identifying attributes are service name, instance id and namespace; the rest is not. */
  static void addAttributes(OpampClientBuilder builder, Resource resource) {
    resource
        .getAttributes()
        .forEach(
            (AttributeKey<?> key, Object value) -> {
              if (value == null) {
                return;
              }
              String text = String.valueOf(value);
              if (IDENTIFYING_KEYS.contains(key.getKey())) {
                builder.putIdentifyingAttribute(key.getKey(), text);
              } else {
                builder.putNonIdentifyingAttribute(key.getKey(), text);
              }
            });
  }

  private static OkHttpClient insecureClient() throws Exception {
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
    context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
    return new OkHttpClient.Builder()
        .sslSocketFactory(context.getSocketFactory(), trustAll)
        .hostnameVerifier((hostname, session) -> true)
        .build();
  }

  @Override
  public void destroy() throws IOException {
    OpampClient current = client;
    client = null;
    if (current != null) {
      current.close();
    }
  }

  private static final class LoggingCallbacks implements OpampClient.Callbacks {
    @Override
    public void onConnect(OpampClient opampClient) {
      logger.debug("OpAMP client connected");
    }

    @Override
    public void onConnectFailed(OpampClient opampClient, Throwable throwable) {
      logger.debug("OpAMP client connection failed", throwable);
    }

    @Override
    public void onErrorResponse(
        OpampClient opampClient, opamp.proto.ServerErrorResponse errorResponse) {
      logger.debug("OpAMP server error response: {}", errorResponse);
    }

    @Override
    public void onMessage(OpampClient opampClient, MessageData messageData) {
      logger.debug("OpAMP message received");
    }
  }
}
