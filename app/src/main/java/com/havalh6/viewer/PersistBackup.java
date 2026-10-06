package com.havalh6.viewer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A copy of the settings and trips.db on shared storage, so both survive an
 * uninstall / reinstall. targetSdk is 28, so WRITE_EXTERNAL_STORAGE is all it
 * takes; there is no scoped storage on this MMI.
 *
 * <p>The order that matters: on a fresh install the local data is EMPTY, and the
 * first thing that must happen is a restore, never a backup, or the empty state
 * overwrites the only copy. {@link #MARKER} in private files is that guard: it is
 * absent after an install or a data clear, and backups stay off until
 * {@link #restoreIfFresh} has run with the permission granted.
 *
 * <p>A file existing is not the same as it having the history. SQLite creates an
 * empty trips.db before storage permission is granted, and a later backup used
 * to copy that empty file over the shared one. Restore and backup now union-merge
 * by primary key, and a backup refuses to replace a shared file that has more
 * trips than the local database or that cannot be read. Day totals are rebuilt
 * from the merged trips, so one day's row cannot hide a richer copy.
 */
final class PersistBackup {
    private static final String TAG = "H6Persist";
    static final String DIR = "HavalH6Viewer";
    private static final String SETTINGS = "settings.json";
    private static final String TRIPS = "trips.db";
    private static final String MARKER = "persist_restored";

    private PersistBackup() {}

    static File dir() {
        return new File(Environment.getExternalStorageDirectory(), DIR);
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static File marker(Context c) {
        return new File(c.getFilesDir(), MARKER);
    }

    /** Backups are allowed only once this install has had its restore chance. */
    static boolean armed(Context c) {
        return marker(c).exists() && hasPermission(c);
    }

    /** True when a restart would restore something: fresh install, backup present. */
    static boolean restorePending(Context c) {
        if (marker(c).exists()) return false;
        return new File(dir(), TRIPS).isFile()
                || new File(dir(), TRIPS + ".bak").isFile()
                || new File(dir(), SETTINGS).isFile();
    }

    /**
     * Run before anything opens trips.db. Seeds the local file from whichever
     * shared copy has more trips when the local one is empty, then absorbs both
     * shared copies into it. The marker stays off until that absorb kept every
     * trip, so the next launch tries again instead of backing an empty file up.
     * Settings are restored by the page itself (it only takes them when its own
     * localStorage is empty), so this only has to leave them readable.
     */
    static synchronized void restoreIfFresh(Context c) {
        if (marker(c).exists() || !hasPermission(c)) return;
        File local = c.getDatabasePath(TRIPS);
        File shared = new File(dir(), TRIPS);
        File bak = new File(dir(), TRIPS + ".bak");
        try {
            int sharedN = tripCount(shared);
            int bakN = tripCount(bak);
            if (sharedN < 0 || bakN < 0) {
                Log.w(TAG, "trips restore unreadable; will retry");
                return;
            }
            if (tripCount(local) == 0) {
                File seed = bakN > sharedN ? bak : shared;
                if (tripCount(seed) > 0) {
                    replaceWith(local, seed);
                    Log.w(TAG, "trips.db seeded from " + seed.getName()
                            + " (" + local.length() + " bytes)");
                }
            }
            if (local.isFile() && tripCount(local) > 0) {
                SQLiteDatabase db = SQLiteDatabase.openDatabase(
                        local.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
                try {
                    absorb(db, shared);
                    absorb(db, bak);
                    checkpoint(db);
                } finally {
                    db.close();
                }
            }
            int best = Math.max(sharedN, bakN);
            int now = tripCount(local);
            if (best > now) {
                Log.w(TAG, "trips restore incomplete " + now + " < " + best + "; will retry");
                return;
            }
            Log.w(TAG, "trips.db restore kept " + now + " trips");
        } catch (RuntimeException | IOException e) {
            Log.w(TAG, "trips.db restore failed", e);
            return; // leave the marker off: try again next launch
        }
        try {
            if (!marker(c).createNewFile() && !marker(c).exists()) Log.w(TAG, "marker not written");
        } catch (IOException e) {
            Log.w(TAG, "marker not written", e);
        }
    }

    /** The saved localStorage snapshot, or "" when there is none. */
    static String readSettings(Context c) {
        if (!hasPermission(c)) return "";
        File f = new File(dir(), SETTINGS);
        if (!f.isFile()) return "";
        try {
            return new String(readAll(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "settings read failed", e);
            return "";
        }
    }

    static void writeSettings(Context c, String json) {
        if (!armed(c) || json == null || json.isEmpty()) return;
        try {
            writeAtomic(new File(dir(), SETTINGS), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "settings backup failed", e);
        }
    }

    /**
     * Copy trips.db out. Must run on the thread that owns every write to it
     * (the trip worker): the checkpoint folds the WAL into the main file, and
     * nothing can write in between it and the copy.
     *
     * <p>Shared trips.db and trips.db.bak are absorbed first. A backup that has
     * fewer trips than the file it would replace is skipped, and .bak is rotated
     * only from a file that is at least as full as the .bak already there.
     */
    static void backupTrips(Context c, SQLiteDatabase db) {
        if (!armed(c)) return;
        if (!checkpoint(db)) return;
        File shared = new File(dir(), TRIPS);
        File bak = new File(dir(), TRIPS + ".bak");
        try {
            absorb(db, shared);
            absorb(db, bak);
        } catch (RuntimeException e) {
            Log.w(TAG, "trips absorb failed; not overwriting backup", e);
            return;
        }
        if (!checkpoint(db)) return;
        File local = c.getDatabasePath(TRIPS);
        if (!local.isFile() || local.length() == 0) return;
        int localN = queryTrips(db);
        int sharedN = tripCount(shared);
        if (sharedN < 0) {
            Log.w(TAG, "refusing to replace unreadable trips backup");
            return;
        }
        if (sharedN > localN) {
            Log.w(TAG, "refusing to shrink trips backup " + sharedN + " -> " + localN);
            return;
        }
        try {
            // Rotate only from a file that is already as full as .bak. The copy
            // lands beside .bak and replaces it only after it is complete, so a
            // crash cannot leave .bak truncated. An unreadable .bak stays put.
            int bakN = tripCount(bak);
            if (bakN < 0) {
                Log.w(TAG, "leaving unreadable trips.db.bak in place");
            } else if (shared.isFile() && shared.length() > 0 && sharedN >= bakN && sharedN > 0) {
                File bakTmp = new File(bak.getPath() + ".tmp");
                copy(shared, bakTmp);
                if (bak.exists() && !bak.delete()) {
                    bakTmp.delete();
                    throw new IOException("could not rotate " + bak);
                }
                if (!bakTmp.renameTo(bak)) throw new IOException("bak rotate failed");
            }
            writeAtomic(shared, readAll(local));
        } catch (IOException e) {
            Log.w(TAG, "trips.db backup failed", e);
        }
    }

    /** Pull every trip from {@code other} into the open database. Missing or identical files are skipped. */
    private static void absorb(SQLiteDatabase db, File other) {
        if (other == null || !other.isFile() || other.length() == 0) return;
        if (sameFile(databasePath(db), other)) return;
        String path = other.getAbsolutePath().replace("'", "''");
        db.execSQL("ATTACH DATABASE '" + path + "' AS src");
        try {
            if (!hasTable(db, "src", "trips")) return;
            if (columns(db, "main", "trips") != columns(db, "src", "trips")) {
                Log.w(TAG, "skip absorb, trips schema differs: " + other);
                return;
            }
            db.beginTransaction();
            try {
                db.execSQL("INSERT OR IGNORE INTO trips SELECT * FROM src.trips");
                db.execSQL("INSERT INTO trip_points (start_ms, t, lat, lon, alt, kmh, kw, fuel_mode, fuel_rate, ice, km) "
                        + "SELECT start_ms, t, lat, lon, alt, kmh, kw, fuel_mode, fuel_rate, ice, km FROM src.trip_points p "
                        + "WHERE NOT EXISTS (SELECT 1 FROM trip_points d WHERE d.start_ms = p.start_ms AND d.t = p.t)");
                // days is a rollup of trips, keyed by day. INSERT OR IGNORE kept the
                // local day and dropped a richer shared total for that same day, then
                // the backup wrote the smaller rollup back. Rebuild from the merged
                // trips so a day that exists on both sides keeps every trip's totals.
                rebuildDays(db);
                if (hasTable(db, "src", "trip_stops")
                        && columns(db, "main", "trip_stops") == columns(db, "src", "trip_stops")) {
                    db.execSQL("INSERT INTO trip_stops (start_ms, t, kind, lat, lon, level_before, level_after, amount) "
                            + "SELECT start_ms, t, kind, lat, lon, level_before, level_after, amount FROM src.trip_stops s "
                            + "WHERE NOT EXISTS (SELECT 1 FROM trip_stops d "
                            + "WHERE d.start_ms = s.start_ms AND d.t = s.t AND d.kind = s.kind)");
                }
                if (hasTable(db, "src", "range_cycles")
                        && columns(db, "main", "range_cycles") == columns(db, "src", "range_cycles")) {
                    db.execSQL("INSERT OR IGNORE INTO range_cycles SELECT * FROM src.range_cycles");
                }
                if (hasTable(db, "src", "range_samples")
                        && columns(db, "main", "range_samples") == columns(db, "src", "range_samples")) {
                    db.execSQL("INSERT INTO range_samples (cycle_id, t, soc, ev_km, km, oem, hist) "
                            + "SELECT cycle_id, t, soc, ev_km, km, oem, hist FROM src.range_samples p "
                            + "WHERE NOT EXISTS (SELECT 1 FROM range_samples d "
                            + "WHERE d.cycle_id = p.cycle_id AND d.t = p.t)");
                }
                if (hasTable(db, "src", "open_trip")) {
                    db.execSQL("INSERT OR REPLACE INTO open_trip (id, state, updated_ms) "
                            + "SELECT id, state, updated_ms FROM src.open_trip "
                            + "WHERE updated_ms > IFNULL((SELECT updated_ms FROM main.open_trip WHERE id = 1), 0)");
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            try {
                db.execSQL("DETACH DATABASE src");
            } catch (RuntimeException ignored) {}
        }
    }

    private static String databasePath(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("PRAGMA database_list", null)) {
            while (c.moveToNext()) {
                if ("main".equals(c.getString(1))) return c.getString(2);
            }
        }
        return null;
    }

    private static boolean sameFile(String open, File other) {
        if (open == null || open.isEmpty()) return false;
        try {
            return new File(open).getCanonicalPath().equals(other.getCanonicalPath());
        } catch (IOException e) {
            return new File(open).getAbsolutePath().equals(other.getAbsolutePath());
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String schema, String table) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM " + schema + ".sqlite_master WHERE type='table' AND name=?",
                new String[]{table})) {
            return c.moveToFirst();
        }
    }

    private static int columns(SQLiteDatabase db, String schema, String table) {
        try (Cursor c = db.rawQuery("PRAGMA " + schema + ".table_info(" + table + ")", null)) {
            int n = 0;
            while (c.moveToNext()) n++;
            return n;
        }
    }

    private static int queryTrips(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT count(*) FROM trips", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /**
     * Trips in a closed file. Missing or empty files count as 0.
     *
     * @return -1 when the file exists but cannot be read. Callers must not treat
     *         that as an empty database: a failed open used to pass the shrink
     *         check and replace the only copy.
     */
    private static int tripCount(File f) {
        if (f == null || !f.isFile() || f.length() == 0) return 0;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            return queryTrips(db);
        } catch (RuntimeException e) {
            Log.w(TAG, "trip count failed for " + f, e);
            return -1;
        } finally {
            if (db != null) db.close();
        }
    }

    /** Replace {@code days} with a rollup of the merged {@code trips} table. */
    private static void rebuildDays(SQLiteDatabase db) {
        if (!hasTable(db, "main", "days") || columns(db, "main", "days") != 8) {
            Log.w(TAG, "skip day rebuild, days schema differs");
            return;
        }
        db.execSQL("DELETE FROM days");
        db.execSQL("INSERT INTO days (day, trips, km, drive_ms, fuel_l, kwh_out, kwh_in, ev_km) "
                + "SELECT day, COUNT(*), "
                + "COALESCE(SUM(km), 0), COALESCE(SUM(drive_ms), 0), COALESCE(SUM(fuel_l), 0), "
                + "COALESCE(SUM(kwh_out), 0), COALESCE(SUM(kwh_in), 0), COALESCE(SUM(ev_km), 0) "
                + "FROM trips WHERE day IS NOT NULL AND day != '' GROUP BY day");
    }

    /** @return false when the WAL could not be folded into the main file. */
    private static boolean checkpoint(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            if (!c.moveToNext()) return true;
            if (c.getInt(0) != 0) {
                Log.w(TAG, "wal checkpoint busy");
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void replaceWith(File local, File seed) throws IOException {
        File parent = local.getParentFile();
        if (parent != null) parent.mkdirs();
        new File(local.getPath() + "-wal").delete();
        new File(local.getPath() + "-shm").delete();
        File tmp = new File(local.getPath() + ".restore");
        copy(seed, tmp);
        if (local.exists() && !local.delete()) {
            throw new IOException("could not replace " + local);
        }
        if (!tmp.renameTo(local)) throw new IOException("rename failed");
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) throw new IOException("rename to " + target + " failed");
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int at = 0;
            while (at < buf.length) {
                int n = in.read(buf, at, buf.length - at);
                if (n < 0) break;
                at += n;
            }
        }
        return buf;
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            ((FileOutputStream) out).getFD().sync();
        }
    }
}
