package com.havalh6.viewer;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Records trips natively, off the WebView.
 *
 * <p>The page cannot do this: when another app takes focus the WebView goes
 * hidden, Chromium stops servicing rAF and clamps timers to 1 Hz (CLAUDE.md), so
 * a trip recorded there would have holes exactly when the driver is using
 * navigation.
 *
 * <p><b>Process-scoped, not activity-scoped.</b> The first version was created
 * and stopped with {@link MainActivity} and fed by the activity's telemetry
 * receiver. Measured on the emulator 2026-09-13: the activity was finished and
 * recreated in the same process 6 s into a simulated drive, the new recorder
 * restored the checkpoint with READY unknown, and -- READY being change-only --
 * integrated nothing for the rest of the drive (5.8 s of a 61 s trip). So there
 * is one recorder per process ({@link #get}), it registers its own receiver on
 * the application context, and an activity going away only flushes it.
 *
 * <p>Everything runs on one worker thread: the receiver, the engine, the
 * database, the GPS callbacks. The page reads through {@link #liveJson()} (a
 * volatile string refreshed each tick) and the query methods, never through a
 * per-signal push.
 */
final class TripRecorder implements TripEngine.Listener {
    private static final String TAG = "H6Trip";
    private static final String ACTION_VEHICLE_EVENT_CHANGED = "com.haval.vehicle.EVENT_CHANGED";
    private static final String ACTION_CAR_DATA_UPDATE = "br.com.redesurftank.havalshisuku.CAR_DATA_UPDATE";
    private static final long TICK_MS = 1_000L;
    /** The MMI can lose power with no warning; lose at most this much. */
    private static final long CHECKPOINT_MS = 15_000L;
    private static final int POINT_FLUSH = 30;
    private static final String ACTION_REQUEST_SNAPSHOT = "com.haval.vehicle.REQUEST_SNAPSHOT";
    private static final String IMPULSE_PACKAGE = "br.com.redesurftank.havalshisuku";
    private static final long GPS_MIN_MS = 1_000L;

    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            TripEngine.KEY_READY, TripEngine.KEY_SPEED, TripEngine.KEY_ODOMETER,
            TripEngine.KEY_VOLTAGE, TripEngine.KEY_CURRENT, TripEngine.KEY_FUEL_INST,
            TripEngine.KEY_ICE, TripEngine.KEY_FLOW, TripEngine.KEY_FUEL_PCT, TripEngine.KEY_NAV, TripEngine.KEY_SOC));

    private static TripRecorder instance;

    private final Context app;
    private final HandlerThread thread;
    private final Handler worker;
    private volatile TripStore store;
    private volatile TripMapWorker maps;
    private TripEngine engine;
    /** Points not saved yet. The bridge thread copies them for the live map, so both are guarded by the list. */
    private final List<TripPoint> pending = new ArrayList<>();
    private long pendingStartMs;
    private long lastCheckpointAt;
    private long lastTickElapsed;
    private String lastReadyRaw;
    private volatile String liveJson = "";
    private boolean gpsOn;

    private final BroadcastReceiver signals = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String key = intent.getStringExtra("key");
            String value = intent.getStringExtra("value");
            if (key == null || value == null) return;
            if (!KEYS.contains(key) && !TripEngine.RAW_KEYS.contains(key)) return;
            onSignal(key, value, now());
        }
    };

    private final LocationListener gps = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            if (engine == null || loc == null) return;
            engine.onLocation(loc.getLatitude(), loc.getLongitude(),
                    loc.hasAltitude() ? loc.getAltitude() : Double.NaN,
                    loc.hasAccuracy() ? loc.getAccuracy() : 30.0, now());
        }

        // Abstract on the MMI's Android 9: without these the framework's call
        // throws AbstractMethodError, even though compileSdk has defaults.
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                onTick(now());
            } catch (Throwable t) {
                Log.w(TAG, "tick failed", t);
            }
            worker.postDelayed(this, TICK_MS);
        }
    };

    /** The one recorder for this process, started on first use. */
    static synchronized TripRecorder get(Context context) {
        if (instance == null) instance = new TripRecorder(context.getApplicationContext());
        return instance;
    }

    private TripRecorder(Context appContext) {
        this.app = appContext;
        thread = new HandlerThread("h6-trip");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                TripStore s = new TripStore(app);
                engine = new TripEngine(this);
                store = s;
                maps = new TripMapWorker(app, s);
                // Catch up on trips closed while offline or recorded before maps existed.
                maps.sweepSoon(20_000L);
                String saved = s.loadCheckpoint();
                if (saved != null) engine.restore(TripState.decode(saved), now());
                // A refuel made while this process was not running is still a refuel.
                TripStore.Parked parked = s.lastParked();
                if (parked != null) engine.primeParked(parked.fuelPct, parked.soc, parked.lat, parked.lon, parked.endMs);
            } catch (Throwable t) {
                Log.w(TAG, "trip store unavailable", t);
            }
            try {
                IntentFilter filter = new IntentFilter(ACTION_VEHICLE_EVENT_CHANGED);
                filter.addAction(ACTION_CAR_DATA_UPDATE);
                // Delivered on the worker: no hop through the main thread, and
                // the signal is stamped where it is integrated.
                app.registerReceiver(signals, filter, null, worker);
            } catch (Exception e) {
                Log.w(TAG, "trip telemetry receiver not registered", e);
            }
            worker.post(tick);
        });
    }

    /** Called when the activity goes away: put what is held on disk, keep recording. */
    void persistSoon() {
        worker.post(() -> persist(now()));
    }

    /** FINALIZAR VIAGEM from the page: end the open trip now; keep recording if still READY. */
    void finishTrip() {
        worker.post(() -> {
            if (engine == null) return;
            long t = now();
            Log.w(TAG, "trip finished by the driver");
            engine.finishNow(t);
            TripSummary live = engine.live(t);
            liveJson = live == null ? "" : TripStore.summaryJson(live);
            persist(t);
        });
    }

    String liveJson() {
        return liveJson;
    }

    String tripsJson(int offset, int limit) {
        TripStore s = store;
        return s == null ? "[]" : s.tripsJson(offset, limit);
    }

    String tripJson(long startMs, int maxPoints) {
        TripStore s = store;
        return s == null ? "null" : s.tripJson(startMs, maxPoints);
    }

    /** The open trip's route so far, saved and still held (TripStore.livePointsJson). */
    String livePointsJson(long startMs, long afterT, int maxPoints) {
        TripStore s = store;
        if (s == null) return "null";
        List<TripPoint> held;
        synchronized (pending) {
            held = pendingStartMs == startMs ? new ArrayList<>(pending) : new ArrayList<>();
        }
        return s.livePointsJson(startMs, afterT, maxPoints, held);
    }

    String daysJson(String from, String to) {
        TripStore s = store;
        return s == null ? "[]" : s.daysJson(from, to);
    }

    String monthsJson() {
        TripStore s = store;
        return s == null ? "[]" : s.monthsJson();
    }

    String totalsJson() {
        TripStore s = store;
        return s == null ? "{}" : s.totalsJson();
    }

    String stopTotalsJson(long fromMs) {
        TripStore s = store;
        return s == null ? "{}" : s.stopTotalsJson(fromMs);
    }

    String stopsJson(long fromMs) {
        TripStore s = store;
        return s == null ? "[]" : s.stopsJson(fromMs);
    }

    String tripMapDataUrl(long startMs) {
        TripMapWorker m = maps;
        return m == null ? "" : m.dataUrl(startMs);
    }

    // ── engine callbacks (worker thread) ───────────────────────────────────

    @Override
    public void onTripOpened(long startMs) {
        synchronized (pending) {
            pending.clear();
            pendingStartMs = startMs;
        }
        Log.w(TAG, "trip opened " + startMs);
    }

    @Override
    public void onPoint(long startMs, TripPoint point) {
        boolean full;
        synchronized (pending) {
            if (startMs != pendingStartMs) {
                pending.clear();
                pendingStartMs = startMs;
            }
            pending.add(point);
            full = pending.size() >= POINT_FLUSH;
        }
        if (full) flushPoints();
    }

    @Override
    public void onTripClosed(TripSummary summary) {
        TripStore s = store;
        if (s == null) return;
        flushPoints();
        s.insertTrip(summary);
        s.saveRoute(summary.startMs, TripStore.ROUTE_POINTS);
        s.clearCheckpoint();
        TripMapWorker m = maps;
        if (m != null) m.sweepSoon(5_000L);
        Log.w(TAG, String.format(Locale.US,
                "trip closed %d: %.1f km (int %.2f, odo jumps %d) fuel %.2f L out %.2f kWh in %.2f kWh ev %.0f%% raw=%s",
                summary.startMs, summary.km, summary.kmIntegrated, summary.odoJumps, summary.fuelL,
                summary.kwhOut, summary.kwhIn, summary.evShare() * 100, summary.rawLast));
    }

    @Override
    public void onStop(long startMs, TripStop stop) {
        TripStore s = store;
        if (s == null) return;
        s.insertStop(startMs, stop);
        Log.w(TAG, String.format(Locale.US, "trip stop %d: %s %.0f%% -> %.0f%% (%.1f)",
                startMs, stop.kind, stop.before, stop.after, stop.amount));
    }

    @Override
    public void onTripDiscarded(long startMs) {
        TripStore s = store;
        synchronized (pending) {
            pending.clear();
        }
        if (s == null) return;
        s.deletePoints(startMs);
        s.clearCheckpoint();
        Log.w(TAG, "trip discarded " + startMs);
    }

    // ── internals (worker thread) ──────────────────────────────────────────

    private void onSignal(String key, String value, long t) {
        if (engine == null) return;
        if (TripEngine.KEY_NAV.equals(key)) {
            onNavigation(value, t);
            return;
        }
        if (TripEngine.KEY_READY.equals(key) && !value.equals(lastReadyRaw)) {
            // The car publishes more than 0 / 1; the raw values are what a drive log needs.
            lastReadyRaw = value;
            Log.w(TAG, "ready " + value);
        }
        boolean wasOpen = engine.isOpen();
        engine.onSignal(key, value, t);
        // READY edges are exactly when the power may be about to go.
        if (TripEngine.KEY_READY.equals(key) || wasOpen != engine.isOpen()) persist(t);
    }

    /**
     * Impulse publishes {"active":false} or a full object with remaining_m /
     * remaining_s (AndroidAutoNavigationTelemetry.Directions.toJson). There is no
     * destination name in it; the trip's end place comes from the geocoder.
     */
    private void onNavigation(String value, long t) {
        try {
            JSONObject o = new JSONObject(value);
            double remainingM = o.isNull("remaining_m") ? Double.NaN : o.optDouble("remaining_m", Double.NaN);
            double remainingS = o.isNull("remaining_s") ? Double.NaN : o.optDouble("remaining_s", Double.NaN);
            engine.onNavigation(o.optBoolean("active", false), remainingM, remainingS,
                    "DESTINATION".equals(o.optString("turn", "")), t);
        } catch (Exception ignored) {
            // A malformed frame must not end a guided trip.
        }
    }

    private void onTick(long t) {
        if (engine == null) return;
        // Ticks are 1 s apart. elapsedRealtime keeps counting through deep sleep and
        // ignores wall-clock corrections, so a gap here means the head unit slept:
        // end a trip left open across it, and ask Impulse to replay its values so
        // READY is current again.
        long elapsed = SystemClock.elapsedRealtime();
        if (lastTickElapsed > 0 && elapsed - lastTickElapsed >= TripEngine.MERGE_GAP_MS) {
            Log.w(TAG, "woke after " + (elapsed - lastTickElapsed) / 1000 + " s");
            engine.closeInterrupted(t);
            requestSnapshot("woke from sleep");
        }
        lastTickElapsed = elapsed;
        engine.tick(t);
        TripSummary live = engine.live(t);
        liveJson = live == null ? "" : TripStore.summaryJson(live);
        setGps(engine.isOpen());
        if (engine.isOpen() && t - lastCheckpointAt >= CHECKPOINT_MS) persist(t);
    }

    /** Asks Impulse to re-broadcast every cached car value. */
    private void requestSnapshot(String why) {
        try {
            Intent request = new Intent(ACTION_REQUEST_SNAPSHOT);
            request.setPackage(IMPULSE_PACKAGE);
            request.putExtra("requester", app.getPackageName());
            app.sendBroadcast(request);
            Log.w(TAG, "snapshot requested: " + why);
        } catch (Exception e) {
            Log.w(TAG, "snapshot request failed", e);
        }
    }

    private void persist(long t) {
        TripStore s = store;
        if (s == null || engine == null) return;
        lastCheckpointAt = t;
        if (engine.isOpen()) {
            flushPoints();
            s.saveCheckpoint(engine.checkpoint(), t);
        }
    }

    private void flushPoints() {
        TripStore s = store;
        if (s == null) return;
        List<TripPoint> batch;
        long startMs;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            startMs = pendingStartMs;
        }
        // Saved before it leaves the buffer, so a live read always finds it in one or the other.
        s.appendPoints(startMs, batch);
        synchronized (pending) {
            pending.subList(0, Math.min(batch.size(), pending.size())).clear();
        }
    }

    private void setGps(boolean on) {
        if (on == gpsOn) return;
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        if (!on) {
            lm.removeUpdates(gps);
            gpsOn = false;
            return;
        }
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;  // re-tried every tick; MainActivity asks for the grant
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_MIN_MS, 0f, gps, thread.getLooper());
            gpsOn = true;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "GPS updates unavailable: " + e.getMessage());
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
