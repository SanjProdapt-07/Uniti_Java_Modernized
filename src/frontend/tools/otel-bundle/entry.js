// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
//
// Browser telemetry bundle for the Spring Boot frontend. This is a direct JavaScript port of
// src/frontend/utils/telemetry/FrontendTracer.ts (+ SessionIdProcessor.ts) from the original
// Next.js application. It is bundled once with esbuild (see src/main/resources/static/js/vendor/README.md)
// so that the Java build needs no Node step.
import { context, propagation, trace } from '@opentelemetry/api';
import { CompositePropagator, W3CBaggagePropagator, W3CTraceContextPropagator } from '@opentelemetry/core';
import { BatchSpanProcessor, TracerProvider } from '@opentelemetry/sdk-trace';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { getWebAutoInstrumentations } from '@opentelemetry/auto-instrumentations-web';
import { defaultResource, resourceFromAttributes, detectResources } from '@opentelemetry/resources';
import { browserDetector } from '@opentelemetry/opentelemetry-browser-detector';
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { ZoneContextManager } from '@opentelemetry/context-zone';

const SESSION_ID = 'session.id';
const ENDUSER_ID = 'enduser.id';

class SessionIdProcessor {
  constructor(userId) {
    this.userId = userId;
  }
  forceFlush() {
    return Promise.resolve();
  }
  onStart(span) {
    span.setAttribute(SESSION_ID, this.userId);
    span.setAttribute(ENDUSER_ID, this.userId);
  }
  onEnd() {}
  shutdown() {
    return Promise.resolve();
  }
}

const FrontendTracer = () => {
  const {
    NEXT_PUBLIC_OTEL_SERVICE_NAME = '',
    NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT = '',
    IS_SYNTHETIC_REQUEST = '',
  } = window.ENV || {};
  const session = window.OtelDemoSession.getSession();

  const resource = defaultResource()
    .merge(resourceFromAttributes({ [ATTR_SERVICE_NAME]: NEXT_PUBLIC_OTEL_SERVICE_NAME }))
    .merge(detectResources({ detectors: [browserDetector] }));

  const tracerProvider = new TracerProvider({
    resource,
    spanProcessors: [
      new SessionIdProcessor(session.userId),
      new BatchSpanProcessor({
        exporter: new OTLPTraceExporter({
          url: NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT || 'http://localhost:4318/v1/traces',
        }),
        scheduledDelayMillis: 500,
      }),
    ],
  });

  context.setGlobalContextManager(new ZoneContextManager().enable());
  propagation.setGlobalPropagator(
    new CompositePropagator({
      propagators: [new W3CBaggagePropagator(), new W3CTraceContextPropagator()],
    }),
  );
  trace.setGlobalTracerProvider(tracerProvider);

  registerInstrumentations({
    tracerProvider,
    instrumentations: [
      getWebAutoInstrumentations({
        '@opentelemetry/instrumentation-fetch': {
          propagateTraceHeaderCorsUrls: /.*/,
          clearTimingResources: true,
          applyCustomAttributesOnSpan(span) {
            span.setAttribute('demo.synthetic_request', IS_SYNTHETIC_REQUEST);
          },
        },
      }),
    ],
  });

  // Exposed so app.js can set per-request baggage exactly like Api.gateway.ts does.
  window.OtelDemoTelemetry = { context, propagation, trace };
};

FrontendTracer();
