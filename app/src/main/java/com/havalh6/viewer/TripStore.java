package com.havalh6.viewer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trip persistence.
 *
 * <p>Retention (docs/energy-workspace-plan.md, decision 2): per-sample detail
 * ({@code trip_points}) is kept for the last {@link #DETAIL_TRIPS} trips only;
 * trip summaries and daily totals are kept forever. Monthly totals are a GROUP
 * BY over {@code days}, not a table of their own.
 */
final class TripStore extends SQLiteOpenHelper {
    static final int DETAIL_TRIPS = 30;
    private static final String DB_NAME = "trips.db";
    static final int ROUTE_POINTS = 300;
    private static final int DB_VERSION = 4;

    TripStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips ("
                + "start_ms INTEGER PRIMARY KEY, end_ms INTEGER NOT NULL, day TEXT NOT NULL,"
                + "km REAL, km_integrated REAL, odo_start REAL, odo_end REAL,"
                + "drive_ms INTEGER, idle_ms INTEGER, ev_km REAL, ev_ms INTEGER,"
                + "kwh_out REAL, kwh_in REAL, fuel_l REAL, max_kmh REAL,"
                + "stops INTEGER, harsh_accel INTEGER, harsh_brake INTEGER, climb_m REAL,"
                + "start_lat REAL, start_lon REAL, end_lat REAL, end_lon REAL,"
                + "fuel_pct_start REAL, fuel_pct_end REAL,"
                + "start_place TEXT, end_place TEXT, snapshot_path TEXT,"
                + "raw_json TEXT, detail_kept INTEGER NOT NULL DEFAULT 1,"
                + "map_attempts INTEGER NOT NULL DEFAULT 0,"
                + "guided INTEGER NOT NULL DEFAULT 0, arrived INTEGER NOT NULL DEFAULT 0,"
                + "planned_km REAL, planned_s INTEGER, guided_km REAL, guided_s INTEGER,"
                + "route TEXT, snapshot_view TEXT, soc_start REAL, soc_end REAL)");
        db.execSQL("CREATE INDEX trips_day ON trips(day)");
        db.execSQL("CREATE TABLE trip_points ("
                + "start_ms INTEGER NOT NULL, t INTEGER NOT NULL,"
                + "lat REAL, lon REAL, alt REAL, kmh REAL, kw REAL,"
                + "fuel_mode INTEGER, fuel_rate REAL, ice INTEGER, km REAL)");
        db.execSQL("CREATE INDEX trip_points_trip ON trip_points(start_ms, t)");
        db.execSQL("CREATE TABLE days ("
                + "day TEXT PRIMARY KEY, trips INTEGER NOT NULL DEFAULT 0,"
                + "km REAL NOT NULL DEFAULT 0, drive_ms INTEGER NOT NULL DEFAULT 0,"
                + "fuel_l REAL NOT NULL DEFAULT 0, kwh_out REAL NOT NULL DEFAULT 0,"
                + "kwh_in REAL NOT NULL DEFAULT 0, ev_km REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE open_trip (id INTEGER PRIMARY KEY CHECK (id = 1),"
                + "state TEXT NOT NULL, updated_ms INTEGER NOT NULL)");
        createStops(db);
    }

    /** Refuels and charges, kept forever (a few rows a month). */
    private static void createStops(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trip_stops (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "start_ms INTEGER NOT NULL, t INTEGER NOT NULL, kind TEXT NOT NULL,"
                + "lat REAL, lon REAL, level_before REAL, level_after REAL, amount REAL)");
        db.execSQL("CREATE INDEX trip_stops_trip ON trip_stops(start_ms)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migrate, never drop: summaries and daily totals are kept forever.
        if (oldVersion < 2) {
            // Phase 4: map snapshots and place names are filled in by TripMapWorker.
            db.execSQL("ALTER TABLE trips ADD COLUMN map_attempts INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            // Android Auto guidance per trip.
            db.execSQL("ALTER TABLE trips ADD COLUMN guided INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE trips ADD COLUMN arrived INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE trips ADD COLUMN planned_km REAL");
            db.execSQL("ALTER TABLE trips ADD COLUMN planned_s INTEGER");
            db.execSQL("ALTER TABLE trips ADD COLUMN guided_km REAL");
            db.execSQL("ALTER TABLE trips ADD COLUMN guided_s INTEGER");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE trips ADD COLUMN route TEXT");
            db.execSQL("ALTER TABLE trips ADD COLUMN snapshot_view TEXT");
            db.execSQL("ALTER TABLE trips ADD COLUMN soc_start REAL");
            db.execSQL("ALTER TABLE trips ADD COLUMN soc_end REAL");
            createStops(db);
            // Snapshots used to have the route baked in; the page now draws it, coloured,
            // on top of a tiles-only image. Redraw them.
            db.execSQL("UPDATE trips SET snapshot_path = NULL, map_attempts = 0"
                    + " WHERE snapshot_path IS NOT NULL AND snapshot_path != ''");
        }
    }

    // ── Checkpoint ─────────────────────────────────────────────────────────

    void saveCheckpoint(String state, long now) {
        ContentValues v = new ContentValues();
        v.put("id", 1);
        v.put("state", state);
        v.put("updated_ms", now);
        getWritableDatabase().insertWithOnConflict("open_trip", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    String loadCheckpoint() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT state FROM open_trip WHERE id = 1", null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    void clearCheckpoint() {
        getWritableDatabase().delete("open_trip", null, null);
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    void appendPoints(long startMs, List<TripPoint> points) {
        if (points.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            for (TripPoint p : points) {
                v.clear();
                v.put("start_ms", startMs);
                v.put("t", p.t);
                putReal(v, "lat", p.lat);
                putReal(v, "lon", p.lon);
                putReal(v, "alt", p.alt);
                putReal(v, "kmh", p.kmh);
                putReal(v, "kw", p.kw);
                v.put("fuel_mode", p.fuelMode);
                putReal(v, "fuel_rate", p.fuelRate);
                v.put("ice", p.ice);
                putReal(v, "km", p.km);
                db.insert("trip_points", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void deletePoints(long startMs) {
        getWritableDatabase().delete("trip_points", "start_ms = ?", new String[]{String.valueOf(startMs)});
    }

    /** Stores a closed trip, adds it to its day, and applies the detail retention. */
    void insertTrip(TripSummary s) {
        String day = dayKey(s.startMs);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("start_ms", s.startMs);
            v.put("end_ms", s.endMs);
            v.put("day", day);
            putReal(v, "km", s.km);
            putReal(v, "km_integrated", s.kmIntegrated);
            putReal(v, "odo_start", s.odoStart);
            putReal(v, "odo_end", s.odoEnd);
            v.put("drive_ms", s.driveMs);
            v.put("idle_ms", s.idleMs);
            putReal(v, "ev_km", s.evKm);
            v.put("ev_ms", s.evMs);
            putReal(v, "kwh_out", s.kwhOut);
            putReal(v, "kwh_in", s.kwhIn);
            putReal(v, "fuel_l", s.fuelL);
            putReal(v, "max_kmh", s.maxKmh);
            v.put("stops", s.stops);
            v.put("harsh_accel", s.harshAccel);
            v.put("harsh_brake", s.harshBrake);
            putReal(v, "climb_m", s.climbM);
            putReal(v, "start_lat", s.startLat);
            putReal(v, "start_lon", s.startLon);
            putReal(v, "end_lat", s.endLat);
            putReal(v, "end_lon", s.endLon);
            putReal(v, "fuel_pct_start", s.fuelPctStart);
            putReal(v, "fuel_pct_end", s.fuelPctEnd);
            v.put("raw_json", rawJson(s));
            putReal(v, "soc_start", s.socStart);
            putReal(v, "soc_end", s.socEnd);
            v.put("guided", s.guided ? 1 : 0);
            v.put("arrived", s.arrived ? 1 : 0);
            if (s.guided) {
                putReal(v, "planned_km", s.plannedKm);
                v.put("planned_s", s.plannedS);
                putReal(v, "guided_km", s.guidedKm);
                v.put("guided_s", s.guidedS);
            }
            db.insertWithOnConflict("trips", null, v, SQLiteDatabase.CONFLICT_REPLACE);

            db.execSQL("INSERT OR IGNORE INTO days(day) VALUES (?)", new Object[]{day});
            db.execSQL("UPDATE days SET trips = trips + 1, km = km + ?, drive_ms = drive_ms + ?,"
                            + " fuel_l = fuel_l + ?, kwh_out = kwh_out + ?, kwh_in = kwh_in + ?,"
                            + " ev_km = ev_km + ? WHERE day = ?",
                    new Object[]{zero(s.km), s.driveMs, zero(s.fuelL), zero(s.kwhOut),
                            zero(s.kwhIn), zero(s.evKm), day});

            String keep = "SELECT start_ms FROM trips ORDER BY start_ms DESC LIMIT " + DETAIL_TRIPS;
            db.execSQL("DELETE FROM trip_points WHERE start_ms IN (SELECT start_ms FROM trips)"
                    + " AND start_ms NOT IN (" + keep + ")");
            db.execSQL("UPDATE trips SET detail_kept = 0 WHERE detail_kept = 1"
                    + " AND start_ms NOT IN (" + keep + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Drops samples that belong to no stored trip and are not the open one --
     * left behind by a trip that was discarded while its points were buffered.
     */
    void deleteOrphanPoints(long openStartMs) {
        getWritableDatabase().execSQL("DELETE FROM trip_points WHERE start_ms NOT IN"
                + " (SELECT start_ms FROM trips) AND start_ms != ?", new Object[]{openStartMs});
    }

    // ── Map snapshot and place names (TripMapWorker) ─────────────────────────

    /** A closed trip still missing its map snapshot (NULL path) or its place names (NULL start_place). */
    static final class PendingMap {
        final long startMs;
        final boolean needMap;
        final boolean needPlaces;
        final double startLat;
        final double startLon;
        final double endLat;
        final double endLon;

        PendingMap(long startMs, boolean needMap, boolean needPlaces,
                   double startLat, double startLon, double endLat, double endLon) {
            this.startMs = startMs;
            this.needMap = needMap;
            this.needPlaces = needPlaces;
            this.startLat = startLat;
            this.startLon = startLon;
            this.endLat = endLat;
            this.endLon = endLon;
        }
    }

    List<PendingMap> pendingMaps(int maxAttempts, int limit) {
        java.util.ArrayList<PendingMap> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms, snapshot_path IS NULL, start_place IS NULL, start_lat, start_lon, end_lat, end_lon"
                        + " FROM trips WHERE (snapshot_path IS NULL OR start_place IS NULL) AND map_attempts < ?"
                        + " ORDER BY start_ms DESC LIMIT ?",
                new String[]{String.valueOf(maxAttempts), String.valueOf(limit)})) {
            while (c.moveToNext()) {
                out.add(new PendingMap(c.getLong(0), c.getInt(1) == 1, c.getInt(2) == 1,
                        c.isNull(3) ? Double.NaN : c.getDouble(3), c.isNull(4) ? Double.NaN : c.getDouble(4),
                        c.isNull(5) ? Double.NaN : c.getDouble(5), c.isNull(6) ? Double.NaN : c.getDouble(6)));
            }
        }
        return out;
    }

    /** {lats, lons} of the trip's GPS samples in time order, strided down to at most {@code max}. */
    double[][] fixes(long startMs, int max) {
        java.util.ArrayList<double[]> pts = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT lat, lon FROM trip_points WHERE start_ms = ? AND lat IS NOT NULL AND lon IS NOT NULL ORDER BY t",
                new String[]{String.valueOf(startMs)})) {
            int stride = Math.max(1, (int) Math.ceil(c.getCount() / (double) Math.max(1, max)));
            int i = 0;
            while (c.moveToNext()) {
                if (i++ % stride == 0 || c.isLast()) pts.add(new double[]{c.getDouble(0), c.getDouble(1)});
            }
        }
        double[][] out = new double[2][pts.size()];
        for (int k = 0; k < pts.size(); k++) {
            out[0][k] = pts.get(k)[0];
            out[1][k] = pts.get(k)[1];
        }
        return out;
    }

    /** File name inside trip-maps/, or "" when the trip has no map to draw. */
    void setTripMap(long startMs, String fileName, String view) {
        ContentValues v = new ContentValues();
        v.put("snapshot_path", fileName);
        if (view == null) v.putNull("snapshot_view"); else v.put("snapshot_view", view);
        getWritableDatabase().update("trips", v, "start_ms = ?", new String[]{String.valueOf(startMs)});
    }

    void setTripPlaces(long startMs, String start, String end) {
        ContentValues v = new ContentValues();
        v.put("start_place", start);
        v.put("end_place", end);
        getWritableDatabase().update("trips", v, "start_ms = ?", new String[]{String.valueOf(startMs)});
    }

    void bumpMapAttempts(long startMs) {
        getWritableDatabase().execSQL("UPDATE trips SET map_attempts = map_attempts + 1 WHERE start_ms = ?",
                new Object[]{startMs});
    }

    // ── Route, stops and totals ──────────────────────────────────────────────

    /** Where and with what levels the car was last parked: the end of the newest trip. */
    static final class Parked {
        final double fuelPct;
        final double soc;
        final double lat;
        final double lon;
        final long endMs;

        Parked(double fuelPct, double soc, double lat, double lon, long endMs) {
            this.fuelPct = fuelPct;
            this.soc = soc;
            this.lat = lat;
            this.lon = lon;
            this.endMs = endMs;
        }
    }

    Parked lastParked() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT fuel_pct_end, soc_end, end_lat, end_lon, end_ms FROM trips ORDER BY start_ms DESC LIMIT 1", null)) {
            if (!c.moveToFirst()) return null;
            return new Parked(c.isNull(0) ? Double.NaN : c.getDouble(0), c.isNull(1) ? Double.NaN : c.getDouble(1),
                    c.isNull(2) ? Double.NaN : c.getDouble(2), c.isNull(3) ? Double.NaN : c.getDouble(3), c.getLong(4));
        }
    }

    void insertStop(long startMs, TripStop stop) {
        ContentValues v = new ContentValues();
        v.put("start_ms", startMs);
        v.put("t", stop.t);
        v.put("kind", stop.kind);
        putReal(v, "lat", stop.lat);
        putReal(v, "lon", stop.lon);
        putReal(v, "level_before", stop.before);
        putReal(v, "level_after", stop.after);
        putReal(v, "amount", stop.amount);
        getWritableDatabase().insert("trip_stops", null, v);
    }

    /**
     * The route kept FOREVER, unlike the per-second samples (last
     * {@link #DETAIL_TRIPS} trips): up to {@code max} GPS points, each
     * [lat, lon, kmh, kw, fuelMode, fuelRate], enough to draw and colour the
     * route by speed, electric or fuel. "[]" for a trip with no fix, so the
     * sweep does not retry it.
     */
    void saveRoute(long startMs, int max) {
        JSONArray route = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT lat, lon, kmh, kw, fuel_mode, fuel_rate FROM trip_points"
                        + " WHERE start_ms = ? AND lat IS NOT NULL AND lon IS NOT NULL ORDER BY t",
                new String[]{String.valueOf(startMs)})) {
            int stride = Math.max(1, (int) Math.ceil(c.getCount() / (double) Math.max(1, max)));
            int i = 0;
            while (c.moveToNext()) {
                if (i++ % stride != 0 && !c.isLast()) continue;
                JSONArray p = new JSONArray();
                p.put(round(c.getDouble(0), 5));
                p.put(round(c.getDouble(1), 5));
                p.put(c.isNull(2) ? JSONObject.NULL : (Object) Math.round(c.getDouble(2)));
                p.put(c.isNull(3) ? JSONObject.NULL : (Object) round(c.getDouble(3), 1));
                p.put(c.getInt(4));
                p.put(c.isNull(5) ? JSONObject.NULL : (Object) round(c.getDouble(5), 1));
                route.put(p);
            }
        } catch (JSONException e) {
            return;
        }
        ContentValues v = new ContentValues();
        v.put("route", route.toString());
        getWritableDatabase().update("trips", v, "start_ms = ?", new String[]{String.valueOf(startMs)});
    }

    /** Trips still holding their samples but no compact route (recorded before routes were kept). */
    List<Long> pendingRoutes(int limit) {
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ms FROM trips WHERE route IS NULL AND detail_kept = 1 ORDER BY start_ms DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(c.getLong(0));
        }
        return out;
    }

    /**
     * Everything recorded: distance (and how much of it with the engine off),
     * fuel, pack energy, stops, and the usable battery capacity MEASURED from
     * trips where the SOC moved at least 10 points: net pack energy divided by
     * the SOC it cost. Absent until three such trips agree in sign.
     */
    String totalsJson() {
        try {
            JSONObject o = new JSONObject();
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT SUM(trips), SUM(km), SUM(drive_ms), SUM(fuel_l), SUM(kwh_out), SUM(kwh_in), SUM(ev_km), MIN(day)"
                            + " FROM days", null)) {
                if (c.moveToFirst()) {
                    o.put("trips", c.getLong(0));
                    o.put("km", c.getDouble(1));
                    o.put("driveMs", c.getLong(2));
                    o.put("fuelL", c.getDouble(3));
                    o.put("kwhOut", c.getDouble(4));
                    o.put("kwhIn", c.getDouble(5));
                    o.put("evKm", c.getDouble(6));
                    o.put("firstDay", c.isNull(7) ? JSONObject.NULL : c.getString(7));
                }
            }
            o.put("refuels", 0);
            o.put("refuelL", 0);
            o.put("charges", 0);
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT kind, COUNT(*), SUM(amount) FROM trip_stops GROUP BY kind", null)) {
                while (c.moveToNext()) {
                    if (TripStop.REFUEL.equals(c.getString(0))) {
                        o.put("refuels", c.getLong(1));
                        o.put("refuelL", c.getDouble(2));
                    } else if (TripStop.CHARGE.equals(c.getString(0))) {
                        o.put("charges", c.getLong(1));
                    }
                }
            }
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*), SUM(kwh_out - kwh_in), SUM(soc_start - soc_end) FROM trips"
                            + " WHERE soc_start IS NOT NULL AND soc_end IS NOT NULL AND ABS(soc_start - soc_end) >= 10"
                            + " AND (soc_start - soc_end) * (kwh_out - kwh_in) > 0", null)) {
                if (c.moveToFirst() && c.getLong(0) >= 3 && c.getDouble(2) != 0) {
                    o.put("capacityKwh", c.getDouble(1) / c.getDouble(2) * 100.0);
                    o.put("capacityTrips", c.getLong(0));
                }
            }
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /**
     * Refuels and charges since {@code fromMs}: how many, and the level points they
     * added (fuel %, SOC %). The page turns points into litres or kWh with the
     * version's tank or battery, so a stored figure never carries a guessed size.
     */
    String stopTotalsJson(long fromMs) {
        try {
            JSONObject o = new JSONObject();
            o.put("refuels", 0);
            o.put("refuelPct", 0);
            o.put("charges", 0);
            o.put("chargePct", 0);
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT kind, COUNT(*), SUM(level_after - level_before) FROM trip_stops WHERE t >= ? GROUP BY kind",
                    new String[]{String.valueOf(fromMs)})) {
                while (c.moveToNext()) {
                    if (TripStop.REFUEL.equals(c.getString(0))) {
                        o.put("refuels", c.getLong(1));
                        o.put("refuelPct", c.getDouble(2));
                    } else if (TripStop.CHARGE.equals(c.getString(0))) {
                        o.put("charges", c.getLong(1));
                        o.put("chargePct", c.getDouble(2));
                    }
                }
            }
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /**
     * Every refuel and charge since {@code fromMs}, oldest first, with the levels
     * either side: the page prices them (asking the driver) and blends each into
     * what was left in the tank or pack.
     */
    String stopsJson(long fromMs) {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT kind, t, start_ms, level_before, level_after, amount FROM trip_stops WHERE t >= ? ORDER BY t",
                new String[]{String.valueOf(fromMs)})) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("kind", c.getString(0));
                o.put("t", c.getLong(1));
                o.put("startMs", c.getLong(2));
                o.put("before", real(c, 3));
                o.put("after", real(c, 4));
                o.put("amount", real(c, 5));
                out.put(o);
            }
        } catch (JSONException e) {
            return "[]";
        }
        return out.toString();
    }

    private static double round(double v, int decimals) {
        double k = Math.pow(10, decimals);
        return Math.round(v * k) / k;
    }

    // ── Reads (JSON for the page) ──────────────────────────────────────────

    String tripsJson(int offset, int limit) {
        JSONArray out = new JSONArray();
        String sql = "SELECT * FROM trips ORDER BY start_ms DESC LIMIT ? OFFSET ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{
                String.valueOf(Math.max(1, Math.min(200, limit))), String.valueOf(Math.max(0, offset))})) {
            while (c.moveToNext()) out.put(tripRow(c));
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    /** One trip with its samples, strided down to at most {@code maxPoints}. */
    String tripJson(long startMs, int maxPoints) {
        try {
            JSONObject trip = null;
            try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM trips WHERE start_ms = ?",
                    new String[]{String.valueOf(startMs)})) {
                if (c.moveToFirst()) {
                    trip = tripRow(c);
                    int ri = c.getColumnIndexOrThrow("route");
                    if (!c.isNull(ri)) trip.put("route", new JSONArray(c.getString(ri)));
                }
            }
            if (trip == null) return "null";
            JSONArray pts = new JSONArray();
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT t, lat, lon, alt, kmh, kw, fuel_mode, fuel_rate, ice, km FROM trip_points"
                            + " WHERE start_ms = ? ORDER BY t", new String[]{String.valueOf(startMs)})) {
                int n = c.getCount();
                int stride = Math.max(1, (int) Math.ceil(n / (double) Math.max(1, maxPoints)));
                int i = 0;
                while (c.moveToNext()) {
                    if (i++ % stride != 0 && !c.isLast()) continue;
                    pts.put(pointRow(c));
                }
            }
            trip.put("pointFields", new JSONArray(
                    "[\"t\",\"lat\",\"lon\",\"alt\",\"kmh\",\"kw\",\"fuelMode\",\"fuelRate\",\"ice\",\"km\"]"));
            trip.put("points", pts);
            trip.put("stops", stopsArray(startMs));
            return trip.toString();
        } catch (JSONException e) {
            return "null";
        }
    }

    /**
     * The open trip's points after {@code afterT}, for the live map: what is saved
     * (thinned by stride to {@code maxPoints}) followed by what the recorder still
     * holds, so the route is a second behind rather than a flush (30 points) behind.
     * {@code held} was copied BEFORE this query: a flush in between shows the same
     * points in both, and they are dropped by time here instead of being lost.
     */
    String livePointsJson(long startMs, long afterT, int maxPoints, List<TripPoint> held) {
        try {
            JSONArray pts = new JSONArray();
            long lastT = afterT;
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT t, lat, lon, alt, kmh, kw, fuel_mode, fuel_rate, ice, km FROM trip_points"
                            + " WHERE start_ms = ? AND t > ? ORDER BY t",
                    new String[]{String.valueOf(startMs), String.valueOf(afterT)})) {
                int n = c.getCount();
                int stride = Math.max(1, (int) Math.ceil(n / (double) Math.max(1, maxPoints)));
                int i = 0;
                while (c.moveToNext()) {
                    if (i++ % stride != 0 && !c.isLast()) continue;
                    pts.put(pointRow(c));
                    lastT = c.getLong(0);
                }
            }
            for (TripPoint p : held) {
                if (p.t <= lastT) continue;
                JSONArray row = new JSONArray();
                row.put(p.t);
                row.put(num(p.lat));
                row.put(num(p.lon));
                row.put(num(p.alt));
                row.put(num(p.kmh));
                row.put(num(p.kw));
                row.put(p.fuelMode);
                row.put(num(p.fuelRate));
                row.put(p.ice);
                row.put(num(p.km));
                pts.put(row);
                lastT = p.t;
            }
            JSONObject out = new JSONObject();
            out.put("points", pts);
            out.put("stops", stopsArray(startMs));
            return out.toString();
        } catch (JSONException e) {
            return "null";
        }
    }

    /** [t, lat, lon, alt, kmh, kw, fuelMode, fuelRate, ice, km], as the page reads a point. */
    private static JSONArray pointRow(Cursor c) {
        JSONArray p = new JSONArray();
        p.put(c.getLong(0));
        for (int col = 1; col <= 5; col++) p.put(real(c, col));
        p.put(c.getInt(6));
        p.put(real(c, 7));
        p.put(c.getInt(8));
        p.put(real(c, 9));
        return p;
    }

    private JSONArray stopsArray(long startMs) throws JSONException {
        JSONArray stops = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT kind, t, lat, lon, level_before, level_after, amount FROM trip_stops WHERE start_ms = ? ORDER BY t",
                new String[]{String.valueOf(startMs)})) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("kind", c.getString(0));
                o.put("t", c.getLong(1));
                o.put("lat", real(c, 2));
                o.put("lon", real(c, 3));
                o.put("before", real(c, 4));
                o.put("after", real(c, 5));
                o.put("amount", real(c, 6));
                stops.put(o);
            }
        }
        return stops;
    }

    String daysJson(String fromDay, String toDay) {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT day, trips, km, drive_ms, fuel_l, kwh_out, kwh_in, ev_km FROM days"
                        + " WHERE day >= ? AND day <= ? ORDER BY day",
                new String[]{fromDay == null ? "" : fromDay, toDay == null ? "9999" : toDay})) {
            while (c.moveToNext()) out.put(periodRow(c));
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    String monthsJson() {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT substr(day, 1, 7) AS month, SUM(trips), SUM(km), SUM(drive_ms), SUM(fuel_l),"
                        + " SUM(kwh_out), SUM(kwh_in), SUM(ev_km) FROM days GROUP BY month ORDER BY month",
                null)) {
            while (c.moveToNext()) out.put(periodRow(c));
        } catch (JSONException ignored) {
        }
        return out.toString();
    }

    static String summaryJson(TripSummary s) {
        try {
            JSONObject o = new JSONObject();
            o.put("open", s.open);
            o.put("startMs", s.startMs);
            o.put("endMs", s.endMs);
            o.put("km", num(s.km));
            o.put("kmIntegrated", num(s.kmIntegrated));
            o.put("driveMs", s.driveMs);
            o.put("idleMs", s.idleMs);
            o.put("evKm", num(s.evKm));
            o.put("evMs", s.evMs);
            o.put("kwhOut", num(s.kwhOut));
            o.put("kwhIn", num(s.kwhIn));
            o.put("fuelL", num(s.fuelL));
            o.put("maxKmh", num(s.maxKmh));
            o.put("stops", s.stops);
            o.put("harshAccel", s.harshAccel);
            o.put("harshBrake", s.harshBrake);
            o.put("climbM", num(s.climbM));
            o.put("guided", s.guided ? 1 : 0);
            o.put("arrived", s.arrived ? 1 : 0);
            o.put("plannedKm", num(s.plannedKm));
            o.put("plannedS", s.plannedS);
            o.put("guidedKm", num(s.guidedKm));
            o.put("guidedS", s.guidedS);
            o.put("kmPerL", num(s.kmPerLitre()));
            o.put("kwhPer100Km", num(s.kwhPer100Km()));
            o.put("evShare", num(s.evShare()));
            o.put("avgKmh", num(s.avgMovingKmh()));
            o.put("fuelPctStart", num(s.fuelPctStart));
            o.put("fuelPctEnd", num(s.fuelPctEnd));
            o.put("socStart", num(s.socStart));
            o.put("socEnd", num(s.socEnd));
            return o.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static String dayKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    private static JSONObject tripRow(Cursor c) throws JSONException {
        JSONObject o = new JSONObject();
        long startMs = c.getLong(c.getColumnIndexOrThrow("start_ms"));
        o.put("startMs", startMs);
        o.put("endMs", c.getLong(c.getColumnIndexOrThrow("end_ms")));
        o.put("day", c.getString(c.getColumnIndexOrThrow("day")));
        for (String col : new String[]{"km", "km_integrated", "odo_start", "odo_end", "ev_km",
                "kwh_out", "kwh_in", "fuel_l", "max_kmh", "climb_m", "start_lat", "start_lon",
                "end_lat", "end_lon", "fuel_pct_start", "fuel_pct_end", "planned_km", "guided_km", "soc_start", "soc_end"}) {
            o.put(camel(col), real(c, c.getColumnIndexOrThrow(col)));
        }
        for (String col : new String[]{"drive_ms", "idle_ms", "ev_ms", "stops", "harsh_accel",
                "harsh_brake", "detail_kept", "guided", "arrived", "planned_s", "guided_s"}) {
            o.put(camel(col), c.getLong(c.getColumnIndexOrThrow(col)));
        }
        for (String col : new String[]{"start_place", "end_place", "snapshot_path", "snapshot_view"}) {
            String v = c.getString(c.getColumnIndexOrThrow(col));
            o.put(camel(col), v == null ? JSONObject.NULL : v);
        }
        double km = c.getDouble(c.getColumnIndexOrThrow("km"));
        double fuel = c.getDouble(c.getColumnIndexOrThrow("fuel_l"));
        double net = c.getDouble(c.getColumnIndexOrThrow("kwh_out")) - c.getDouble(c.getColumnIndexOrThrow("kwh_in"));
        o.put("kmPerL", fuel >= TripSummary.MIN_FUEL_FOR_RATIO_L ? km / fuel : JSONObject.NULL);
        o.put("kwhPer100Km", km >= 0.1 ? net * 100.0 / km : JSONObject.NULL);
        o.put("evShare", km >= 0.1 ? Math.min(1.0, c.getDouble(c.getColumnIndexOrThrow("ev_km")) / km) : JSONObject.NULL);
        return o;
    }

    private static JSONObject periodRow(Cursor c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("period", c.getString(0));
        o.put("trips", c.getLong(1));
        double km = c.getDouble(2);
        double fuel = c.getDouble(4);
        double net = c.getDouble(5) - c.getDouble(6);
        o.put("km", km);
        o.put("driveMs", c.getLong(3));
        o.put("fuelL", fuel);
        o.put("kwhOut", c.getDouble(5));
        o.put("kwhIn", c.getDouble(6));
        o.put("evKm", c.getDouble(7));
        o.put("kmPerL", fuel >= TripSummary.MIN_FUEL_FOR_RATIO_L ? km / fuel : JSONObject.NULL);
        o.put("kwhPer100Km", km >= 0.1 ? net * 100.0 / km : JSONObject.NULL);
        return o;
    }

    private static String rawJson(TripSummary s) {
        try {
            JSONObject o = new JSONObject();
            JSONObject first = new JSONObject();
            for (Map.Entry<String, String> e : s.rawFirst.entrySet()) first.put(e.getKey(), e.getValue());
            JSONObject last = new JSONObject();
            for (Map.Entry<String, String> e : s.rawLast.entrySet()) last.put(e.getKey(), e.getValue());
            o.put("first", first);
            o.put("last", last);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** org.json throws on NaN, and SQLite has no NaN: both become null. */
    private static Object num(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? JSONObject.NULL : v;
    }

    private static Object real(Cursor c, int col) {
        return c.isNull(col) ? JSONObject.NULL : c.getDouble(col);
    }

    private static void putReal(ContentValues v, String key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) v.putNull(key); else v.put(key, value);
    }

    private static double zero(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : v;
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return b.toString();
    }
}
