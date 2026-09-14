package com.havalh6.viewer;

import android.webkit.JavascriptInterface;

/**
 * {@code window.TripBridge}: read-only access to recorded trips.
 *
 * <p>Pull, not push. The page asks when it needs to draw (a settle point or a
 * low-rate timer); nothing here calls into the WebView, so recording can never
 * put a React commit on the bus's cadence.
 */
final class TripBridge {
    private static final int MAX_POINTS = 1500;
    private final TripRecorder recorder;

    TripBridge(TripRecorder recorder) {
        this.recorder = recorder;
    }

    /** The open trip's totals, or "" when there is none. */
    @JavascriptInterface
    public String getLiveTrip() {
        return recorder.liveJson();
    }

    @JavascriptInterface
    public String getTrips(int offset, int limit) {
        return recorder.tripsJson(offset, limit);
    }

    /**
     * One trip with its samples, its compact route (kept forever) and its
     * refuel / charge stops. {@code startMs} as a string: JS numbers do not cross
     * the bridge as a long reliably.
     */
    @JavascriptInterface
    public String getTrip(String startMs, int maxPoints) {
        try {
            int cap = maxPoints <= 0 ? MAX_POINTS : Math.min(MAX_POINTS, maxPoints);
            return recorder.tripJson(Long.parseLong(startMs.trim()), cap);
        } catch (RuntimeException e) {
            return "null";
        }
    }

    /**
     * The trip's map snapshot as a {@code data:image/jpeg} URL, or "" while it
     * has not been drawn (offline) or cannot be (no GPS fix). Bind it to an
     * {@code <img src>}, never a style: the template engine splits a style on
     * ";" and a data URL always contains one (docs/ui-surfaces.md).
     */
    @JavascriptInterface
    public String getTripMap(String startMs) {
        try {
            return recorder.tripMapDataUrl(Long.parseLong(startMs.trim()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Days as {@code yyyy-MM-dd}, inclusive. */
    @JavascriptInterface
    public String getDays(String fromDay, String toDay) {
        return recorder.daysJson(fromDay, toDay);
    }

    @JavascriptInterface
    public String getMonths() {
        return recorder.monthsJson();
    }

    /** Everything ever recorded: distance (EV / engine), fuel, electricity, stops, measured battery capacity. */
    @JavascriptInterface
    public String getTotals() {
        return recorder.totalsJson();
    }

    /** FINALIZAR VIAGEM: ends the open trip; a new one starts at once if the car is still READY. */
    @JavascriptInterface
    public void finishTrip() {
        recorder.finishTrip();
    }
}
