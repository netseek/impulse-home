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

import java.util.List;
import java.util.Locale;

/**
 * Best-effort city / street when Android Auto is not guiding.
 *
 * <p>Uses last-known GPS or network location and the platform Geocoder. No
 * permission dialog: if the grant is missing the glance stays silent, which
 * is the honest idle state rather than a invented city. Publish is throttled
 * so a moving car cannot rebuild the navigation card at GPS rate.
 */
final class PlaceGlance {
    static final String KEY = "app.location.place";
    private static final String TAG = "H6Viewer";
    private static final long PERIOD_MS = 45_000L;

    interface Listener {
        void onPlace(String json);
    }

    private final Context app;
    private final Handler main;
    private final Listener listener;
    private Handler worker;
    private boolean started;
    private String lastJson = "";

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
        String city = "";
        String street = "";
        String provider = best.getProvider() == null ? "gps" : best.getProvider();
        if (Geocoder.isPresent()) {
            try {
                List<Address> results = new Geocoder(app, new Locale("pt", "BR"))
                        .getFromLocation(best.getLatitude(), best.getLongitude(), 1);
                if (results != null && !results.isEmpty()) {
                    Address a = results.get(0);
                    if (a.getSubLocality() != null && !a.getSubLocality().isEmpty()) {
                        city = a.getSubLocality();
                    } else if (a.getLocality() != null) {
                        city = a.getLocality();
                    } else if (a.getSubAdminArea() != null) {
                        city = a.getSubAdminArea();
                    }
                    if (a.getThoroughfare() != null) street = a.getThoroughfare();
                }
            } catch (Exception e) {
                Log.w(TAG, "PlaceGlance geocode failed: " + e.getMessage());
            }
        }
        if (city.isEmpty() && street.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("city", city);
            o.put("street", street);
            o.put("provider", provider);
            String json = o.toString();
            if (json.equals(lastJson)) return;
            lastJson = json;
            main.post(() -> listener.onPlace(json));
        } catch (Exception ignored) {}
    }
}
