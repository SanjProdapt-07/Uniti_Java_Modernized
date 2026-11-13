// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

// Port of gateways/Session.gateway.ts. The session (user id + selected currency) lives in the
// browser's localStorage under the "session" key; it is not a cookie, as in the original app.
(function () {
  var sessionKey = 'session';

  function uuidv4() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = (Math.random() * 16) | 0;
      var v = c === 'x' ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }

  // Generated once per page load, like the module-level constant of the original
  var defaultSession = Object.freeze({ userId: uuidv4(), currencyCode: 'USD' });

  function isValid(session) {
    return (
      typeof session === 'object' &&
      session !== null &&
      typeof session.userId === 'string' &&
      typeof session.currencyCode === 'string'
    );
  }

  function getSession() {
    var sessionString = null;
    try {
      sessionString = window.localStorage.getItem(sessionKey);
    } catch (e) {
      return defaultSession;
    }

    if (sessionString) {
      try {
        var parsed = JSON.parse(sessionString);
        if (isValid(parsed)) {
          return parsed;
        }
      } catch (e) {
        console.warn('Failed to parse session from localStorage', e);
      }
    }
    try {
      window.localStorage.setItem(sessionKey, JSON.stringify(defaultSession));
    } catch (e) {
      /* storage unavailable: keep the in-memory default */
    }
    return defaultSession;
  }

  function setSessionValue(key, value) {
    var session = getSession();
    var next = {};
    Object.keys(session).forEach(function (k) {
      next[k] = session[k];
    });
    next[key] = value;
    window.localStorage.setItem(sessionKey, JSON.stringify(next));
  }

  window.OtelDemoSession = { getSession: getSession, setSessionValue: setSessionValue };
})();
