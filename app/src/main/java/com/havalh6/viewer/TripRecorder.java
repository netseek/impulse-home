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
 * hidden, Chromium stops servicing rAF and clamps timers to 1 Hz (docs/engineering), so
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
            TripEngine.KEY_ICE, TripEngine.KEY_FLOW, TripEngine.KEY_FUEL_PCT, TripEngine.KEY_NAV, TripEngine.KEY_SOC,
            RangeLedger.KEY_EV_RANGE,
            TripSetting.KEY_DRIVE_MODE, TripSetting.KEY_POWER_MODE, TripSetting.KEY_RESERVE, TripSetting.KEY_SOC_TARGET));

    private static TripRecorder instance;

    private final Context app;
    private final HandlerThread thread;
    private final Handler worker;
    private volatile TripStore store;
    private volatile TripMapWorker maps;
    private TripEngine engine;
    /** The range forecast check; null only if the store could not open. */
    private RangeLedger ledger;
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
                // Before the DB is opened: a fresh install takes the shared-storage copy.
                PersistBackup.restoreIfFresh(app);
                TripStore s = new TripStore(app);
                engine = new TripEngine(this);
                store = s;
                ledger = new RangeLedger(s);
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
            worker.postDelayed(backupTick, BACKUP_MS);
        });
    }

    /** trips.db copied to shared storage (PersistBackup) this often, and on persistSoon. */
    private static final long BACKUP_MS = 10 * 60_000L;

    private final Runnable backupTick = new Runnable() {
        @Override
        public void run() {
            backupNow();
            worker.postDelayed(this, BACKUP_MS);
        }
    };

    private void backupNow() {
        TripStore s = store;
        if (s == null) return;
        try {
            PersistBackup.backupTrips(app, s.getWritableDatabase());
        } catch (RuntimeException e) {
            Log.w(TAG, "trips backup failed", e);
        }
    }

    /** Called when the activity goes away: put what is held on disk, keep recording. */
    void persistSoon() {
        worker.post(() -> {
            persist(now());
            backupNow();
        });
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

    /**
     * The page's history-based EV range and the SOC it was worked out for
     * (RangeLedger.onHistForecast). NaN when it has none.
     */
    void rangeForecast(double km, double atSoc) {
        worker.post(() -> {
            RangeLedger l = ledger;
            if (l != null) l.onHistForecast(km, atSoc, now());
        });
    }

    String rangeCyclesJson(int limit) {
        TripStore s = store;
        return s == null ? "[]" : s.rangeCyclesJson(limit);
    }

    String tripMapDataUrl(long startMs) {
        TripMapWorker m = maps;
        return m == null ? "" : m.dataUrl(startMs);
    }

    // ── EXPORTAR VIAGENS ───────────────────────────────────────────────────

    /** {state: idle | running | done | error, ...}; read by the page while it waits. */
    private volatile String exportJson = "{\"state\":\"idle\"}";
    private Thread exportThread;

    String exportStatusJson() {
        return exportJson;
    }

    /**
     * Writes the stored trips (all of them, or only the start times in
     * {@code only}) to shared storage (TripStore.exportTrips) on a thread of its
     * own: a long history takes seconds, and on the worker it would hold back the
     * signals being integrated. One export at a time.
     */
    synchronized String exportTrips(java.util.Set<Long> only) {
        if (exportThread != null && exportThread.isAlive()) return exportJson;
        TripStore s = store;
        if (s == null) return exportJson = exportError("unavailable");
        if (only != null && only.isEmpty()) return exportJson = exportError("empty");
        if (!PersistBackup.hasPermission(app)) return exportJson = exportError("permission");
        exportJson = "{\"state\":\"running\"}";
        exportThread = new Thread(() -> runExport(s, only), "h6-trip-export");
        exportThread.setPriority(Thread.MIN_PRIORITY);
        exportThread.start();
        return exportJson;
    }

    private void runExport(TripStore s, java.util.Set<Long> only) {
        long t = now();
        java.io.File dir = new java.io.File(PersistBackup.dir(), TripExport.DIR);
        String stem = TripExport.fileStem(t);
        java.io.File json = new java.io.File(dir, stem + ".json");
        java.io.File csv = new java.io.File(dir, stem + ".csv");
        java.io.File jsonTmp = new java.io.File(dir, stem + ".json.tmp");
        java.io.File csvTmp = new java.io.File(dir, stem + ".csv.tmp");
        java.io.File zip = new java.io.File(dir, stem + ".zip");
        java.io.File zipTmp = new java.io.File(dir, stem + ".zip.tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("mkdirs " + dir);
            TripStore.ExportCount n;
            try (java.io.Writer jw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                         new java.io.FileOutputStream(jsonTmp), java.nio.charset.StandardCharsets.UTF_8), 64 * 1024);
                 java.io.Writer cw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                         new java.io.FileOutputStream(csvTmp), java.nio.charset.StandardCharsets.UTF_8))) {
                // A BOM, so a spreadsheet opens the place names as UTF-8.
                cw.write('﻿');
                n = s.exportTrips(jw, cw, t, only);
            }
            // Renamed only once complete: a half-written export never looks like one.
            if (!jsonTmp.renameTo(json) || !csvTmp.renameTo(csv)) throw new java.io.IOException("rename");
            // Both in one zip for ENVIAR POR BLUETOOTH (TripExportProvider).
            TripExport.zip(zipTmp, json, csv);
            if (!zipTmp.renameTo(zip)) throw new java.io.IOException("rename zip");
            android.media.MediaScannerConnection.scanFile(app,
                    new String[]{json.getAbsolutePath(), csv.getAbsolutePath(), zip.getAbsolutePath()}, null, null);
            JSONObject o = new JSONObject();
            o.put("state", "done");
            o.put("dir", dir.getAbsolutePath());
            o.put("json", json.getName());
            o.put("csv", csv.getName());
            o.put("zip", zip.getName());
            o.put("zipBytes", zip.length());
            // Asked once here, so the page never queries the package manager while it renders.
            o.put("bluetooth", TripExportProvider.canSendBluetooth(app));
            o.put("scope", only == null ? "all" : "selection");
            o.put("trips", n.trips);
            o.put("points", n.points);
            o.put("stops", n.stops);
            o.put("bytes", json.length() + csv.length());
            o.put("ms", now() - t);
            exportJson = o.toString();
            Log.w(TAG, "trips exported: " + exportJson);
        } catch (Exception e) {
            Log.w(TAG, "trip export failed", e);
            jsonTmp.delete();
            csvTmp.delete();
            zipTmp.delete();
            exportJson = exportError("write");
        }
    }

    /**
     * QR CODE: serves the export behind {@code zipName} on the unit's Wi-Fi
     * (TripExportLink) and returns {state: on, ttlMs, links: [{label, url, qr,
     * size}]}, the code as an SVG path in module units; or {state: error,
     * reason: missing | no-wifi | failed}.
     */
    String exportLinkJson(String zipName) {
        try {
            JSONObject o = new JSONObject();
            java.io.File dir = new java.io.File(PersistBackup.dir(), TripExport.DIR);
            if (!TripExport.isZipName(zipName) || !new java.io.File(dir, zipName).isFile()) {
                return exportError("missing");
            }
            String stem = zipName.substring(0, zipName.length() - 4);
            List<TripExportLink.Link> links = TripExportLink.start(app, dir, stem);
            if (links.isEmpty()) return exportError("no-wifi");
            org.json.JSONArray arr = new org.json.JSONArray();
            for (TripExportLink.Link l : links) {
                io.nayuki.qrcodegen.QrCode qr = io.nayuki.qrcodegen.QrCode.encodeText(l.url, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
                JSONObject j = new JSONObject();
                j.put("label", l.label);
                j.put("url", l.url);
                j.put("qr", TripExport.qrSvgPath(qr));
                j.put("size", qr.size + 8);
                arr.put(j);
            }
            o.put("state", "on");
            o.put("ttlMs", TripExportLink.TTL_MS);
            o.put("links", arr);
            return o.toString();
        } catch (Exception e) {
            Log.w(TAG, "export link failed", e);
            return exportError("failed");
        }
    }

    void stopExportLink() {
        TripExportLink.stop();
    }

    boolean sendExportBluetooth(String zipName) {
        return TripExportProvider.sendBluetooth(app, zipName);
    }

    private static String exportError(String reason) {
        return "{\"state\":\"error\",\"reason\":\"" + reason + "\"}";
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
        RangeLedger l = ledger;
        if (l != null && TripStop.CHARGE.equals(stop.kind)) l.onCharge();
        Log.w(TAG, String.format(Locale.US, "trip stop %d: %s %.0f%% -> %.0f%% (%.1f)",
                startMs, stop.kind, stop.before, stop.after, stop.amount));
    }

    @Override
    public void onSetting(long startMs, TripSetting setting) {
        TripStore s = store;
        if (s == null) return;
        s.insertSetting(startMs, setting);
        Log.w(TAG, String.format(Locale.US, "trip setting %d: %s at %.2f km",
                startMs, setting.label(), setting.km));
    }

    @Override
    public void onTripDiscarded(long startMs) {
        TripStore s = store;
        synchronized (pending) {
            pending.clear();
        }
        if (s == null) return;
        s.deletePoints(startMs);
        s.deleteSettings(startMs);
        s.clearCheckpoint();
        Log.w(TAG, "trip discarded " + startMs);
    }

    // ── internals (worker thread) ──────────────────────────────────────────

    private void onSignal(String key, String value, long t) {
        if (engine == null) return;
        RangeLedger l = ledger;
        if (l != null) l.onSignal(key, value);
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
        RangeLedger l = ledger;
        if (l != null) {
            // Its own guard: a failure here must never stop a trip being recorded.
            try {
                if (live == null) l.onTick(t, 0L, 0, 0);
                else l.onTick(t, live.startMs, live.evKm, live.km);
            } catch (RuntimeException e) {
                Log.w(TAG, "range ledger tick failed", e);
            }
        }
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
        RangeLedger l = ledger;
        if (l != null) {
            try {
                l.flush(t);
            } catch (RuntimeException e) {
                Log.w(TAG, "range ledger flush failed", e);
            }
        }
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
