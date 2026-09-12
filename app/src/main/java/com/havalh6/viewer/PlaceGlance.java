package com.havalh6.viewer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort neighborhood / city / street when Android Auto is not guiding.
 *
 * <p>Uses last-known GPS or network location and reverse geocoding. No
 * permission dialog from here: if the grant is missing the glance stays
 * silent, which is the honest idle state rather than an invented city.
 * Publish is throttled so a moving car cannot rebuild the navigation card
 * at GPS rate.
 *
 * <p><b>The platform Geocoder does not work on this MMI.</b> Measured on the
 * car 2026-09-11: {@code Geocoder.isPresent()} returns true, then every
 * {@code getFromLocation} throws {@code Service not Available}, because the
 * geocode backend is supplied by the same package that implements the network
 * location provider and {@code dumpsys location} reports
 * {@code Overlay Provider Packages: network: null} — GMS here is
 * ReVanced-patched and registers neither. So the platform call is tried once
 * and, on failure, latched off ({@link #platformGeocoderDead}) in favour of an
 * HTTP reverse geocode. The platform path is kept because it is free and
 * offline on a normal ROM; the HTTP path is what actually answers on this one.
 */
final class PlaceGlance {
    static final String KEY = "app.location.place";
    private static final String TAG = "H6Viewer";
    private static final long PERIOD_MS = 45_000L;
    /** Below this, the car has not left the block; skip the network round trip. */
    private static final float REGEOCODE_MIN_MOVE_M = 120f;
    private static final int HTTP_TIMEOUT_MS = 8_000;
    /** Nominatim's usage policy requires an identifying User-Agent. */
    private static final String HTTP_UA = "HavalH6Viewer/1.0 (head-unit navigation glance)";

    interface Listener {
        void onPlace(String json);
    }

    private final Context app;
    private final Handler main;
    private final Listener listener;
    private Handler worker;
    private boolean started;
    private String lastJson = "";
    private boolean platformGeocoderDead;
    /** Where the last SUCCESSFUL reverse geocode was taken, for the move gate. */
    private Location lastFixed;

    /** Neighborhood / city / street, any of which may be empty when unresolved.
     * neighborhood is the finer, suburb-level hit; city is the town/municipality
     * that contains it — two different levels of the same reverse geocode, not
     * a fallback chain into each other. */
    private static final class Place {
        String neighborhood = "";
        String city = "";
        String street = "";

        boolean isEmpty() {
            return neighborhood.isEmpty() && city.isEmpty() && street.isEmpty();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                pollOnce();
            } catch (Throwable t) {
                Log.w(TAG, "PlaceGlance poll failed", t);
            }
            if (worker != null) worker.postDelayed(this, PERIOD_MS);
        }
    };

    PlaceGlance(Context context, Handler mainHandler, Listener listener) {
        this.app = context.getApplicationContext();
        this.main = mainHandler;
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        HandlerThread thread = new HandlerThread("h6-place");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(tick);
    }

    void stop() {
        started = false;
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            worker.getLooper().quitSafely();
            worker = null;
        }
    }

    /**
     * Poll immediately instead of waiting out the current 45 s gap.
     *
     * <p>Called when the location permission is granted mid-session: the worker
     * is already ticking, but every tick so far returned on its first line, and
     * a driver who has just answered the dialog should not stare at an em dash
     * for another three quarters of a minute.
     */
    void pokeNow() {
        final Handler w = worker;
        if (w == null) return;
        w.post(() -> {
            try {
                pollOnce();
            } catch (Throwable t) {
                Log.w(TAG, "PlaceGlance poke failed", t);
            }
        });
    }

    private boolean hasLocationPermission() {
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void pollOnce() {
        if (!hasLocationPermission()) return;
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        Location best = null;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        }) {
            try {
                Location next = lm.getLastKnownLocation(provider);
                if (next == null) continue;
                if (best == null || next.getTime() > best.getTime()) best = next;
            } catch (SecurityException ignored) {
                return;
            } catch (Throwable ignored) {}
        }
        if (best == null) return;
        // Still where we were when the current answer was resolved: that answer
        // is still correct, so do not spend a request confirming it.
        if (lastFixed != null && !lastJson.isEmpty()
                && best.distanceTo(lastFixed) < REGEOCODE_MIN_MOVE_M) {
            return;
        }
        Place place = reverseGeocode(best);
        if (place == null || place.isEmpty()) return;
        lastFixed = best;
        String provider = best.getProvider() == null ? "gps" : best.getProvider();
        try {
            JSONObject o = new JSONObject();
            o.put("neighborhood", place.neighborhood);
            o.put("city", place.city);
            o.put("street", place.street);
            o.put("provider", provider);
            String json = o.toString();
            if (json.equals(lastJson)) return;
            lastJson = json;
            main.post(() -> listener.onPlace(json));
        } catch (Exception ignored) {}
    }

    /** Platform geocoder while it still answers, HTTP once it has proved it will not. */
    private Place reverseGeocode(Location at) {
        if (!platformGeocoderDead) {
            Place local = geocodePlatform(at);
            if (local != null && !local.isEmpty()) return local;
        }
        return geocodeHttp(at);
    }

    private Place geocodePlatform(Location at) {
        if (!Geocoder.isPresent()) {
            platformGeocoderDead = true;
            return null;
        }
        try {
            List<Address> results = new Geocoder(app, new Locale("pt", "BR"))
                    .getFromLocation(at.getLatitude(), at.getLongitude(), 1);
            if (results == null || results.isEmpty()) return null;
            Address a = results.get(0);
            Place place = new Place();
            if (a.getSubLocality() != null && !a.getSubLocality().isEmpty()) {
                place.neighborhood = a.getSubLocality();
            }
            if (a.getLocality() != null && !a.getLocality().isEmpty()) {
                place.city = a.getLocality();
            } else if (a.getSubAdminArea() != null) {
                place.city = a.getSubAdminArea();
            }
            if (a.getThoroughfare() != null) place.street = a.getThoroughfare();
            return place;
        } catch (Exception e) {
            // "Service not Available" means no backend is bound, and no later
            // call will find one. Latch it off rather than throwing every tick.
            platformGeocoderDead = true;
            Log.w(TAG, "PlaceGlance platform geocoder unavailable ("
                    + e.getMessage() + ") — using HTTP reverse geocode");
            return null;
        }
    }

    /**
     * Nominatim reverse geocode. One request per poll at most, gated further by
     * {@link #REGEOCODE_MIN_MOVE_M}, which keeps this well inside the service's
     * 1 req/s policy even at motorway speed.
     */
    private Place geocodeHttp(Location at) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2"
                            + "&lat=%.6f&lon=%.6f&zoom=17&addressdetails=1&accept-language=pt-BR",
                    at.getLatitude(), at.getLongitude()));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", HTTP_UA);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "PlaceGlance reverse geocode HTTP " + code);
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return parseNominatim(body.toString());
        } catch (Exception e) {
            // Offline, or the car's link is down. Silent is the honest state.
            Log.w(TAG, "PlaceGlance reverse geocode failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Nominatim's address object is not a fixed shape — which field carries
     * "the place you are in" depends on how the area was mapped. neighborhood
     * and city are two different LEVELS of the same address, not a fallback
     * chain into each other: walk suburb-level keys for one and city-level keys
     * for the other, same order the platform path walks subLocality then
     * locality -> subAdminArea.
     */
    private Place parseNominatim(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject address = root.optJSONObject("address");
            if (address == null) return null;
            Place place = new Place();
            for (String key : new String[]{"suburb", "neighbourhood", "city_district"}) {
                String value = address.optString(key, "").trim();
                if (!value.isEmpty()) {
                    place.neighborhood = value;
                    break;
                }
            }
            for (String key : new String[]{"city", "town", "village", "municipality", "county"}) {
                String value = address.optString(key, "").trim();
                if (!value.isEmpty()) {
                    place.city = value;
                    break;
                }
            }
            for (String key : new String[]{"road", "pedestrian", "footway"}) {
                String value = address.optString(key, "").trim();
                if (!value.isEmpty()) {
                    place.street = value;
                    break;
                }
            }
            return place;
        } catch (Exception e) {
            Log.w(TAG, "PlaceGlance reverse geocode parse failed: " + e.getMessage());
            return null;
        }
    }
}
