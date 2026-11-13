// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

// Browser-side flagd client. Replaces the @openfeature/react-sdk + @openfeature/flagd-web-provider
// pair of the original app without needing a bundler: it talks to flagd through the Envoy
// "/flagservice" route using the Connect protocol (the transport the web provider uses), with the
// same evaluation context ({targetingKey: userId, userId, currencyCode}), the same bulk
// "ResolveAll" evaluation, the same "EventStream" subscription for configuration changes and the
// same reconnect policy (initial delay 100ms, x2 back-off, 10s cap, 3 retries).
(function () {
  var SERVICE = 'flagd.evaluation.v1.Service';
  var INITIAL_DELAY_MS = 100;
  var BACK_OFF_MULTIPLIER = 2;
  var MAX_DELAY_MS = 10000;
  var MAX_RETRIES = 3;

  var baseUrl = window.location.origin + '/flagservice';
  var flags = {};
  var listeners = [];
  var retry = 0;
  var delayMs = INITIAL_DELAY_MS;
  var abortController = null;

  function evaluationContext() {
    var session = window.OtelDemoSession.getSession();
    return { targetingKey: session.userId, userId: session.userId, currencyCode: session.currencyCode };
  }

  function notify() {
    listeners.slice().forEach(function (listener) {
      try {
        listener();
      } catch (e) {
        console.error(e);
      }
    });
  }

  // Connect JSON for the AnyFlag oneof carries exactly one of these members
  function valueOf(flag) {
    var members = ['boolValue', 'stringValue', 'doubleValue', 'objectValue'];
    for (var i = 0; i < members.length; i++) {
      if (Object.prototype.hasOwnProperty.call(flag, members[i])) {
        return { type: members[i], value: flag[members[i]] };
      }
    }
    return undefined;
  }

  function fetchAll() {
    return fetch(baseUrl + '/' + SERVICE + '/ResolveAll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Connect-Protocol-Version': '1' },
      body: JSON.stringify({ context: evaluationContext() }),
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('flagd ResolveAll failed with status ' + response.status);
        }
        return response.json();
      })
      .then(function (all) {
        var next = {};
        Object.keys(all.flags || {}).forEach(function (key) {
          var resolved = valueOf(all.flags[key]);
          if (resolved) {
            next[key] = resolved;
          }
        });
        flags = next;
        notify();
      });
  }

  function envelope(json) {
    var payload = new TextEncoder().encode(json);
    var out = new Uint8Array(5 + payload.length);
    out[0] = 0;
    new DataView(out.buffer).setUint32(1, payload.length, false);
    out.set(payload, 5);
    return out;
  }

  function onStreamMessage(message) {
    if (message.type === 'provider_ready') {
      fetchAll().then(function () {
        retry = 0;
        delayMs = INITIAL_DELAY_MS;
      }).catch(function (e) {
        console.warn('flagd: could not resolve flags', e);
      });
    } else if (message.type === 'configuration_change') {
      fetchAll().catch(function (e) {
        console.warn('flagd: could not resolve flags', e);
      });
    }
  }

  function scheduleReconnect(reason) {
    console.warn('flagd: could not establish connection, ' + (reason && reason.message ? reason.message : reason));
    if (retry < MAX_RETRIES) {
      retry++;
      setTimeout(connect, delayMs);
    } else {
      console.warn('flagd: max retries reached');
    }
  }

  function connect() {
    delayMs = Math.min(delayMs * BACK_OFF_MULTIPLIER, MAX_DELAY_MS);
    if (abortController) {
      abortController.abort();
    }
    abortController = new AbortController();
    var signal = abortController.signal;

    fetch(baseUrl + '/' + SERVICE + '/EventStream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/connect+json', 'Connect-Protocol-Version': '1' },
      body: envelope('{}'),
      signal: signal,
    })
      .then(function (response) {
        if (!response.ok || !response.body) {
          throw new Error('flagd EventStream failed with status ' + response.status);
        }
        var reader = response.body.getReader();
        var buffer = new Uint8Array(0);

        function append(chunk) {
          var merged = new Uint8Array(buffer.length + chunk.length);
          merged.set(buffer, 0);
          merged.set(chunk, buffer.length);
          buffer = merged;
        }

        function drain() {
          while (buffer.length >= 5) {
            var flagsByte = buffer[0];
            var length = new DataView(buffer.buffer, buffer.byteOffset).getUint32(1, false);
            if (buffer.length < 5 + length) {
              return;
            }
            var payload = buffer.slice(5, 5 + length);
            buffer = buffer.slice(5 + length);
            if ((flagsByte & 2) === 2) {
              // end-of-stream frame; an "error" member means the stream failed
              var end = length ? JSON.parse(new TextDecoder().decode(payload)) : {};
              if (end.error) {
                throw new Error(end.error.message || 'flagd stream error');
              }
              return;
            }
            onStreamMessage(JSON.parse(new TextDecoder().decode(payload)));
          }
        }

        function pump() {
          return reader.read().then(function (result) {
            if (result.done) {
              throw new Error('flagd event stream closed');
            }
            append(result.value);
            drain();
            return pump();
          });
        }
        return pump();
      })
      .catch(function (error) {
        if (signal.aborted) {
          return;
        }
        scheduleReconnect(error);
      });
  }

  window.OtelDemoFlags = {
    /** Number flag value, or the default while flagd is unreachable / the flag is unknown. */
    getNumber: function (key, defaultValue) {
      var flag = flags[key];
      if (!flag || flag.type !== 'doubleValue') {
        return defaultValue;
      }
      return Number(flag.value);
    },
    /** Registers a callback for every (re)evaluation of the flags. */
    onChange: function (listener) {
      listeners.push(listener);
    },
  };

  connect();
})();
