package com.havalh6.viewer;

/** One sample of an open trip: position when there is a fresh fix, drive state always. */
final class TripPoint {
    final long t;
    final double lat;
    final double lon;
    final double alt;
    final double kmh;
    final double kw;
    final int fuelMode;
    final double fuelRate;
    /** 1 engine on, 0 off, -1 unknown. */
    final int ice;
    /** Trip distance at this sample. */
    final double km;

    TripPoint(long t, double lat, double lon, double alt, double kmh, double kw,
              int fuelMode, double fuelRate, int ice, double km) {
        this.t = t;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.kmh = kmh;
        this.kw = kw;
        this.fuelMode = fuelMode;
        this.fuelRate = fuelRate;
        this.ice = ice;
        this.km = km;
    }

    boolean hasFix() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }
}
