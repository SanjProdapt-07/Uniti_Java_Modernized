/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot.opamp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * OpAMP over a WebSocket (the demo's OpAMP server speaks WebSocket on {@code /v1/opamp}): every
 * message is a binary frame consisting of a zero varint header byte and the protobuf payload; the
 * server answers each message with one binary frame in the same format.
 */
public final class OpampWebSocketTransport {

  private final URI uri;
  private final Duration timeout;
  private final HttpClient httpClient;
  private WebSocket socket;
  private Listener listener;

  public OpampWebSocketTransport(String endpoint, Duration timeout, boolean skipTlsVerification) {
    this.uri = toWebSocketUri(endpoint);
    this.timeout = timeout;
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout);
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
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {trustAll}, new SecureRandom());
        SSLParameters params = new SSLParameters();
        params.setEndpointIdentificationAlgorithm("");
        builder.sslContext(ctx).sslParameters(params);
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Cannot disable TLS verification", e);
      }
    }
    this.httpClient = builder.build();
  }

  static URI toWebSocketUri(String endpoint) {
    URI u = URI.create(endpoint);
    String scheme =
        switch (String.valueOf(u.getScheme())) {
          case "https" -> "wss";
          case "http" -> "ws";
          default -> u.getScheme();
        };
    return URI.create(scheme + ":" + u.getRawSchemeSpecificPart()
        + (u.getRawFragment() == null ? "" : "#" + u.getRawFragment()));
  }

  /** Sends one message and returns the server's response payload (without the header byte). */
  public synchronized byte[] send(byte[] data, String userAgent) throws IOException {
    try {
      if (socket == null || socket.isInputClosed() || socket.isOutputClosed()) {
        listener = new Listener();
        socket =
            httpClient
                .newWebSocketBuilder()
                .header("User-Agent", userAgent)
                .connectTimeout(timeout)
                .buildAsync(uri, listener)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      }
      CompletableFuture<byte[]> response = listener.expectResponse();
      byte[] framed = new byte[data.length + 1];
      System.arraycopy(data, 0, framed, 1, data.length);
      socket.sendBinary(ByteBuffer.wrap(framed), true).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      byte[] reply = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return reply.length > 0 && reply[0] == 0 ? java.util.Arrays.copyOfRange(reply, 1, reply.length) : reply;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      reset();
      throw new IOException("interrupted", e);
    } catch (ExecutionException | TimeoutException e) {
      reset();
      throw new IOException(e.getCause() == null ? e.toString() : e.getCause().toString(), e);
    }
  }

  public synchronized void close() {
    reset();
  }

  private void reset() {
    WebSocket s = socket;
    socket = null;
    if (s != null) {
      try {
        s.abort();
      } catch (RuntimeException ignored) {
        // already closed
      }
    }
  }

  private static final class Listener implements WebSocket.Listener {
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private volatile CompletableFuture<byte[]> pending = new CompletableFuture<>();

    CompletableFuture<byte[]> expectResponse() {
      pending = new CompletableFuture<>();
      return pending;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      byte[] chunk = new byte[data.remaining()];
      data.get(chunk);
      partial.write(chunk, 0, chunk.length);
      if (last) {
        byte[] full = partial.toByteArray();
        partial.reset();
        pending.complete(full);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      pending.completeExceptionally(
          new IOException("OpAMP server returned a non-binary WebSocket message"));
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      pending.completeExceptionally(new IOException("WebSocket closed: " + statusCode + " " + reason));
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      pending.completeExceptionally(error);
    }
  }
}
