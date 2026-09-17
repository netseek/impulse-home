package com.havalh6.viewer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the recorder has accumulated for the open trip.
 *
 * <p>This is the checkpoint: the MMI can lose power the instant the car is
 * switched off, so the recorder writes it to disk continuously and restores it
 * on the next start. Latest raw signal values are deliberately NOT part of it --
 * Impulse replays them on request, and a value from before a power cut is not a
 * current one.
 */
final class TripState {
    boolean open;
    long startMs;
    /** Wall-clock time the integrals were last advanced to. */
    long lastT;
    /** When READY went off while the trip was open, else 0. */
    long readyOffAt;
    long lastPointT;

    double odoStart = Double.NaN;
    double odoLast = Double.NaN;
    long lastOdoT;
    /** Sum of plausible odometer increases: the distance of record. */
    double odoKm;
    /** Odometer changes rejected as implausible (glitch, replay, movement outside READY). */
    int odoJumps;
    /** True when the odometer was already known as the trip opened. */
    boolean odoFromOpen;
    double distInt;
    double distIntAtOdo;

    long driveMs;
    long idleMs;
    double evKm;
    /** Distance driven with the engine known to be on; with evKm, the share's denominator. */
    double iceKm;
    long evMs;
    double kwhOut;
    double kwhIn;
    double fuelL;
    double maxKmh;

    int stops;
    boolean wasMoving;
    int harshAccel;
    int harshBrake;
    boolean inAccel;
    boolean inBrake;
    double vPrev = Double.NaN;
    long lastSpeedT;

    double climbM;
    double altRef = Double.NaN;
    double startLat = Double.NaN;
    double startLon = Double.NaN;
    double endLat = Double.NaN;
    double endLon = Double.NaN;
    double fuelPctStart = Double.NaN;
    double fuelPctEnd = Double.NaN;

    /** Android Auto guidance during this trip (TripEngine.onNavigation). */
    boolean guided;
    double plannedKm;
    long plannedS;
    double guideStartKm;
    long guideStartT;
    double guideEndKm;
    long guideEndT;
    boolean arrived;
    double lastRemainingM = Double.NaN;
    /** Last Android Auto guidance update; guidance silent too long no longer holds the trip. */
    long lastNavT;

    /** Battery state of charge at both ends: with the net pack energy, it measures usable capacity. */
    double socStart = Double.NaN;
    double socEnd = Double.NaN;

    /** First and last raw value of each trip-computer key, for validation on the car. */
    final Map<String, String> rawFirst = new LinkedHashMap<>();
    final Map<String, String> rawLast = new LinkedHashMap<>();

    /** One {@code key=value} per line. Car values never contain a newline. */
    String encode() {
        StringBuilder b = new StringBuilder();
        put(b, "open", open ? 1 : 0);
        put(b, "startMs", startMs);
        put(b, "lastT", lastT);
        put(b, "readyOffAt", readyOffAt);
        put(b, "lastPointT", lastPointT);
        put(b, "odoStart", odoStart);
        put(b, "odoLast", odoLast);
        put(b, "lastOdoT", lastOdoT);
        put(b, "odoKm", odoKm);
        put(b, "odoJumps", odoJumps);
        put(b, "odoFromOpen", odoFromOpen ? 1 : 0);
        put(b, "distInt", distInt);
        put(b, "distIntAtOdo", distIntAtOdo);
        put(b, "driveMs", driveMs);
        put(b, "idleMs", idleMs);
        put(b, "evKm", evKm);
        put(b, "iceKm", iceKm);
        put(b, "evMs", evMs);
        put(b, "kwhOut", kwhOut);
        put(b, "kwhIn", kwhIn);
        put(b, "fuelL", fuelL);
        put(b, "maxKmh", maxKmh);
        put(b, "stops", stops);
        put(b, "wasMoving", wasMoving ? 1 : 0);
        put(b, "harshAccel", harshAccel);
        put(b, "harshBrake", harshBrake);
        put(b, "climbM", climbM);
        put(b, "altRef", altRef);
        put(b, "startLat", startLat);
        put(b, "startLon", startLon);
        put(b, "endLat", endLat);
        put(b, "endLon", endLon);
        put(b, "fuelPctStart", fuelPctStart);
        put(b, "fuelPctEnd", fuelPctEnd);
        put(b, "guided", guided ? 1 : 0);
        put(b, "plannedKm", plannedKm);
        put(b, "plannedS", plannedS);
        put(b, "guideStartKm", guideStartKm);
        put(b, "guideStartT", guideStartT);
        put(b, "guideEndKm", guideEndKm);
        put(b, "guideEndT", guideEndT);
        put(b, "arrived", arrived ? 1 : 0);
        put(b, "lastRemainingM", lastRemainingM);
        put(b, "lastNavT", lastNavT);
        put(b, "socStart", socStart);
        put(b, "socEnd", socEnd);
        for (Map.Entry<String, String> e : rawFirst.entrySet()) put(b, "rf." + e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : rawLast.entrySet()) put(b, "rl." + e.getKey(), e.getValue());
        return b.toString();
    }

    static TripState decode(String text) {
        TripState s = new TripState();
        if (text == null) return s;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq);
            String v = line.substring(eq + 1);
            try {
                if (k.startsWith("rf.")) { s.rawFirst.put(k.substring(3), v); continue; }
                if (k.startsWith("rl.")) { s.rawLast.put(k.substring(3), v); continue; }
                switch (k) {
                    case "open": s.open = "1".equals(v); break;
                    case "startMs": s.startMs = Long.parseLong(v); break;
                    case "lastT": s.lastT = Long.parseLong(v); break;
                    case "readyOffAt": s.readyOffAt = Long.parseLong(v); break;
                    case "lastPointT": s.lastPointT = Long.parseLong(v); break;
                    case "odoStart": s.odoStart = Double.parseDouble(v); break;
                    case "odoLast": s.odoLast = Double.parseDouble(v); break;
                    case "lastOdoT": s.lastOdoT = Long.parseLong(v); break;
                    case "odoKm": s.odoKm = Double.parseDouble(v); break;
                    case "odoJumps": s.odoJumps = Integer.parseInt(v); break;
                    case "odoFromOpen": s.odoFromOpen = "1".equals(v); break;
                    case "distInt": s.distInt = Double.parseDouble(v); break;
                    case "distIntAtOdo": s.distIntAtOdo = Double.parseDouble(v); break;
                    case "driveMs": s.driveMs = Long.parseLong(v); break;
                    case "idleMs": s.idleMs = Long.parseLong(v); break;
                    case "evKm": s.evKm = Double.parseDouble(v); break;
                    case "iceKm": s.iceKm = Double.parseDouble(v); break;
                    case "evMs": s.evMs = Long.parseLong(v); break;
                    case "kwhOut": s.kwhOut = Double.parseDouble(v); break;
                    case "kwhIn": s.kwhIn = Double.parseDouble(v); break;
                    case "fuelL": s.fuelL = Double.parseDouble(v); break;
                    case "maxKmh": s.maxKmh = Double.parseDouble(v); break;
                    case "stops": s.stops = Integer.parseInt(v); break;
                    case "wasMoving": s.wasMoving = "1".equals(v); break;
                    case "harshAccel": s.harshAccel = Integer.parseInt(v); break;
                    case "harshBrake": s.harshBrake = Integer.parseInt(v); break;
                    case "climbM": s.climbM = Double.parseDouble(v); break;
                    case "altRef": s.altRef = Double.parseDouble(v); break;
                    case "startLat": s.startLat = Double.parseDouble(v); break;
                    case "startLon": s.startLon = Double.parseDouble(v); break;
                    case "endLat": s.endLat = Double.parseDouble(v); break;
                    case "endLon": s.endLon = Double.parseDouble(v); break;
                    case "fuelPctStart": s.fuelPctStart = Double.parseDouble(v); break;
                    case "fuelPctEnd": s.fuelPctEnd = Double.parseDouble(v); break;
                    case "guided": s.guided = "1".equals(v); break;
                    case "plannedKm": s.plannedKm = Double.parseDouble(v); break;
                    case "plannedS": s.plannedS = Long.parseLong(v); break;
                    case "guideStartKm": s.guideStartKm = Double.parseDouble(v); break;
                    case "guideStartT": s.guideStartT = Long.parseLong(v); break;
                    case "guideEndKm": s.guideEndKm = Double.parseDouble(v); break;
                    case "guideEndT": s.guideEndT = Long.parseLong(v); break;
                    case "arrived": s.arrived = "1".equals(v); break;
                    case "lastRemainingM": s.lastRemainingM = Double.parseDouble(v); break;
                    case "lastNavT": s.lastNavT = Long.parseLong(v); break;
                    case "socStart": s.socStart = Double.parseDouble(v); break;
                    case "socEnd": s.socEnd = Double.parseDouble(v); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) {
                // A corrupt field loses that field, not the trip.
            }
        }
        return s;
    }

    private static void put(StringBuilder b, String key, Object value) {
        b.append(key).append('=').append(String.valueOf(value).replace('\n', ' ')).append('\n');
    }
}
