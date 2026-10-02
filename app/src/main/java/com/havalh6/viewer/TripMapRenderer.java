package com.havalh6.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Saves the OpenStreetMap tiles under a trip as a small JPEG.
 *
 * <p>Owner decision (docs/features/energy-workspace.md): the online map is primary,
 * a plain background the fallback, and a low-res snapshot is taken when a trip
 * finishes so the trip never needs the network again. One image per trip, a few
 * tiles per trip, tiles cached on disk: well inside the OSM tile usage policy,
 * which asks for an identifying User-Agent, caching, and no bulk downloading.
 * The attribution the licence requires is drawn into the image itself.
 *
 * <p><b>The route is not baked in.</b> The first version drew it, which fixed
 * its colour forever; the page now colours it by speed / electric / fuel and
 * marks refuel stops, so the image carries only the tiles, and the render
 * returns the projection it used ({@link Result#view}) so the page can draw the
 * route exactly on top.
 *
 * <p>Runs on {@link TripMapWorker}'s thread, never on the recorder's: tile
 * fetches block for seconds.
 */
final class TripMapRenderer {
    static final int WIDTH = 640;
    static final int HEIGHT = 400;
    private static final int PAD = 36;
    private static final int MIN_ZOOM = 3;
    private static final int MAX_ZOOM = 17;
    private static final String TAG = "H6Trip";
    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String USER_AGENT = "HavalH6Viewer/1.0 (head-unit trip map)";
    static final String ATTRIBUTION = "© OpenStreetMap contributors";
    private static final int HTTP_TIMEOUT_MS = 8_000;
    private static final long TILE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    /** A saved snapshot and the world frame it shows: "z,ox,oy" in Web Mercator pixels. */
    static final class Result {
        final File file;
        final String view;

        Result(File file, String view) {
            this.file = file;
            this.view = view;
        }
    }

    private final File tileCache;
    private final File outDir;

    TripMapRenderer(Context context) {
        tileCache = new File(context.getCacheDir(), "osm-tiles");
        outDir = new File(context.getFilesDir(), "trip-maps");
    }

    File fileFor(long startMs) {
        return new File(outDir, startMs + ".jpg");
    }

    /**
     * Renders and saves the tiles framing the route. Returns null when a tile
     * could not be fetched (offline): the caller retries later and the page draws
     * the route on a plain background meanwhile.
     */
    Result render(long startMs, double[] lats, double[] lons) throws Exception {
        int n = Math.min(lats.length, lons.length);
        if (n < 2) return null;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (int i = 0; i < n; i++) {
            minLat = Math.min(minLat, lats[i]);
            maxLat = Math.max(maxLat, lats[i]);
            minLon = Math.min(minLon, lons[i]);
            maxLon = Math.max(maxLon, lons[i]);
        }
        int z = TripMapMath.fitZoom(minLat, maxLat, minLon, maxLon, WIDTH, HEIGHT, PAD, MIN_ZOOM, MAX_ZOOM);
        double cx = (TripMapMath.worldX(minLon, z) + TripMapMath.worldX(maxLon, z)) / 2;
        double cy = (TripMapMath.worldY(minLat, z) + TripMapMath.worldY(maxLat, z)) / 2;
        double ox = cx - WIDTH / 2.0;
        double oy = cy - HEIGHT / 2.0;

        Bitmap image = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(0xFFE8E6E1);
        int tiles = 1 << z;
        int tx0 = (int) Math.floor(ox / TripMapMath.TILE);
        int tx1 = (int) Math.floor((ox + WIDTH - 1) / TripMapMath.TILE);
        int ty0 = (int) Math.floor(oy / TripMapMath.TILE);
        int ty1 = (int) Math.floor((oy + HEIGHT - 1) / TripMapMath.TILE);
        Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int ty = ty0; ty <= ty1; ty++) {
            if (ty < 0 || ty >= tiles) continue;
            for (int tx = tx0; tx <= tx1; tx++) {
                Bitmap tile = tile(z, ((tx % tiles) + tiles) % tiles, ty);
                if (tile == null) {
                    image.recycle();
                    return null;
                }
                canvas.drawBitmap(tile, (float) (tx * TripMapMath.TILE - ox), (float) (ty * TripMapMath.TILE - oy), tilePaint);
                tile.recycle();
            }
        }

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(12f);
        text.setColor(0xFF333333);
        float tw = text.measureText(ATTRIBUTION);
        Paint back = new Paint();
        back.setColor(0xCCFFFFFF);
        canvas.drawRect(WIDTH - tw - 10, HEIGHT - 18, WIDTH, HEIGHT, back);
        canvas.drawText(ATTRIBUTION, WIDTH - tw - 5, HEIGHT - 5, text);

        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IllegalStateException("cannot create " + outDir);
        File out = fileFor(startMs);
        try (OutputStream os = new FileOutputStream(out)) {
            image.compress(Bitmap.CompressFormat.JPEG, 82, os);
        } finally {
            image.recycle();
        }
        String view = String.format(Locale.US, "%d,%.3f,%.3f", z, ox, oy);
        Log.w(TAG, String.format(Locale.US, "trip map %d: z%d, %d points, %d bytes", startMs, z, n, out.length()));
        return new Result(out, view);
    }

    /** A tile from the disk cache while fresh, else from the tile server. Null when unreachable. */
    private Bitmap tile(int z, int x, int y) {
        File cached = new File(tileCache, z + File.separator + x + File.separator + y + ".png");
        if (cached.isFile() && System.currentTimeMillis() - cached.lastModified() < TILE_MAX_AGE_MS) {
            Bitmap b = BitmapFactory.decodeFile(cached.getPath());
            if (b != null) return b;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(String.format(Locale.US, TILE_URL, z, x, y)).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "tile " + z + "/" + x + "/" + y + " HTTP " + conn.getResponseCode());
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) bytes.write(buf, 0, r);
            }
            byte[] data = bytes.toByteArray();
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            File dir = cached.getParentFile();
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                try (OutputStream os = new FileOutputStream(cached)) {
                    os.write(data);
                } catch (Exception ignored) {
                    // A cache that cannot be written only costs a refetch next time.
                }
            }
            return b;
        } catch (Exception e) {
            Log.w(TAG, "tile fetch failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
