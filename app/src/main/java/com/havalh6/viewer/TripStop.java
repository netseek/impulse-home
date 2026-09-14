package com.havalh6.viewer;

/**
 * A refuel or a charge, noticed as the tank / battery level reading higher
 * after a stop than when READY went off (TripEngine.onLevel).
 *
 * <p>{@code amount} is litres for a refuel -- derived from the percentage the
 * same way Impulse does, percent x 55 L -- and percentage points for a charge:
 * the pack capacity is not on the bus, so no kWh figure is invented for it.
 */
final class TripStop {
    static final String REFUEL = "refuel";
    static final String CHARGE = "charge";

    final String kind;
    final long t;
    final double lat;
    final double lon;
    final double before;
    /** Mutable while the gauge is still settling after the fill. */
    double after;
    double amount;

    TripStop(String kind, long t, double lat, double lon, double before, double after, double amount) {
        this.kind = kind;
        this.t = t;
        this.lat = lat;
        this.lon = lon;
        this.before = before;
        this.after = after;
        this.amount = amount;
    }
}
