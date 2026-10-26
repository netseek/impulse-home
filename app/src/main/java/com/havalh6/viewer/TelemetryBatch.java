package com.havalh6.viewer;

/**
 * Main-thread telemetry calls share one renderer task. Keep every arrival in
 * order because repeated keys can carry edges rather than just current state.
 * Pure Java so escaping can be tested without Android's JSON stubs.
 */
final class TelemetryBatch {
    private final StringBuilder calls = new StringBuilder();
    private boolean flushPending;
    private static final String HEX = "0123456789abcdef";

    /** Only the first arrival posts work; later arrivals share that pending flush. */
    boolean add(String key, String value) {
        boolean postFlush = !flushPending;
        flushPending = true;
        calls.append("window.onCarDataUpdate(");
        appendLiteral(key);
        calls.append(',');
        appendLiteral(value);
        calls.append(");");
        return postFlush;
    }

    /** Empty drains do not schedule any renderer work; each call is sent once. */
    String drain() {
        flushPending = false;
        if (calls.length() == 0) return null;
        String script = "if(window.onCarDataUpdate){" + calls + "}";
        calls.setLength(0);
        return script;
    }

    private void appendLiteral(String value) {
        calls.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': calls.append("\\\\"); break;
                case '\'': calls.append("\\'"); break;
                case '"': calls.append("\\\""); break;
                case '\n': calls.append("\\n"); break;
                case '\r': calls.append("\\r"); break;
                default:
                    // Line separators also terminate literals in older WebViews.
                    if (c == '\u2028' || c == '\u2029' || Character.isISOControl(c)) {
                        calls.append("\\u");
                        calls.append(HEX.charAt((c >> 12) & 15));
                        calls.append(HEX.charAt((c >> 8) & 15));
                        calls.append(HEX.charAt((c >> 4) & 15));
                        calls.append(HEX.charAt(c & 15));
                    } else {
                        calls.append(c);
                    }
            }
        }
        calls.append('\'');
    }
}
