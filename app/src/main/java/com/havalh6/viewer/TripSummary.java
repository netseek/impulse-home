package com.havalh6.viewer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A trip's totals, as closed (or, for the live view, as of now). */
final class TripSummary {
    /**
     * Below this much fuel a km/L figure is noise, not a measurement: a mostly
     * electric trip that burned 6 mL read 171,554 km/L on the emulator.
     */
    static final double MIN_FUEL_FOR_RATIO_L = 0.05;

    final boolean open;
    final long startMs;
    final long endMs;
    /** Distance of record: plausible odometer increases when usable, else the speed integral. */
    final double km;
    final double kmIntegrated;
    final double odoStart;
    final double odoEnd;
    final int odoJumps;
    final long driveMs;
    final long idleMs;
    final double evKm;
    final long evMs;
    final double kwhOut;
    final double kwhIn;
    final double fuelL;
    final double maxKmh;
    final int stops;
    final int harshAccel;
    final int harshBrake;
    final double climbM;
    final double startLat;
    final double startLon;
    final double endLat;
    final double endLon;
    final double fuelPctStart;
    final double fuelPctEnd;
    /** Android Auto guided part of the trip: the plan as first stated, and what was driven. */
    final boolean guided;
    final boolean arrived;
    final double plannedKm;
    final long plannedS;
    final double guidedKm;
    final long guidedS;
    final double socStart;
    final double socEnd;
    final Map<String, String> rawFirst;
    final Map<String, String> rawLast;

    TripSummary(TripState s, long endMs, double km) {
        this.open = s.open;
        this.startMs = s.startMs;
        this.endMs = endMs;
        this.km = km;
        this.kmIntegrated = s.distInt;
        this.odoStart = s.odoStart;
        this.odoEnd = s.odoLast;
        this.odoJumps = s.odoJumps;
        this.driveMs = s.driveMs;
        this.idleMs = s.idleMs;
        // The EV share compares like with like: distance integrated with the engine
        // off against distance integrated with the engine state known. Distance
        // before the state was first heard is shared out in that proportion, and
        // the result is expressed in the distance of record (the odometer). Owner
        // report 2026-09-14: an all-electric drive read 99%, because integrated EV
        // km was divided by odometer km and the first metres had no engine state.
        double knownKm = s.evKm + s.iceKm;
        this.evKm = knownKm > 0 ? Math.min(1.0, s.evKm / knownKm) * km : 0;
        this.evMs = s.evMs;
        this.kwhOut = s.kwhOut;
        this.kwhIn = s.kwhIn;
        this.fuelL = s.fuelL;
        this.maxKmh = s.maxKmh;
        this.stops = s.stops;
        this.harshAccel = s.harshAccel;
        this.harshBrake = s.harshBrake;
        this.climbM = s.climbM;
        this.startLat = s.startLat;
        this.startLon = s.startLon;
        this.endLat = s.endLat;
        this.endLon = s.endLon;
        this.fuelPctStart = s.fuelPctStart;
        this.fuelPctEnd = s.fuelPctEnd;
        this.guided = s.guided;
        this.arrived = s.arrived;
        this.plannedKm = s.guided ? s.plannedKm : Double.NaN;
        this.plannedS = s.guided ? s.plannedS : 0;
        // Still guiding (live view): measure to now.
        double guideEndKm = s.guideEndT > 0 ? s.guideEndKm : km;
        long guideEndT = s.guideEndT > 0 ? s.guideEndT : endMs;
        this.guidedKm = s.guided ? Math.max(0, guideEndKm - s.guideStartKm) : Double.NaN;
        this.guidedS = s.guided ? Math.max(0, guideEndT - s.guideStartT) / 1000 : 0;
        this.socStart = s.socStart;
        this.socEnd = s.socEnd;
        this.rawFirst = Collections.unmodifiableMap(new LinkedHashMap<>(s.rawFirst));
        this.rawLast = Collections.unmodifiableMap(new LinkedHashMap<>(s.rawLast));
    }

    /** km per litre, or NaN when too little fuel was burned to mean anything. */
    double kmPerLitre() {
        return fuelL >= MIN_FUEL_FOR_RATIO_L ? km / fuelL : Double.NaN;
    }

    /** Net pack energy per 100 km (out minus recovered), or NaN below 100 m. */
    double kwhPer100Km() {
        return km >= 0.1 ? (kwhOut - kwhIn) * 100.0 / km : Double.NaN;
    }

    double evShare() {
        return km >= 0.1 ? Math.min(1.0, evKm / km) : Double.NaN;
    }

    double avgMovingKmh() {
        return driveMs > 0 ? kmIntegrated / (driveMs / 3_600_000.0) : Double.NaN;
    }
}
