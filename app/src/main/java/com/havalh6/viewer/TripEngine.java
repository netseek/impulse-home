package com.havalh6.viewer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns the car's signal stream and GPS fixes into trips.
 *
 * <p>Pure Java and single-threaded: the Android side ({@link TripRecorder}) owns
 * the thread, the clock, the database and the location listener, and calls in
 * here. That keeps every rule below testable on a desktop JVM.
 *
 * <p><b>Why integrate rather than read the trip computer.</b> The bus only
 * carries AVERAGES whose reset rules are unknown, so a trip's fuel, energy and EV
 * share are added up here from instantaneous values. The trip-computer keys are
 * still captured raw at both ends ({@link TripState#rawFirst}) so the first real
 * drives can validate these integrals against the car's own numbers.
 *
 * <p><b>The stream is change-only.</b> A steady cruise sends no new
 * {@code vehicle_speed}, so integration advances on every event AND on the
 * recorder's 1 s tick, holding the last value. A gap longer than
 * {@link #MAX_STEP_MS} is not integrated across: that is a frozen process or a
 * clock correction, not driving.
 */
final class TripEngine {
    static final String KEY_READY = "car.basic.driving_ready_state";
    static final String KEY_SPEED = "car.basic.vehicle_speed";
    static final String KEY_ODOMETER = "car.basic.total_odometer";
    static final String KEY_VOLTAGE = "car.ev_info.power_battery_voltage";
    static final String KEY_CURRENT = "car.ev_info.cur_charge_current";
    static final String KEY_FUEL_INST = "car.basic.instant_fuel_consumption";
    static final String KEY_ICE = "haval.power.ice";
    static final String KEY_FLOW = "haval.power.flow";
    static final String KEY_FUEL_PCT = "car.basic.remain_fuel_percentage";
    /** Impulse Android Auto telemetry (JSON). Parsed by TripRecorder, fed to {@link #onNavigation}. */
    static final String KEY_NAV = "app.navigation.directions";
    /** Traction battery state of charge, percent. */
    static final String KEY_SOC = "car.ev_info.cur_battery_power_percentage";

    /** Trip-computer keys recorded raw at both ends of a trip, for validation only. */
    static final Set<String> RAW_KEYS = new HashSet<>(Arrays.asList(
            KEY_ODOMETER,
            KEY_FUEL_PCT,
            "car.basic.avg_fuel_consumption",
            "car.basic.cur_journey_avg_fuel_consume",
            "car.ev_info.avg_energy_consume_info_since_startup",
            "car.basic.accumulated_odometer",
            "car.basic.accumulated_drivetime",
            "car.basic.vehicle_speed_since_reset",
            "car.ev_info.cycle_fuel_consume_info",
            "car.ev_info.cycle_energy_consume_info"));

    // Tunables. All provisional until measured on real drives.
    /** READY off for less than this (a fuel stop) keeps the same trip. */
    static final long MERGE_GAP_MS = 10 * 60_000L;
    static final long MAX_STEP_MS = 10_000L;
    /** Below both, a READY cycle was not a trip (moved the car in the driveway). */
    static final double MIN_TRIP_KM = 0.2;
    static final long MIN_TRIP_DRIVE_MS = 60_000L;
    static final double MOVING_KMH = 1.0;
    static final long POINT_MOVING_MS = 1_000L;
    static final long POINT_STOPPED_MS = 10_000L;
    static final long FIX_FRESH_MS = 5_000L;
    static final double FIX_MAX_ACCURACY_M = 50;
    static final double CLIMB_MAX_ACCURACY_M = 25;
    static final double CLIMB_HYSTERESIS_M = 3;
    static final double HARSH_ACCEL_MS2 = 3.0;
    static final double HARSH_BRAKE_MS2 = -3.5;
    /** Speed samples closer than this are too noisy to differentiate. */
    static final long ACCEL_MIN_DT_MS = 400L;
    static final long ACCEL_MAX_DT_MS = 3_000L;
    static final double STOP_BELOW_KMH = 1.0;
    static final double STOP_ARMED_ABOVE_KMH = 5.0;
    /** The odometer ticks every 100 m; interpolation between ticks never exceeds one tick. */
    static final double ODOMETER_TICK_KM = 0.1;
    /** Always allow this much odometer change between readings (a few ticks of jitter). */
    static final double ODOMETER_SLACK_KM = 3 * ODOMETER_TICK_KM;
    /** Faster than this between two odometer readings is not driving. */
    static final double ODOMETER_MAX_KMH = 250;
    /** Android Auto remaining distance at or under this is "arrived". */
    static final double ARRIVAL_M = 150;
    /** Guidance that has sent nothing for this long is a lost session and no longer holds a trip open. */
    static final long GUIDANCE_HOLD_MAX_MS = 60 * 60_000L;
    /** Impulse DASHBOARD_FUEL_TANK_CAPACITY_LITERS: one tank size across both apps (CLAUDE.md). */
    static final double TANK_LITRES = 55.0;
    /** A fuel level this much higher after a stop is a refuel, not gauge noise. */
    static final double REFUEL_MIN_PCT = 4.0;
    /** A battery level this much higher after a stop is a charge. */
    static final double CHARGE_MIN_PCT = 8.0;
    /** Level readings this soon after setting off still belong to the stop: gauges settle slowly. */
    static final long LEVEL_SETTLE_MS = 3 * 60_000L;

    interface Listener {
        void onTripOpened(long startMs);
        void onPoint(long startMs, TripPoint point);
        void onTripClosed(TripSummary summary);
        void onTripDiscarded(long startMs);
        /** A refuel or charge noticed across a stop; {@code startMs} is the trip it belongs to. */
        void onStop(long startMs, TripStop stop);
    }

    private final Listener listener;
    private TripState st = new TripState();

    // Latest values. Not checkpointed: Impulse replays them after a restart.
    private Boolean ready;
    private double kmh = Double.NaN;
    private double volt = Double.NaN;
    private double amp = Double.NaN;
    private double odo = Double.NaN;
    private double fuelPct = Double.NaN;
    private double soc = Double.NaN;
    // What the tank / battery held when READY last went off, and where. Not checkpointed:
    // TripRecorder re-primes them from the last stored trip after a restart (primeParked).
    private double parkedFuelPct = Double.NaN;
    private double parkedSoc = Double.NaN;
    private double parkedLat = Double.NaN;
    private double parkedLon = Double.NaN;
    private long parkedAt;
    private long resumedAt;
    private TripStop refuelCandidate;
    private TripStop chargeCandidate;
    private final java.util.List<TripStop> pendingStops = new java.util.ArrayList<>();
    private int fuelMode = TripSignals.FUEL_UNKNOWN;
    private double fuelRate = Double.NaN;
    private Boolean ice;
    private double lat = Double.NaN;
    private double lon = Double.NaN;
    private double alt = Double.NaN;
    private long fixT;
    private final Map<String, String> latestRaw = new HashMap<>();

    TripEngine(Listener listener) {
        this.listener = listener;
    }

    boolean isOpen() {
        return st.open;
    }

    void onSignal(String key, String raw, long t) {
        if (key == null || raw == null) return;
        advance(t);
        if (RAW_KEYS.contains(key)) {
            latestRaw.put(key, raw);
            if (st.open) {
                if (!st.rawFirst.containsKey(key)) st.rawFirst.put(key, raw);
                st.rawLast.put(key, raw);
            }
        }
        switch (key) {
            case KEY_READY: {
                Boolean on = TripSignals.parseReady(raw);
                if (on != null) setReady(on, t);
                break;
            }
            case KEY_SPEED: {
                double v = TripSignals.parseNumber(raw);
                if (TripSignals.inRange(v, 0, 300)) onSpeed(v, t);
                break;
            }
            case KEY_ODOMETER: {
                double v = TripSignals.parseNumber(raw);
                if (TripSignals.inRange(v, 0, 2_000_000)) onOdometer(v, t);
                break;
            }
            case KEY_VOLTAGE: {
                double v = TripSignals.parseNumber(raw);
                volt = TripSignals.inRange(v, 1, 1000) ? v : Double.NaN;
                break;
            }
            case KEY_CURRENT: {
                double v = TripSignals.parseNumber(raw);
                amp = TripSignals.inRange(v, -1000, 1000) ? v : Double.NaN;
                break;
            }
            case KEY_FUEL_INST: {
                TripSignals.FuelRate r = TripSignals.parseFuelInst(raw);
                fuelMode = r == null ? TripSignals.FUEL_UNKNOWN : r.mode;
                fuelRate = r == null ? Double.NaN : r.value;
                break;
            }
            case KEY_ICE: {
                Boolean on = TripSignals.parseBool(raw);
                if (on != null) ice = on;
                break;
            }
            case KEY_FLOW: {
                Boolean on = TripSignals.parseFlowIce(raw);
                if (on != null) ice = on;
                break;
            }
            case KEY_FUEL_PCT: {
                double v = TripSignals.parseNumber(raw);
                if (TripSignals.inRange(v, 0, 100)) {
                    fuelPct = v;
                    if (st.open) {
                        if (Double.isNaN(st.fuelPctStart)) st.fuelPctStart = v;
                        st.fuelPctEnd = v;
                    }
                    onLevel(true, v, t);
                }
                break;
            }
            case KEY_SOC: {
                double v = TripSignals.parseNumber(raw);
                if (TripSignals.inRange(v, 0, 100)) {
                    soc = v;
                    if (st.open) {
                        if (Double.isNaN(st.socStart)) st.socStart = v;
                        st.socEnd = v;
                    }
                    onLevel(false, v, t);
                }
                break;
            }
            default:
                break;
        }
    }

    void onLocation(double latitude, double longitude, double altitude, double accuracyM, long t) {
        advance(t);
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) return;
        if (!(accuracyM <= FIX_MAX_ACCURACY_M)) return;
        lat = latitude;
        lon = longitude;
        alt = altitude;
        fixT = t;
        if (!st.open) return;
        if (Double.isNaN(st.startLat)) {
            st.startLat = latitude;
            st.startLon = longitude;
        }
        st.endLat = latitude;
        st.endLon = longitude;
        if (!Double.isNaN(altitude) && accuracyM <= CLIMB_MAX_ACCURACY_M) {
            if (Double.isNaN(st.altRef)) {
                st.altRef = altitude;
            } else if (altitude > st.altRef + CLIMB_HYSTERESIS_M) {
                st.climbM += altitude - st.altRef;
                st.altRef = altitude;
            } else if (altitude < st.altRef - CLIMB_HYSTERESIS_M) {
                st.altRef = altitude;
            }
        }
    }

    /**
     * Android Auto guidance, parsed by the recorder from
     * {@code app.navigation.directions}. Records the plan as Android Auto first
     * stated it (remaining distance and time when guidance began during this
     * trip) and whether the car got there. The payload carries no destination
     * NAME -- only distances and manoeuvres -- so the trip's end place comes from
     * the geocoder instead.
     */
    void onNavigation(boolean active, double remainingM, double remainingS, boolean destinationTurn, long t) {
        advance(t);
        if (!st.open) return;
        if (active) {
            if (!st.guided) {
                if (!(remainingM > 0)) return;  // wait for the first figure
                st.guided = true;
                st.plannedKm = remainingM / 1000.0;
                st.plannedS = remainingS > 0 ? Math.round(remainingS) : 0;
                st.guideStartKm = liveKm();
                st.guideStartT = t;
            }
            st.lastNavT = t;
            if (st.guideEndT != 0 || Double.isNaN(remainingM)) return;
            st.lastRemainingM = remainingM;
            if (remainingM <= ARRIVAL_M || (destinationTurn && remainingM <= 2 * ARRIVAL_M)) endGuidance(t, true);
        } else if (st.guided && st.guideEndT == 0) {
            // Android Auto clears guidance on arrival as well as on cancel, so an
            // end within reach of the destination still counts as arrived.
            if (!Double.isNaN(st.lastRemainingM) && st.lastRemainingM <= 2 * ARRIVAL_M) {
                endGuidance(t, true);
            } else {
                endGuidance(t, false);
            }
        }
    }

    private void endGuidance(long t, boolean arrived) {
        st.arrived = arrived;
        st.guideEndT = t;
        st.guideEndKm = liveKm();
    }

    /**
     * Seeds the parked levels after a restart from the last stored trip, so a
     * refuel made while this process was not running is still noticed when the
     * gauge reads higher on the next start. Never overwrites a level already known.
     */
    void primeParked(double fuelPctAtEnd, double socAtEnd, double atLat, double atLon, long at) {
        if (Double.isNaN(parkedFuelPct)) parkedFuelPct = fuelPctAtEnd;
        if (Double.isNaN(parkedSoc)) parkedSoc = socAtEnd;
        if (Double.isNaN(parkedLat)) {
            parkedLat = atLat;
            parkedLon = atLon;
        }
        if (parkedAt == 0) parkedAt = at;
    }

    private void park(long t) {
        flushCandidates();
        // A level not read yet this session keeps what primeParked supplied.
        if (!Double.isNaN(fuelPct)) parkedFuelPct = fuelPct;
        if (!Double.isNaN(soc)) parkedSoc = soc;
        if (!Double.isNaN(lat)) {
            parkedLat = lat;
            parkedLon = lon;
        }
        parkedAt = t;
        resumedAt = 0;
    }

    /**
     * A fuel or battery level reading. One taken while parked, or within
     * {@link #LEVEL_SETTLE_MS} of setting off, that is clearly above the level at
     * READY-off is a refuel / charge at the parking spot. A gauge that keeps
     * climbing as it settles grows the same stop rather than adding another.
     */
    private void onLevel(boolean fuel, double v, long t) {
        double parked = fuel ? parkedFuelPct : parkedSoc;
        if (Double.isNaN(parked)) return;
        boolean duringStop = !Boolean.TRUE.equals(ready) || (resumedAt > 0 && t - resumedAt <= LEVEL_SETTLE_MS);
        if (!duringStop) return;
        TripStop c = fuel ? refuelCandidate : chargeCandidate;
        if (c != null) {
            if (v > c.after) {
                c.after = v;
                c.amount = amountOf(fuel, c.before, v);
            }
            return;
        }
        double gain = v - parked;
        if (gain < (fuel ? REFUEL_MIN_PCT : CHARGE_MIN_PCT)) return;
        TripStop stop = new TripStop(fuel ? TripStop.REFUEL : TripStop.CHARGE, parkedAt > 0 ? parkedAt : t,
                parkedLat, parkedLon, parked, v, amountOf(fuel, parked, v));
        if (fuel) refuelCandidate = stop; else chargeCandidate = stop;
    }

    /** Litres the way Impulse derives them (percent x 55 L); percentage points for a charge. */
    private static double amountOf(boolean fuel, double before, double after) {
        return fuel ? (after - before) * TANK_LITRES / 100.0 : after - before;
    }

    /** Once the gauges have had time to settle after setting off, the stop is final. */
    private void settleStops(long t) {
        if (resumedAt == 0 || !Boolean.TRUE.equals(ready) || t - resumedAt <= LEVEL_SETTLE_MS) return;
        flushCandidates();
        parkedFuelPct = Double.NaN;
        parkedSoc = Double.NaN;
        resumedAt = 0;
    }

    private void flushCandidates() {
        if (refuelCandidate != null) emitStop(refuelCandidate);
        if (chargeCandidate != null) emitStop(chargeCandidate);
        refuelCandidate = null;
        chargeCandidate = null;
    }

    /** To the open trip, or held for the next one when the car is parked between trips. */
    private void emitStop(TripStop stop) {
        if (st.open) listener.onStop(st.startMs, stop); else pendingStops.add(stop);
    }

    /** Called by the recorder about once a second. */
    void tick(long t) {
        advance(t);
        settleStops(t);
        if (!st.open) return;
        if (!Boolean.TRUE.equals(ready)) {
            if (st.readyOffAt > 0 && t - st.readyOffAt >= MERGE_GAP_MS && !guidingNow(t)) close(st.readyOffAt);
            return;
        }
        boolean moving = !Double.isNaN(kmh) && kmh >= MOVING_KMH;
        long interval = moving ? POINT_MOVING_MS : POINT_STOPPED_MS;
        if (st.lastPointT == 0 || t - st.lastPointT >= interval) {
            st.lastPointT = t;
            boolean fresh = fixT > 0 && t - fixT <= FIX_FRESH_MS;
            double kw = !Double.isNaN(volt) && !Double.isNaN(amp) ? volt * amp / 1000.0 : Double.NaN;
            listener.onPoint(st.startMs, new TripPoint(t,
                    fresh ? lat : Double.NaN, fresh ? lon : Double.NaN, fresh ? alt : Double.NaN,
                    kmh, kw, fuelMode, fuelRate, ice == null ? -1 : (ice ? 1 : 0), liveKm()));
        }
    }

    /**
     * The head unit slept with this trip open: the recorder's monotonic clock
     * says its ticks stopped for longer than a stop may last, and READY=0 never
     * reached it. Nobody drives with the head unit asleep, so the trip ends
     * where its data stopped, and READY must be heard again before the next
     * one opens. Owner report 2026-09-14: parked at home, the trip never
     * concluded. Not inferred from a wall-clock gap: a forward NTP/GNSS
     * correction while driving must keep the trip (clockJumpsAreNotIntegrated).
     */
    void closeInterrupted(long t) {
        if (!st.open) return;
        ready = null;
        if (guidingNow(t)) {
            // Still guiding: the trip stays open, with the car treated as off since
            // its data stopped, so the usual rule ends it once guidance does.
            if (st.readyOffAt == 0) st.readyOffAt = st.lastT;
            return;
        }
        close(st.readyOffAt > 0 ? st.readyOffAt : st.lastT);
    }

    /**
     * Android Auto is guiding and the car has not reached the destination (owner,
     * 2026-09-14): a stop, however long, does not end the trip until guidance
     * ends -- arrived or cancelled -- and then the READY-off rules apply as usual.
     * Within 2 x ARRIVAL_M counts as reached. Guidance silent for
     * GUIDANCE_HOLD_MAX_MS is a lost session and holds nothing.
     */
    private boolean guidingNow(long t) {
        return st.open && st.guided && st.guideEndT == 0
                && !(st.lastRemainingM <= 2 * ARRIVAL_M)
                && t - st.lastNavT < GUIDANCE_HOLD_MAX_MS;
    }

    /**
     * The driver's FINALIZAR VIAGEM. Closes the open trip now (at READY-off if
     * the car is already off; a READY cycle too short to be a trip is discarded
     * as usual). If the car is still READY the next trip starts at this moment,
     * so driving on is recorded from here.
     */
    void finishNow(long t) {
        advance(t);
        if (!st.open) return;
        close(st.readyOffAt > 0 ? st.readyOffAt : t);
        if (Boolean.TRUE.equals(ready)) open(t);
    }

    /** The open trip as of {@code t}, or null. */
    TripSummary live(long t) {
        advance(t);
        return st.open ? new TripSummary(st, t, liveKm()) : null;
    }

    String checkpoint() {
        return st.encode();
    }

    /**
     * Resume after a restart. A trip whose READY-off (or last sample, if READY
     * never went off -- the power was simply cut) is older than the merge gap is
     * closed at that moment rather than left open forever.
     */
    void restore(TripState saved, long now) {
        if (saved == null || !saved.open) return;
        st = saved;
        ready = null;
        if (st.readyOffAt == 0) st.readyOffAt = st.lastT;
        if (now - st.readyOffAt >= MERGE_GAP_MS && !guidingNow(now)) close(st.readyOffAt);
    }

    private void setReady(boolean on, long t) {
        Boolean was = ready;
        ready = on;
        if (on) {
            if (!st.open) {
                open(t);
            } else if (st.readyOffAt > 0) {
                if (t - st.readyOffAt < MERGE_GAP_MS || guidingNow(t)) {
                    st.readyOffAt = 0;
                } else {
                    close(st.readyOffAt);
                    open(t);
                }
            }
            // Only a real transition starts the settle window: Impulse replays READY=1 while driving.
            if (!Boolean.TRUE.equals(was)) resumedAt = t;
        } else {
            if (st.open && st.readyOffAt == 0) st.readyOffAt = t;
            // A replayed READY=0 while already parked must not re-take the parked levels:
            // the gauge may already read the refilled tank.
            if (!Boolean.FALSE.equals(was)) park(t);
        }
    }

    private void open(long t) {
        st = new TripState();
        st.open = true;
        st.startMs = t;
        st.lastT = t;
        if (!Double.isNaN(odo)) {
            st.odoStart = odo;
            st.odoLast = odo;
            st.lastOdoT = t;
            st.odoFromOpen = true;
        }
        st.fuelPctStart = fuelPct;
        st.fuelPctEnd = fuelPct;
        st.socStart = soc;
        st.socEnd = soc;
        st.rawFirst.putAll(latestRaw);
        st.rawLast.putAll(latestRaw);
        if (fixT > 0 && t - fixT <= 30_000L) {
            st.startLat = lat;
            st.startLon = lon;
            st.endLat = lat;
            st.endLon = lon;
        }
        st.vPrev = kmh;
        st.lastSpeedT = Double.isNaN(kmh) ? 0 : t;
        st.wasMoving = !Double.isNaN(kmh) && kmh > STOP_ARMED_ABOVE_KMH;
        listener.onTripOpened(t);
        // Stops noticed while no trip was open (parked between trips) belong to this one.
        for (TripStop stop : pendingStops) listener.onStop(t, stop);
        pendingStops.clear();
    }

    private void close(long endT) {
        // A level read while parked at the end of this trip belongs to this trip.
        flushCandidates();
        if (st.guided && st.guideEndT == 0) {
            // Still guiding when the car was switched off: at the door counts as arrived.
            st.arrived = !Double.isNaN(st.lastRemainingM) && st.lastRemainingM <= 2 * ARRIVAL_M;
            st.guideEndT = endT;
            st.guideEndKm = finalKm();
        }
        st.open = false;
        TripSummary summary = new TripSummary(st, endT, finalKm());
        long startMs = st.startMs;
        st = new TripState();
        if (summary.km < MIN_TRIP_KM && summary.driveMs < MIN_TRIP_DRIVE_MS) {
            listener.onTripDiscarded(startMs);
        } else {
            listener.onTripClosed(summary);
        }
    }

    private void advance(long t) {
        if (!st.open) return;
        long dt = t - st.lastT;
        if (dt <= 0) {
            if (dt < 0) st.lastT = t;  // the clock went back: rebase, integrate nothing
            return;
        }
        st.lastT = t;
        if (dt > MAX_STEP_MS) return;
        if (!Boolean.TRUE.equals(ready)) return;
        double hours = dt / 3_600_000.0;
        if (!Double.isNaN(kmh)) {
            double km = kmh * hours;
            st.distInt += km;
            boolean moving = kmh >= MOVING_KMH;
            if (moving) st.driveMs += dt; else st.idleMs += dt;
            if (moving && Boolean.FALSE.equals(ice)) {
                st.evKm += km;
                st.evMs += dt;
            } else if (moving && Boolean.TRUE.equals(ice)) {
                st.iceKm += km;
            }
            if (fuelMode == TripSignals.FUEL_RUNNING && fuelRate > 0) st.fuelL += fuelRate / 100.0 * km;
        }
        if (fuelMode == TripSignals.FUEL_IDLE && fuelRate > 0) st.fuelL += fuelRate * hours;
        if (!Double.isNaN(volt) && !Double.isNaN(amp)) {
            double kwh = volt * amp / 1000.0 * hours;
            if (kwh >= 0) st.kwhOut += kwh; else st.kwhIn -= kwh;
        }
    }

    private void onSpeed(double v, long t) {
        if (st.open && Boolean.TRUE.equals(ready)) {
            if (v > st.maxKmh) st.maxKmh = v;
            if (v > STOP_ARMED_ABOVE_KMH) {
                st.wasMoving = true;
            } else if (v < STOP_BELOW_KMH && st.wasMoving) {
                st.stops++;
                st.wasMoving = false;
            }
            countHarsh(v, t);
        }
        kmh = v;
    }

    private void countHarsh(double v, long t) {
        if (st.lastSpeedT > 0) {
            long dt = t - st.lastSpeedT;
            if (dt < ACCEL_MIN_DT_MS) return;  // keep the older sample as the reference
            if (dt <= ACCEL_MAX_DT_MS && !Double.isNaN(st.vPrev)) {
                double a = (v - st.vPrev) / 3.6 / (dt / 1000.0);
                if (a >= HARSH_ACCEL_MS2) {
                    if (!st.inAccel) st.harshAccel++;
                    st.inAccel = true;
                } else if (a < HARSH_ACCEL_MS2 / 2) {
                    st.inAccel = false;
                }
                if (a <= HARSH_BRAKE_MS2) {
                    if (!st.inBrake) st.harshBrake++;
                    st.inBrake = true;
                } else if (a > HARSH_BRAKE_MS2 / 2) {
                    st.inBrake = false;
                }
            }
        }
        st.vPrev = v;
        st.lastSpeedT = t;
    }

    /**
     * Distance of record is the SUM of plausible odometer increases, not
     * last-minus-first. Measured on the emulator 2026-09-13: a trip resumed
     * inside the merge gap received an odometer 1000 km above its start value
     * and reported 1001 km. A car's odometer does not jump, but a replayed or
     * corrupt value would destroy a trip the same way, so any change larger than
     * the car could have driven since the previous reading, or while READY is
     * known off, re-bases instead of counting.
     *
     * <p>A LOWER reading re-bases too, rather than being ignored. The first
     * version dropped any value below the last one ("the odometer never runs
     * backwards"), and on the emulator a single spike to 9999.0 then latched out
     * every real reading that followed. Re-basing costs at most one tick.
     */
    private void onOdometer(double v, long t) {
        odo = v;
        if (!st.open) return;
        if (Double.isNaN(st.odoLast)) {
            st.odoStart = v;
            st.odoLast = v;
            st.lastOdoT = t;
            st.odoFromOpen = st.distInt < 0.05;
            st.distIntAtOdo = st.distInt;
            return;
        }
        if (v == st.odoLast) return;
        double delta = v - st.odoLast;
        long dt = st.lastOdoT > 0 ? Math.max(0, t - st.lastOdoT) : 0;
        double plausible = ODOMETER_SLACK_KM + ODOMETER_MAX_KMH * dt / 3_600_000.0;
        if (delta > 0 && delta <= plausible && !Boolean.FALSE.equals(ready)) {
            st.odoKm += delta;
        } else {
            st.odoJumps++;
        }
        st.odoLast = v;
        st.lastOdoT = t;
        st.distIntAtOdo = st.distInt;
    }

    private double odoDelta() {
        return Double.isNaN(st.odoLast) ? Double.NaN : st.odoKm;
    }

    /** Odometer ticks, interpolated by the speed integral since the last tick. */
    private double liveKm() {
        double delta = odoDelta();
        if (Double.isNaN(delta)) return st.distInt;
        double extra = Math.max(0, Math.min(ODOMETER_TICK_KM, st.distInt - st.distIntAtOdo));
        double km = delta + extra;
        return st.odoFromOpen ? km : Math.max(km, st.distInt);
    }

    private double finalKm() {
        double delta = odoDelta();
        if (Double.isNaN(delta)) return st.distInt;
        // Odometer known from the start and it moved: it is the distance of record.
        if (st.odoFromOpen && delta >= ODOMETER_TICK_KM) return delta;
        return Math.max(delta, st.distInt);
    }
}
