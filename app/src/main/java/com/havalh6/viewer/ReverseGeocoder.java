package com.havalh6.viewer;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Nominatim reverse geocoding, shared by {@link PlaceGlance} (the navigation
 * card's idle city) and {@link TripMapWorker} (a trip's start and end names).
 *
 * <p>The platform {@code Geocoder} does not work on this MMI (see PlaceGlance),
 * so this HTTP path is the one that actually answers. Nominatim's usage policy
 * asks for an identifying User-Agent and at most one request per second; with
 * two callers now, the rate limit is enforced here, across the whole process.
 */
final class ReverseGeocoder {
    private static final String TAG = "H6Viewer";
    private static final int HTTP_TIMEOUT_MS = 8_000;
    private static final String HTTP_UA = "HavalH6Viewer/1.0 (head-unit navigation glance)";
    private static final long MIN_GAP_MS = 1_100L;
    private static final Object RATE_LOCK = new Object();
    private static long lastRequestAt;

    /**
     * Neighborhood / city / street, any of which may be empty. neighborhood is
     * the finer, suburb-level hit; city is the town that contains it -- two
     * levels of the same answer, not a fallback chain into each other.
     */
    static final class Place {
        String neighborhood = "";
        String city = "";
        String street = "";

        boolean isEmpty() {
            return neighborhood.isEmpty() && city.isEmpty() && street.isEmpty();
        }

        /** The name a driver would say: the neighborhood, else the city, else the street. */
        String label() {
            if (!neighborhood.isEmpty()) return neighborhood;
            if (!city.isEmpty()) return city;
            return street;
        }
    }

    private ReverseGeocoder() {}

    /** Blocks for the rate limit and the request. Returns null when offline or on any failure. */
    static Place lookup(double lat, double lon) {
        synchronized (RATE_LOCK) {
            long wait = lastRequestAt + MIN_GAP_MS - SystemClock.elapsedRealtime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            lastRequestAt = SystemClock.elapsedRealtime();
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2"
                            + "&lat=%.6f&lon=%.6f&zoom=17&addressdetails=1&accept-language=pt-BR",
                    lat, lon));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", HTTP_UA);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "reverse geocode HTTP " + code);
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return parse(body.toString());
        } catch (Exception e) {
            // Offline, or the car's link is down. Silent is the honest state.
            Log.w(TAG, "reverse geocode failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Nominatim's address object is not a fixed shape -- which field carries
     * "the place you are in" depends on how the area was mapped. Walk
     * suburb-level keys for the neighborhood and city-level keys for the city.
     */
    static Place parse(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject address = root.optJSONObject("address");
            if (address == null) return null;
            Place place = new Place();
            place.neighborhood = first(address, "suburb", "neighbourhood", "city_district");
            place.city = first(address, "city", "town", "village", "municipality", "county");
            place.street = first(address, "road", "pedestrian", "footway");
            return place;
        } catch (Exception e) {
            Log.w(TAG, "reverse geocode parse failed: " + e.getMessage());
            return null;
        }
    }

    private static String first(JSONObject address, String... keys) {
        for (String key : keys) {
            String value = address.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }
}
