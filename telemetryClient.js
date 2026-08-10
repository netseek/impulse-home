/**
 * Car telemetry bus for the Haval H6 viewer.
 *
 * Contract used by Time → Auto (viewer consumes; car/app publishes):
 *   key:   "isNight"
 *   value: "true" | "false"  (string, matching Android evaluateJavascript)
 *          also accepts boolean true/false and "1"/"0"
 *
 * Ingress paths:
 *   1. Android: MainActivity broadcast → onCarDataUpdate(key, value)
 *   2. Desktop: WebSocket ws://127.0.0.1:8888  { event: 'car_data', key, value }
 */
(function() {
  'use strict';

  function parseIsNight(value) {
    return value === true || value === 'true' || value === '1' || value === 1;
  }

  var carTelemetry = {
    data: {},
    listeners: [],

    onUpdate: function(callback) {
      if (typeof callback === 'function') {
        this.listeners.push(callback);
      }
    },

    setKey: function(key, value) {
      this.data[key] = value;
      for (var i = 0; i < this.listeners.length; i++) {
        try {
          this.listeners[i](key, value, this.data);
        } catch (e) {
          console.error("[TelemetryClient] Listener error", e);
        }
      }
      window.dispatchEvent(new CustomEvent('carDataUpdate', { detail: { key: key, value: value, allData: this.data } }));
    },

    get: function(key, fallback) {
      if (this.data.hasOwnProperty(key)) return this.data[key];
      if (window.TelemetryBridge && typeof window.TelemetryBridge.getCarData === 'function') {
        var bridgeVal = window.TelemetryBridge.getCarData(key);
        if (bridgeVal !== undefined && bridgeVal !== null && bridgeVal !== '') return bridgeVal;
      }
      return fallback !== undefined ? fallback : '';
    },

    /** Effective cabin/UI night from the contracted isNight key. */
    isNight: function() {
      return parseIsNight(this.get('isNight', 'false'));
    }
  };

  window.carTelemetry = carTelemetry;

  // 1. Android Native Callback Bridge
  window.onCarDataUpdate = function(key, value) {
    carTelemetry.setKey(key, value);
  };

  // 2. WebSocket Telemetry Listener (ws://127.0.0.1:8888)
  function connectWebSocket() {
    var wsUrl = 'ws://127.0.0.1:8888';
    try {
      var ws = new WebSocket(wsUrl);

      ws.onopen = function() {
        console.log("[TelemetryClient] Connected to WebSocket at " + wsUrl);
      };

      ws.onmessage = function(event) {
        try {
          var msg = JSON.parse(event.data);
          if (msg.event === 'car_data' && msg.key) {
            carTelemetry.setKey(msg.key, msg.value);
          } else if (msg.event === 'snapshot' && msg.data) {
            for (var k in msg.data) {
              if (msg.data.hasOwnProperty(k)) {
                carTelemetry.setKey(k, msg.data[k]);
              }
            }
          }
        } catch (e) {
          console.error("[TelemetryClient] Error parsing message", e);
        }
      };

      ws.onclose = function() {
        console.log("[TelemetryClient] WebSocket closed, retrying in 3s...");
        setTimeout(connectWebSocket, 3000);
      };

      ws.onerror = function(err) {
        console.warn("[TelemetryClient] WebSocket error", err);
        try { ws.close(); } catch(_) {}
      };
    } catch (e) {
      console.warn("[TelemetryClient] Failed to initialize WebSocket", e);
    }
  }

  // Auto-connect WebSocket on desktop/dev hosts only. The Android WebView APK
  // has no INTERNET permission and no local telemetry broker.
  var isAndroidApp = /[?&#]android(?:&|$)/.test(String(location.search || ''))
    || /(?:^|[&#])android(?:&|$)/.test(String(location.hash || '').replace(/^#/, ''));
  if (typeof WebSocket !== 'undefined' && !isAndroidApp) {
    connectWebSocket();
  }
})();
