/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package oteldemo.loadgenerator;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/** Helper for {@code with tracer.start_as_current_span(...)} blocks. */
final class UserTelemetry {

  private UserTelemetry() {}

  static void inSpan(Tracer tracer, String name, Attributes attributes, Runnable body) {
    inSpan(tracer.spanBuilder(name).setAllAttributes(attributes), body);
  }

  static void inSpan(SpanBuilder builder, Runnable body) {
    Span span = builder.startSpan();
    try (Scope ignored = span.makeCurrent()) {
      body.run();
    } catch (SimulatedUserStopped stopped) {
      throw stopped;
    } catch (RuntimeException e) {
      // like start_as_current_span: record the exception and mark the span as failed
      span.recordException(e);
      span.setStatus(StatusCode.ERROR, e.getMessage());
      throw e;
    } finally {
      span.end();
    }
  }
}
