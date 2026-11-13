/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.chatbot.opamp;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpAMP client with the behaviour of the Python {@code OpAMPAgent} + {@code
 * HealthReportingOpAMPClient}: a full-state message on connect (retried with exponential backoff),
 * a heartbeat every 30 seconds once connected, a full-state report when the server asks for it and
 * an {@code AgentDisconnect} message on shutdown. The agent only reports status and health; it does
 * not accept remote configuration.
 */
public final class OpampAgent {

  private static final Logger log = LoggerFactory.getLogger(OpampAgent.class);

  static final String USER_AGENT = "OTel-OpAMP-Java/1.0";
  private static final int MAX_RETRIES = 10;
  private static final int HEARTBEAT_MAX_RETRIES = 1;
  private static final double INITIAL_BACKOFF_SECONDS = 1.0;

  /** Sends one OpAMP message over the wire and returns the server's response payload. */
  @FunctionalInterface
  public interface Transport {
    byte[] send(byte[] data, String userAgent) throws IOException;
  }

  private static final class Job {
    final byte[] payload;
    final int maxRetries;
    final Runnable callback;
    int attempt;

    Job(byte[] payload, int maxRetries, Runnable callback) {
      this.payload = payload;
      this.maxRetries = maxRetries;
      this.callback = callback;
    }

    boolean shouldRetry() {
      return attempt <= maxRetries;
    }

    double delaySeconds(double initialBackoff) {
      return initialBackoff * Math.pow(2, attempt - 1) * ThreadLocalRandom.current().nextDouble(0.8, 1.2);
    }
  }

  private final Transport transport;
  private final byte[] instanceUid;
  private final byte[] agentDescription;
  private final long startTimeUnixNano;
  private final Duration interval;
  private final double initialBackoff;
  private final AtomicLong sequenceNum = new AtomicLong();
  private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private volatile boolean scheduleHeartbeats;
  private Thread worker;
  private Thread scheduler;

  public OpampAgent(
      Transport transport,
      Map<String, Object> identifyingAttributes,
      Map<String, Object> nonIdentifyingAttributes,
      Duration interval) {
    this(transport, identifyingAttributes, nonIdentifyingAttributes, interval, INITIAL_BACKOFF_SECONDS);
  }

  OpampAgent(
      Transport transport,
      Map<String, Object> identifyingAttributes,
      Map<String, Object> nonIdentifyingAttributes,
      Duration interval,
      double initialBackoff) {
    this.transport = transport;
    this.instanceUid = OpampMessages.uuid7(System.currentTimeMillis());
    this.agentDescription =
        OpampMessages.agentDescription(identifyingAttributes, nonIdentifyingAttributes);
    this.startTimeUnixNano = nowNanos();
    this.interval = interval;
    this.initialBackoff = initialBackoff;
  }

  public void start() {
    worker = new Thread(this::runWorker, "OpAMPAgentWorker");
    worker.setDaemon(true);
    scheduler = new Thread(this::runScheduler, "OpAMPAgentScheduler");
    scheduler.setDaemon(true);
    worker.start();
    scheduler.start();
    // connection message; heartbeats are enabled once it went through
    queue.add(new Job(buildFullState(), MAX_RETRIES, () -> scheduleHeartbeats = true));
  }

  /** Sends AgentDisconnect (best effort), then stops the worker threads. */
  public void stop(Duration timeout) {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    try {
      send(buildDisconnect());
    } catch (IOException | RuntimeException e) {
      log.debug("Stopping OpAMPAgent: failed to send AgentDisconnect message");
    }
    for (Thread t : new Thread[] {worker, scheduler}) {
      if (t == null) {
        continue;
      }
      t.interrupt();
      try {
        t.join(timeout.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  // ---- message construction (sequence_num is the value at build time, as in the Python client) --

  byte[] buildFullState() {
    return OpampMessages.fullState(instanceUid, sequenceNum.get(), agentDescription, health());
  }

  byte[] buildHeartbeat() {
    return OpampMessages.heartbeat(instanceUid, sequenceNum.get(), health());
  }

  byte[] buildDisconnect() {
    return OpampMessages.disconnect(instanceUid, sequenceNum.get());
  }

  private byte[] health() {
    return OpampMessages.health(startTimeUnixNano, nowNanos());
  }

  private static long nowNanos() {
    java.time.Instant now = java.time.Instant.now();
    return now.getEpochSecond() * 1_000_000_000L + now.getNano();
  }

  private byte[] send(byte[] payload) throws IOException {
    try {
      return transport.send(payload, USER_AGENT);
    } finally {
      sequenceNum.incrementAndGet();
    }
  }

  // ---- worker / scheduler ----------------------------------------------------------------------

  private void runScheduler() {
    while (!stopped.get()) {
      try {
        Thread.sleep(interval.toMillis());
      } catch (InterruptedException e) {
        return;
      }
      if (scheduleHeartbeats && !stopped.get()) {
        queue.add(new Job(buildHeartbeat(), HEARTBEAT_MAX_RETRIES, null));
      }
    }
  }

  private void runWorker() {
    while (!stopped.get()) {
      Job job;
      try {
        job = queue.poll(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        return;
      }
      if (job == null) {
        continue;
      }
      byte[] response = null;
      while (job.shouldRetry() && !stopped.get()) {
        try {
          response = send(job.payload);
          break;
        } catch (IOException | RuntimeException e) {
          job.attempt++;
          log.warn("Job failed attempt {}/{}: {}", job.attempt, job.maxRetries + 1, e.toString());
          if (!job.shouldRetry()) {
            log.error("Job dropped after max retries");
            break;
          }
          try {
            Thread.sleep((long) (job.delaySeconds(initialBackoff) * 1000));
          } catch (InterruptedException ie) {
            return;
          }
        }
      }
      if (response != null) {
        processMessage(response);
      }
      if (job.callback != null) {
        try {
          job.callback.run();
        } catch (RuntimeException e) {
          log.warn("Callback for job failed: {}", e.toString());
        }
      }
    }
  }

  private void processMessage(byte[] response) {
    try {
      OpampMessages.ServerToAgent message = OpampMessages.parseServerToAgent(response);
      if (message.hasErrorResponse()) {
        return;
      }
      if ((message.flags() & OpampMessages.FLAG_REPORT_FULL_STATE) != 0) {
        log.debug("Server requested full state report");
        queue.add(new Job(buildFullState(), MAX_RETRIES, null));
      }
    } catch (RuntimeException e) {
      log.warn("Could not parse OpAMP server response: {}", e.toString());
    }
  }
}
