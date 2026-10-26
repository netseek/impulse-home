package com.havalh6.viewer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Locale;

/**
 * ENVIAR POR BLUETOOTH: hands an export's zip to the system's Bluetooth file
 * transfer (OPP). targetSdk 28 forbids a file:// URI in an intent, and the app
 * has no androidx, so this is the smallest provider that does the job:
 * read-only, not exported, serving only export zips by name, to whoever the
 * intent granted it to.
 *
 * <p>The Haval MMI ships that activity unexported (measured on the car,
 * 2026-10-25: "not exported from uid 1002"), so an app cannot start it. There
 * the share goes through {@link #shizuku}: the app's Shizuku user service
 * (GwmRebootService.shareFile) starts it with a file:// stream, as Shizuku's
 * user.
 */
public final class TripExportProvider extends ContentProvider {
    private static final String TAG = "H6Trip";
    static final String MIME = "application/zip";

    /** Starts the share as Shizuku's user; set by MainActivity while it lives. */
    interface ShizukuShare {
        /** Shizuku is running (its grant is asked for on first use). */
        boolean available();

        void share(String component, String path);
    }

    static volatile ShizukuShare shizuku;

    static String authority(Context c) {
        return c.getPackageName() + ".tripexport";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Only a plain export name: never a path, so nothing outside exports/ can be reached. */
    private static File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!TripExport.isZipName(name) || uri.getPathSegments().size() != 1) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(new File(PersistBackup.dir(), TripExport.DIR), name);
        if (!f.isFile()) throw new FileNotFoundException(f.getName());
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Bluetooth reads the name and size before it sends. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    // ── sending ────────────────────────────────────────────────────────────

    private static Intent sendIntent() {
        return new Intent(Intent.ACTION_SEND).setType(MIME);
    }

    /**
     * The system's Bluetooth share activity, or null where the head unit has none:
     * its package is OEM-specific (AOSP: com.android.bluetooth), so it is matched by name.
     */
    static ResolveInfo bluetoothTarget(Context c) {
        try {
            List<ResolveInfo> all = c.getPackageManager().queryIntentActivities(sendIntent(), 0);
            for (ResolveInfo r : all) {
                if (r.activityInfo == null) continue;
                String id = (r.activityInfo.packageName + "/" + r.activityInfo.name).toLowerCase(Locale.US);
                if (id.contains("bluetooth")) return r;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "bluetooth share lookup failed", e);
        }
        return null;
    }

    /** A Bluetooth share this app can reach: exported, or through Shizuku. */
    static boolean canSendBluetooth(Context c) {
        ResolveInfo bt = bluetoothTarget(c);
        if (bt == null) return false;
        if (bt.activityInfo.exported) return true;
        ShizukuShare s = shizuku;
        return s != null && s.available();
    }

    /** Opens the Bluetooth device picker for the zip; false when there is no Bluetooth share or the file is gone. */
    static boolean sendBluetooth(Context c, String zipName) {
        ResolveInfo bt = bluetoothTarget(c);
        if (bt == null || !TripExport.isZipName(zipName)) return false;
        File zip = new File(new File(PersistBackup.dir(), TripExport.DIR), zipName);
        if (!zip.isFile()) return false;
        if (!bt.activityInfo.exported) {
            ShizukuShare s = shizuku;
            if (s == null || !s.available()) return false;
            s.share(bt.activityInfo.packageName + "/" + bt.activityInfo.name, zip.getAbsolutePath());
            return true;
        }
        Uri uri = new Uri.Builder().scheme("content").authority(authority(c)).appendPath(zipName).build();
        Intent send = sendIntent()
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setClassName(bt.activityInfo.packageName, bt.activityInfo.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(send);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "bluetooth share failed", e);
            return false;
        }
    }
}
