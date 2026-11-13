/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.cart;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal RESP2 server, just enough of Valkey for the cart store (PING, AUTH, SET, GET, HGET,
 * HSET, EXPIRE) to run the real Lettuce client code paths in tests. It is NOT Valkey: it proves
 * the wire level usage of the client, not the behavior of a real server.
 */
final class FakeValkeyServer implements AutoCloseable {

  private final ServerSocket server;
  private final Map<String, byte[]> strings = new ConcurrentHashMap<>();
  private final Map<String, Map<String, byte[]>> hashes = new ConcurrentHashMap<>();
  private final Map<String, Long> expiries = new ConcurrentHashMap<>();
  private final List<List<String>> commands = new CopyOnWriteArrayList<>();
  private final List<Socket> clients = new CopyOnWriteArrayList<>();
  private volatile String requiredPassword;

  FakeValkeyServer() throws IOException {
    this.server = new ServerSocket(0);
    Thread acceptor = new Thread(this::acceptLoop, "fake-valkey-accept");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  int port() {
    return server.getLocalPort();
  }

  void requirePassword(String password) {
    this.requiredPassword = password;
  }

  /** The commands received so far, as name followed by arguments (values as UTF-8 text). */
  List<List<String>> commands() {
    return commands;
  }

  Map<String, byte[]> hash(String key) {
    return hashes.get(key);
  }

  Long expirySeconds(String key) {
    return expiries.get(key);
  }

  /** Drops every client connection, as a network failure would. */
  void dropConnections() {
    for (Socket client : clients) {
      try {
        client.close();
      } catch (IOException ignored) {
        // nothing to do
      }
    }
    clients.clear();
  }

  @Override
  public void close() throws IOException {
    dropConnections();
    server.close();
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        Socket socket = server.accept();
        clients.add(socket);
        Thread handler = new Thread(() -> serve(socket), "fake-valkey-client");
        handler.setDaemon(true);
        handler.start();
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket socket) {
    try (socket) {
      InputStream in = new BufferedInputStream(socket.getInputStream());
      OutputStream out = socket.getOutputStream();
      boolean authenticated = requiredPassword == null;
      while (true) {
        List<byte[]> args = readCommand(in);
        if (args == null) {
          return;
        }
        List<String> text = new ArrayList<>();
        for (byte[] arg : args) {
          text.add(new String(arg, StandardCharsets.ISO_8859_1));
        }
        commands.add(text);
        String name = text.get(0).toUpperCase();
        if (name.equals("AUTH")) {
          String password = text.get(text.size() - 1);
          if (password.equals(requiredPassword)) {
            authenticated = true;
            out.write("+OK\r\n".getBytes(StandardCharsets.UTF_8));
          } else {
            out.write("-WRONGPASS invalid password\r\n".getBytes(StandardCharsets.UTF_8));
          }
          continue;
        }
        if (!authenticated && !name.equals("HELLO") && !name.equals("CLIENT")) {
          out.write("-NOAUTH Authentication required.\r\n".getBytes(StandardCharsets.UTF_8));
          continue;
        }
        out.write(handle(name, args));
        out.flush();
      }
    } catch (IOException e) {
      // connection closed
    }
  }

  private byte[] handle(String name, List<byte[]> args) {
    switch (name) {
      case "PING":
        return "+PONG\r\n".getBytes(StandardCharsets.UTF_8);
      case "SET":
        strings.put(key(args, 1), args.get(2));
        return "+OK\r\n".getBytes(StandardCharsets.UTF_8);
      case "GET":
        return bulk(strings.get(key(args, 1)));
      case "HSET":
        hashes
            .computeIfAbsent(key(args, 1), k -> new ConcurrentHashMap<>())
            .put(key(args, 2), args.get(3));
        return ":1\r\n".getBytes(StandardCharsets.UTF_8);
      case "HGET":
        Map<String, byte[]> hash = hashes.get(key(args, 1));
        return bulk(hash == null ? null : hash.get(key(args, 2)));
      case "EXPIRE":
        expiries.put(key(args, 1), Long.parseLong(key(args, 2)));
        return ":1\r\n".getBytes(StandardCharsets.UTF_8);
      case "PEXPIRE":
        expiries.put(key(args, 1), Long.parseLong(key(args, 2)) / 1000);
        return ":1\r\n".getBytes(StandardCharsets.UTF_8);
      case "CLIENT":
        return "+OK\r\n".getBytes(StandardCharsets.UTF_8);
      case "HELLO":
        // RESP3 is not supported: the client falls back to RESP2
        return "-ERR unknown command 'HELLO'\r\n".getBytes(StandardCharsets.UTF_8);
      default:
        return ("-ERR unknown command '" + name + "'\r\n").getBytes(StandardCharsets.UTF_8);
    }
  }

  private static String key(List<byte[]> args, int index) {
    return new String(args.get(index), StandardCharsets.UTF_8);
  }

  private static byte[] bulk(byte[] value) {
    if (value == null) {
      return "$-1\r\n".getBytes(StandardCharsets.UTF_8);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(("$" + value.length + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.writeBytes(value);
    out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private static List<byte[]> readCommand(InputStream in) throws IOException {
    int first = in.read();
    if (first < 0) {
      return null;
    }
    if (first != '*') {
      throw new IOException("unsupported RESP frame " + (char) first);
    }
    int count = Integer.parseInt(readLine(in));
    List<byte[]> args = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      if (in.read() != '$') {
        throw new IOException("expected bulk string");
      }
      int length = Integer.parseInt(readLine(in));
      byte[] data = in.readNBytes(length);
      in.readNBytes(2);
      args.add(Arrays.copyOf(data, data.length));
    }
    return args;
  }

  private static String readLine(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = in.read()) >= 0) {
      if (c == '\r') {
        in.read();
        break;
      }
      line.append((char) c);
    }
    return line.toString();
  }
}
