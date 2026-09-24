package com.havalh6.viewer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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
        return new File(dir(), TRIPS).isFile() || new File(dir(), SETTINGS).isFile();
    }

    /**
     * Run before anything opens trips.db. Copies the backup in only when there
     * is no local DB, so an upgrade of a running install never loses data.
     * Settings are restored by the page itself (it only takes them when its own
     * localStorage is empty), so this only has to leave them readable.
     */
    static synchronized void restoreIfFresh(Context c) {
        if (marker(c).exists() || !hasPermission(c)) return;
        File local = c.getDatabasePath(TRIPS);
        File backup = new File(dir(), TRIPS);
        if (!local.exists() && backup.isFile()) {
            try {
                File parent = local.getParentFile();
                if (parent != null) parent.mkdirs();
                File tmp = new File(local.getPath() + ".restore");
                copy(backup, tmp);
                if (!tmp.renameTo(local)) throw new IOException("rename failed");
                Log.w(TAG, "trips.db restored from " + backup + " (" + local.length() + " bytes)");
            } catch (IOException e) {
                Log.w(TAG, "trips.db restore failed", e);
                return; // leave the marker off: try again next launch
            }
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
     */
    static void backupTrips(Context c, SQLiteDatabase db) {
        if (!armed(c)) return;
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close();
        } catch (RuntimeException e) {
            // Not in WAL mode: the main file is already complete.
        }
        File local = c.getDatabasePath(TRIPS);
        if (!local.isFile()) return;
        try {
            writeAtomic(new File(dir(), TRIPS), readAll(local));
        } catch (IOException e) {
            Log.w(TAG, "trips.db backup failed", e);
        }
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
