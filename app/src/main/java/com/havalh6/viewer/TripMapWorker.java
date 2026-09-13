package com.havalh6.viewer;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Fills in what a closed trip needs after the fact: the compact route kept
 * forever, the map snapshot, and the start / end place names.
 *
 * <p>Its own thread, because tile fetches and geocodes block for seconds and
 * the recorder's thread integrates the drive once a second. Work is a sweep over
 * the database rather than a queue, so nothing is lost across a restart or a
 * stretch offline: a trip is pending while its route, snapshot or place names are
 * still NULL. An empty string means "tried, nothing to show" (no GPS fix), which
 * ends the retries.
 */
final class TripMapWorker {
    private static final String TAG = "H6Trip";
    private static final long SWEEP_MS = 10 * 60_000L;
    /** Only failures that are not simply "offline" count toward this. */
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH = 5;
    private static final int MAX_ROUTE_POINTS = 800;

    private final TripStore store;
    private final TripMapRenderer renderer;
    private final Handler handler;

    private final Runnable sweep = new Runnable() {
        @Override
        public void run() {
            try {
                sweepOnce();
            } catch (Throwable t) {
                Log.w(TAG, "trip map sweep failed", t);
            }
            handler.removeCallbacks(this);
            handler.postDelayed(this, SWEEP_MS);
        }
    };

    TripMapWorker(Context context, TripStore store) {
        this.store = store;
        this.renderer = new TripMapRenderer(context.getApplicationContext());
        HandlerThread thread = new HandlerThread("h6-trip-map");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Run a sweep after {@code delayMs}, replacing any sooner or later one already queued. */
    void sweepSoon(long delayMs) {
        handler.removeCallbacks(sweep);
        handler.postDelayed(sweep, delayMs);
    }

    /** The trip's snapshot as a data URL for an {@code <img src>}, or "" when there is none. */
    String dataUrl(long startMs) {
        File file = renderer.fileFor(startMs);
        if (!file.isFile() || file.length() > 1_000_000L) return "";
        byte[] data = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
            if (off != data.length) return "";
        } catch (Exception e) {
            return "";
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private void sweepOnce() {
        // Compact routes first: they need no network, and trips recorded before
        // routes were kept still have their per-second samples for a while.
        for (Long startMs : store.pendingRoutes(20)) store.saveRoute(startMs, TripStore.ROUTE_POINTS);

        List<TripStore.PendingMap> pending = store.pendingMaps(MAX_ATTEMPTS, BATCH);
        for (TripStore.PendingMap trip : pending) {
            if (trip.needMap) {
                double[][] fixes = store.fixes(trip.startMs, MAX_ROUTE_POINTS);
                if (fixes[0].length < 2) {
                    store.setTripMap(trip.startMs, "", null);
                } else {
                    TripMapRenderer.Result result;
                    try {
                        result = renderer.render(trip.startMs, fixes[0], fixes[1]);
                    } catch (Exception e) {
                        Log.w(TAG, "trip map render failed for " + trip.startMs, e);
                        store.bumpMapAttempts(trip.startMs);
                        continue;
                    }
                    // Null is "a tile did not come": offline. Stop the sweep, try later.
                    if (result == null) return;
                    store.setTripMap(trip.startMs, result.file.getName(), result.view);
                }
            }
            if (trip.needPlaces) {
                if (Double.isNaN(trip.startLat) || Double.isNaN(trip.endLat)) {
                    store.setTripPlaces(trip.startMs, "", "");
                    continue;
                }
                ReverseGeocoder.Place start = ReverseGeocoder.lookup(trip.startLat, trip.startLon);
                if (start == null) return;
                ReverseGeocoder.Place end = ReverseGeocoder.lookup(trip.endLat, trip.endLon);
                if (end == null) return;
                store.setTripPlaces(trip.startMs, start.label(), end.label());
                Log.w(TAG, "trip places " + trip.startMs + ": " + start.label() + " -> " + end.label());
            }
        }
    }
}
