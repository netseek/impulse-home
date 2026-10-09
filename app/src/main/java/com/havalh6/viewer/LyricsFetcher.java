package com.havalh6.viewer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics from LRCLIB (lrclib.net): free, no key, returns LRC.
 *
 * <p>Nothing on the car's bus or in any MediaSession carries lyrics, so they
 * are looked up by artist + title + duration. This is a blocking call meant for
 * a worker thread, and it is only made when the driver opens the lyrics view.
 *
 * <p>Results are cached on disk per track, misses included: a track LRCLIB does
 * not have would otherwise be re-requested on every skip back to it. A miss is
 * retried after {@link #MISS_TTL_MS}, because the catalogue grows and a failed
 * request on a flaky mobile link looks the same as "not found" from here.
 */
final class LyricsFetcher {
    private static final String TAG = "H6Lyrics";
    private static final String HOST = "https://lrclib.net/api/";
    private static final String UA = "H6Viewer/1.0";
    private static final long MISS_TTL_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_CACHE_FILES = 300;
    private static final int HTTP_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 700;
    /** Same recording is within a couple of seconds; a remaster or live cut is not. */
    private static final long DURATION_TOLERANCE_S = 3;
    private static final Pattern BRACKETS = Pattern.compile("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]");
    private static final Pattern DASH_SUFFIX = Pattern.compile(
            "\\s+-\\s+(?:.*(?:remaster|version|edit|mix|live|mono|stereo|feat\\.?|ft\\.?).*)$",
            Pattern.CASE_INSENSITIVE);

    /** Result of a lookup. {@code lrc} is empty when nothing synced was found. */
    static final class Result {
        final String lrc;
        /** True when the answer is final (a hit, or a cached/confirmed miss). */
        final boolean definitive;

        Result(String lrc, boolean definitive) {
            this.lrc = lrc == null ? "" : lrc;
            this.definitive = definitive;
        }
    }

    private final File cacheDir;

    LyricsFetcher(Context context) {
        File dir = new File(context.getCacheDir(), "lyrics");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.cacheDir = dir;
    }

    Result fetch(String artist, String title, String album, long durationMs) {
        artist = artist == null ? "" : artist.trim();
        title = title == null ? "" : title.trim();
        if (title.isEmpty()) return new Result("", true);

        String key = cacheKey(artist, title, durationMs);
        File hit = new File(cacheDir, key + ".lrc");
        File miss = new File(cacheDir, key + ".none");
        try {
            if (hit.isFile()) return new Result(readFile(hit), true);
            if (miss.isFile() && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) {
                return new Result("", true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cache read failed", t);
        }

        boolean reachable = true;
        String lrc = "";
        try {
            lrc = lookup(artist, title, album, durationMs);
        } catch (java.io.IOException e) {
            // Offline or the server is down: say nothing is cached, so the next
            // open tries again instead of remembering a network error as a miss.
            Log.w(TAG, "lookup failed: " + e);
            reachable = false;
        } catch (Throwable t) {
            Log.w(TAG, "lookup error", t);
        }

        if (!reachable) return new Result("", false);
        try {
            if (lrc.isEmpty()) {
                writeFile(miss, "");
            } else {
                writeFile(hit, lrc);
            }
            prune();
        } catch (Throwable t) {
            Log.w(TAG, "cache write failed", t);
        }
        Log.w(TAG, (lrc.isEmpty() ? "no synced lyrics: " : "lyrics " + lrc.length() + "B: ")
                + artist + " - " + title);
        return new Result(lrc, true);
    }

    private String lookup(String artist, String title, String album, long durationMs)
            throws Exception {
        long durS = durationMs > 0 ? Math.round(durationMs / 1000.0) : 0;
        String cleanTitle = cleanTitle(title);
        String cleanArtist = cleanArtist(artist);

        // Exact match first: one row, matched on duration, so the right recording.
        if (durS > 0 && !cleanArtist.isEmpty()) {
            String url = HOST + "get?track_name=" + enc(cleanTitle)
                    + "&artist_name=" + enc(cleanArtist)
                    + (album != null && !album.isEmpty() ? "&album_name=" + enc(album) : "")
                    + "&duration=" + durS;
            String body = httpGet(url);
            if (body != null) {
                String lrc = syncedOf(new JSONObject(body));
                if (!lrc.isEmpty()) return lrc;
            }
        }

        // Fuzzy: search, then take the closest duration that has synced lyrics.
        String url = HOST + "search?track_name=" + enc(cleanTitle)
                + (cleanArtist.isEmpty() ? "" : "&artist_name=" + enc(cleanArtist));
        String body = httpGet(url);
        if (body == null) return "";
        JSONArray rows = new JSONArray(body);
        String best = "";
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String lrc = syncedOf(row);
            if (lrc.isEmpty()) continue;
            if (durS <= 0) return lrc;
            double gap = Math.abs(row.optDouble("duration", 0) - durS);
            if (gap < bestGap) {
                bestGap = gap;
                best = lrc;
            }
        }
        // A synced file for a different cut would drift out of step the whole way
        // through, which is worse than showing nothing.
        return bestGap <= DURATION_TOLERANCE_S ? best : "";
    }

    private static String syncedOf(JSONObject row) {
        if (row.optBoolean("instrumental", false)) return "";
        String s = row.optString("syncedLyrics", "");
        return "null".equals(s) ? "" : s.trim();
    }

    /**
     * LRCLIB answers 503/429 in bursts while the connection is fine, and the
     * same request succeeds seconds later. A few quick retries absorb that so
     * it is not reported as "offline"; a persistent failure still throws.
     */
    private static String httpGet(String url) throws java.io.IOException {
        java.io.IOException last = null;
        for (int attempt = 0; attempt < HTTP_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                return httpGetOnce(url);
            } catch (java.io.IOException e) {
                last = e;
                Log.w(TAG, "attempt " + (attempt + 1) + " failed: " + e);
            }
        }
        throw last != null ? last : new java.io.IOException("interrupted");
    }

    /** Null on 404 (a clean miss); throws on anything that means "could not ask". */
    private static String httpGetOnce(String url) throws java.io.IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code == 404) return null;
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = is.read(tmp)) != -1) {
                    buf.write(tmp, 0, n);
                    if (buf.size() > 2 * 1024 * 1024) break;
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** "Song (feat. X)" and "Song - 2011 Remaster" both find the plain "Song". */
    static String cleanTitle(String title) {
        String t = BRACKETS.matcher(title).replaceAll("");
        t = DASH_SUFFIX.matcher(t).replaceAll("");
        t = t.trim();
        return t.isEmpty() ? title.trim() : t;
    }

    /** YouTube Music publishes "Artist - Topic"; take the first of "A, B" / "A & B". */
    static String cleanArtist(String artist) {
        String a = artist.replaceAll("(?i)\\s*-\\s*topic$", "");
        int cut = a.indexOf(',');
        if (cut > 0) a = a.substring(0, cut);
        return a.trim();
    }

    private static String enc(String s) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String cacheKey(String artist, String title, long durationMs) throws RuntimeException {
        try {
            String raw = (artist + "\u0001" + title + "\u0001" + (durationMs / 1000))
                    .toLowerCase(Locale.ROOT);
            byte[] d = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readFile(File f) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(File f, String text) throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Oldest first, so the tracks in current rotation survive. */
    private void prune() {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length <= MAX_CACHE_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_CACHE_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
