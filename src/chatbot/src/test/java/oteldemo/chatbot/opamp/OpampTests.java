/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot.opamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Wire format expectations were generated with the Python opentelemetry-opamp-client protobuf
 * classes the original chatbot used.
 */
class OpampTests {

  private static final byte[] UID = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
  private static final long START = 1_700_000_000_000_000_000L;
  private static final long STATUS = 1_700_000_001_000_000_000L;

  private static byte[] description() {
    Map<String, Object> identifying = new LinkedHashMap<>();
    identifying.put("service.name", "chatbot");
    identifying.put("service.instance.id", "abc");
    Map<String, Object> other = new LinkedHashMap<>();
    other.put("service.version", "2.2.0");
    other.put("x.int", 42);
    other.put("x.bool", true);
    other.put("x.double", 1.5);
    other.put("x.empty", "");
    return OpampMessages.agentDescription(identifying, other);
  }

  @Test
  void capabilitiesAreStatusHealthAndHeartbeat() {
    assertThat(OpampMessages.CAPABILITIES).isEqualTo(10241L);
  }

  @Test
  void fullStateMatchesPythonSerialization() {
    byte[] bytes =
        OpampMessages.fullState(UID, 3, description(), OpampMessages.health(START, STATUS));

    assertThat(HexFormat.of().formatHex(bytes))
        .isEqualTo(
            "0a100102030405060708090a0b0c0d0e0f1010031a96010a190a0c736572766963652e6e616d6512090a0763686174626f740a1c0a13736572766963652e696e7374616e63652e696412050a03616263121a0a0f736572766963652e76657273696f6e12070a05322e322e30120b0a05782e696e741202182a120c0a06782e626f6f6c1202100112150a08782e646f75626c65120921000000000000f83f120d0a07782e656d70747912020a002081502a1808011100002a36fe9c971722024f4b2900cac471fe9c9717");
  }

  @Test
  void heartbeatMatchesPythonSerialization() {
    byte[] bytes = OpampMessages.heartbeat(UID, 0, OpampMessages.health(START, STATUS));

    assertThat(HexFormat.of().formatHex(bytes))
        .isEqualTo(
            "0a100102030405060708090a0b0c0d0e0f102081502a1808011100002a36fe9c971722024f4b2900cac471fe9c9717");
  }

  @Test
  void disconnectMatchesPythonSerialization() {
    assertThat(HexFormat.of().formatHex(OpampMessages.disconnect(UID, 7)))
        .isEqualTo("0a100102030405060708090a0b0c0d0e0f1010072081504a00");
  }

  @Test
  void serverToAgentFlagsAndErrorAreParsed() {
    byte[] reportFullState = HexFormat.of().parseHex("0a100102030405060708090a0b0c0d0e0f1030013805");
    byte[] error = HexFormat.of().parseHex("0a100102030405060708090a0b0c0d0e0f101203120178");

    assertThat(OpampMessages.parseServerToAgent(reportFullState))
        .isEqualTo(new OpampMessages.ServerToAgent(false, 1));
    assertThat(OpampMessages.parseServerToAgent(error))
        .isEqualTo(new OpampMessages.ServerToAgent(true, 0));
  }

  @Test
  void uuid7HasVersionAndVariantBits() {
    byte[] u = OpampMessages.uuid7(1_700_000_000_000L);
    assertThat(u).hasSize(16);
    assertThat(u[6] >> 4 & 0xf).isEqualTo(7);
    assertThat(u[8] >> 6 & 0x3).isEqualTo(2);
    long ts = 0;
    for (int i = 0; i < 6; i++) {
      ts = (ts << 8) | (u[i] & 0xff);
    }
    assertThat(ts).isEqualTo(1_700_000_000_000L);
  }

  @Test
  void webSocketUrlSchemeIsMapped() {
    assertThat(OpampWebSocketTransport.toWebSocketUri("https://opamp-server:4320/v1/opamp").toString())
        .isEqualTo("wss://opamp-server:4320/v1/opamp");
    assertThat(OpampWebSocketTransport.toWebSocketUri("http://h:1/x").toString()).isEqualTo("ws://h:1/x");
  }

  // ---- agent behaviour -------------------------------------------------------------------------

  private static final class FakeServer implements OpampAgent.Transport {
    final List<byte[]> received = new CopyOnWriteArrayList<>();
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    final AtomicInteger failures = new AtomicInteger();
    volatile byte[] response = new byte[0];

    @Override
    public byte[] send(byte[] data, String userAgent) throws IOException {
      if (failures.getAndDecrement() > 0) {
        throw new IOException("connection refused");
      }
      received.add(data);
      userAgents.add(userAgent);
      return response;
    }
  }

  private static OpampAgent agent(FakeServer server, Duration interval) {
    return new OpampAgent(server, Map.of("service.name", "chatbot"), Map.of("k", "v"), interval, 0.01);
  }

  @Test
  void connectsWithFullStateThenHeartbeatsAndSendsDisconnectOnStop() {
    FakeServer server = new FakeServer();
    OpampAgent agent = agent(server, Duration.ofMillis(100));

    agent.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> server.received.size() >= 3);
    agent.stop(Duration.ofSeconds(2));

    byte[] first = server.received.get(0);
    assertThat(first[18]).isEqualTo((byte) 0x1a); // field 3: agent_description present
    byte[] second = server.received.get(1);
    assertThat(second[18]).isEqualTo((byte) 0x10); // sequence_num tag
    assertThat(second[19]).isEqualTo((byte) 1);
    byte[] last = server.received.get(server.received.size() - 1);
    assertThat(last[last.length - 2]).isEqualTo((byte) 0x4a); // agent_disconnect (field 9)
    assertThat(last[last.length - 1]).isEqualTo((byte) 0);
    assertThat(server.userAgents).allMatch(ua -> ua.startsWith("OTel-OpAMP"));
  }

  @Test
  void connectionMessageIsRetriedWithBackoff() {
    FakeServer server = new FakeServer();
    server.failures.set(3);
    OpampAgent agent = agent(server, Duration.ofSeconds(30));

    agent.start();
    await().atMost(Duration.ofSeconds(10)).until(() -> !server.received.isEmpty());
    agent.stop(Duration.ofSeconds(2));

    // the payload is built once (sequence 0, so no sequence_num field) and re-sent after the failures
    byte[] first = server.received.get(0);
    assertThat(first[18]).isEqualTo((byte) 0x1a); // agent_description follows the instance uid
  }

  @Test
  void serverRequestForFullStateTriggersAnotherFullStateReport() {
    FakeServer server = new FakeServer();
    server.response = HexFormat.of().parseHex("0a100102030405060708090a0b0c0d0e0f1030013805");
    OpampAgent agent = agent(server, Duration.ofSeconds(30));

    agent.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> server.received.size() >= 2);
    server.response = new byte[0];
    agent.stop(Duration.ofSeconds(2));

    // both leading messages carry an agent_description (field 3)
    assertThat(server.received.get(0)).contains((byte) 0x1a);
    assertThat(containsField3(server.received.get(1))).isTrue();
  }

  private static boolean containsField3(byte[] message) {
    // after uid (bytes 0..17) comes optional sequence varint then tag 0x1a (field 3)
    int pos = 18;
    if (message[pos] == 0x10) {
      pos += 2;
    }
    return message[pos] == 0x1a;
  }

  @Test
  void booleanEnvParsingMatchesTheOriginal() {
    assertThat(OpampLifecycle.parseBool("K", null)).isFalse();
    assertThat(OpampLifecycle.parseBool("K", "")).isFalse();
    for (String v : List.of("true", "1", "yes", "y", "on", "TRUE", "On")) {
      assertThat(OpampLifecycle.parseBool("K", v)).as(v).isTrue();
    }
    for (String v : List.of("false", "0", "no", "n", "off")) {
      assertThat(OpampLifecycle.parseBool("K", v)).as(v).isFalse();
    }
    assertThatThrownBy(() -> OpampLifecycle.parseBool("OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY", "maybe"))
        .hasMessage("OPAMP_SERVER_TLS_INSECURE_SKIP_VERIFY must be a boolean");
  }
}
