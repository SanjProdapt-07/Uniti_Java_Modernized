/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Opens Lettuce connections. The options follow the original StackExchange.Redis settings:
 * optional TLS and password, a 5 second connect timeout, keep alive every 180 seconds and
 * reconnects with exponential backoff starting at one second.
 */
public final class LettuceValkeyLinkFactory implements ValkeyLinkFactory {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  static final Duration KEEP_ALIVE = Duration.ofSeconds(180);

  private final ClientResources resources;
  private final RedisURI uri;

  public LettuceValkeyLinkFactory(
      ClientResources resources, String address, String password, boolean tls) {
    ValkeyAddress parsed = ValkeyAddress.parse(address);
    this.resources = resources;
    RedisURI.Builder builder =
        RedisURI.builder()
            .withHost(parsed.host())
            .withPort(parsed.port())
            .withSsl(tls)
            .withTimeout(CONNECT_TIMEOUT);
    if (password != null && !password.isEmpty()) {
      // Set on the URI rather than a connection string, which gets logged.
      builder.withPassword(password.toCharArray());
    }
    this.uri = builder.build();
  }

  public static ClientResources.Builder resourcesBuilder() {
    return ClientResources.builder()
        .reconnectDelay(
            io.lettuce.core.resource.Delay.exponential(
                Duration.ofSeconds(1), Duration.ofSeconds(10), 2, TimeUnit.MILLISECONDS));
  }

  @Override
  public ValkeyLink connect() {
    RedisClient client = RedisClient.create(resources, uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .socketOptions(
                SocketOptions.builder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .keepAlive(
                        SocketOptions.KeepAliveOptions.builder().enable().idle(KEEP_ALIVE).build())
                    .build())
            .build());
    try {
      return new LettuceLink(client, client.connect(ByteArrayValueCodec.INSTANCE));
    } catch (RuntimeException e) {
      client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      throw e;
    }
  }

  private static final class LettuceLink implements ValkeyLink, RedisConnectionStateListener {

    private final RedisClient client;
    private final StatefulRedisConnection<String, byte[]> connection;
    private volatile StateListener listener;

    LettuceLink(RedisClient client, StatefulRedisConnection<String, byte[]> connection) {
      this.client = client;
      this.connection = connection;
      client.addListener(this);
    }

    @Override
    public boolean isOpen() {
      return connection.isOpen();
    }

    @Override
    public RedisCommands<String, byte[]> commands() {
      return connection.sync();
    }

    @Override
    public void setStateListener(StateListener listener) {
      this.listener = listener;
    }

    @Override
    public void close() {
      client.removeListener(this);
      try {
        connection.close();
      } finally {
        client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      }
    }

    @Override
    public void onRedisConnected(RedisChannelHandler<?, ?> connection, java.net.SocketAddress address) {
      StateListener current = listener;
      if (current != null) {
        current.onRestored();
      }
    }

    @Override
    public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
      StateListener current = listener;
      if (current != null) {
        current.onLost();
      }
    }

    @Override
    public void onRedisExceptionCaught(RedisChannelHandler<?, ?> connection, Throwable cause) {
      StateListener current = listener;
      if (current != null) {
        current.onInternalError(cause);
      }
    }
  }
}
