package com.havalh6.viewer;

/**
 * Parsers for the car values the trip recorder integrates.
 *
 * <p>Pure Java so the recorder's arithmetic can be unit-tested off the car.
 * The formats follow what Impulse's cluster theme already parses
 * ({@code InstrumentProjector2}), which is the trusted reference for these keys
 * (docs/energy-workspace-plan.md, "Data sources").
 */
final class TripSignals {
    static final int FUEL_UNKNOWN = 0;
    /** {@code {1,x}}: engine running, x is L/100 km. */
    static final int FUEL_RUNNING = 1;
    /** {@code {4,x}}: engine idling, x is L/h. */
    static final int FUEL_IDLE = 4;

    private TripSignals() {}

    /** Mirrors index.html {@code _carBool}: a braced vector reads its first slot. */
    static Boolean parseBool(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("{")) {
            s = stripBraces(s);
            int comma = s.indexOf(',');
            if (comma >= 0) s = s.substring(0, comma);
            s = s.trim();
        }
        if (s.isEmpty()) return null;
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /**
     * {@code car.basic.driving_ready_state} read the way Impulse reads it
     * (ServiceManager.isVehicleReadyStateOff / On): "0" and "-1" are off and
     * any other value is on. The car publishes more than 0 / 1, so treating
     * only "1" as READY would misread it.
     */
    static Boolean parseReady(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("{")) {
            s = stripBraces(s);
            int comma = s.indexOf(',');
            if (comma >= 0) s = s.substring(0, comma);
            s = s.trim();
        }
        if (s.isEmpty()) return null;
        return !("0".equals(s) || "-1".equals(s));
    }

    /** Mirrors index.html {@code _carNumber}: a braced pair reads its second slot. */
    static double parseNumber(String raw) {
        if (raw == null) return Double.NaN;
        String s = raw.trim();
        if (s.startsWith("{")) {
            String[] parts = stripBraces(s).split(",");
            s = (parts.length > 1 ? parts[1] : parts[0]).trim();
        }
        try {
            double n = Double.parseDouble(s.replace(',', '.'));
            return Double.isInfinite(n) ? Double.NaN : n;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Unavailable-value sentinels the bus uses. Same list as index.html
     * {@code _graphAcceptKpi}.
     */
    static boolean isSentinel(double n) {
        return n == 65535 || n == 6553.5 || n == 1023 || n == 2047;
    }

    static boolean inRange(double n, double min, double max) {
        return !Double.isNaN(n) && !isSentinel(n) && n >= min && n <= max;
    }

    /** {@code car.basic.instant_fuel_consumption} as a mode and a rate. */
    static final class FuelRate {
        final int mode;
        final double value;

        FuelRate(int mode, double value) {
            this.mode = mode;
            this.value = value;
        }
    }

    /**
     * Impulse {@code updateGasConsumption}: {@code {metric,value}}, metric 1 is a
     * running rate in L/100 km, metric 4 an idle rate in L/h. A bare number is
     * treated as metric 1, exactly as the theme does. Returns null when the value
     * is unreadable or a sentinel.
     */
    static FuelRate parseFuelInst(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int metric;
        double value;
        try {
            if (s.startsWith("{") && s.contains(",")) {
                String[] parts = stripBraces(s).split(",");
                metric = (int) Math.round(Double.parseDouble(parts[0].trim()));
                value = Double.parseDouble(parts[1].trim());
            } else {
                metric = FUEL_RUNNING;
                value = Double.parseDouble(s);
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (isSentinel(value) || Double.isNaN(value) || value < 0) return null;
        if (metric == FUEL_RUNNING) return value <= 60 ? new FuelRate(FUEL_RUNNING, value) : null;
        if (metric == FUEL_IDLE) return value <= 10 ? new FuelRate(FUEL_IDLE, value) : null;
        return new FuelRate(FUEL_UNKNOWN, 0);
    }

    /** Impulse packed flow {@code v1|state|ice|front|rear}: the ICE slot, or null. */
    static Boolean parseFlowIce(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split("\\|");
        if (parts.length < 5 || !"v1".equals(parts[0])) return null;
        return "1".equals(parts[2]);
    }

    private static String stripBraces(String s) {
        String out = s;
        if (out.startsWith("{")) out = out.substring(1);
        if (out.endsWith("}")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
