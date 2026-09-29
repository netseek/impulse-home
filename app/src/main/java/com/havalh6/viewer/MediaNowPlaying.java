package com.havalh6.viewer;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects now-playing from every source the head unit has and feeds the
 * winner to the WebView.
 *
 * <ul>
 *   <li>{@link MediaNotificationListener} — apps with a MediaSession.</li>
 *   <li>{@link MediaCenterSource} — Android Auto audio and USB media.</li>
 *   <li>{@link CarPlaySource} — iPhone projection.</li>
 * </ul>
 *
 * Projection audio never registers a MediaSession, so the last two are the only
 * way Android Auto / CarPlay show up at all.
 */
final class MediaNowPlaying {
    interface Callback {
        void onUpdate(JSONObject payload);
        void onPosition(long positionMs);
    }

    private static final String TAG = "H6Media";
    private static final long POSITION_INTERVAL_MS = 250;
    /** Cover art is upscaled to fill a ~620x370 card, so keep it well above that. */
    private static final int ART_MAX_PX = 720;
    private static final int ART_JPEG_QUALITY = 88;
    /** The source app's own icon, drawn beside its name on the card. */
    private static final int APP_ICON_PX = 96;
    private static final Pattern YT_ID = Pattern.compile(
            "(?:(?:youtube(?:-nocookie)?\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/|live/|v/)|youtu\\.be/|ytimg\\.com/vi(?:_webp)?/))([A-Za-z0-9_-]{11})");
    private static final Pattern YT_ID_BARE = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern SEARCH_VIDEO_ID =
            Pattern.compile("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"");
    /** Stop reading the results page once the top hit must have gone by. */
    private static final int SEARCH_SCAN_LIMIT = 2 * 1024 * 1024;
    private static final long SEARCH_RETRY_MS = 20000;

    private static volatile MediaNowPlaying INSTANCE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaCenterSource mediaCenter = new MediaCenterSource();
    private final CarPlaySource carPlay = new CarPlaySource();
    private ComponentName listenerComponent;
    private Callback callback;
    private Context appContext;
    private final java.util.Map<String, String> appIconCache = new java.util.HashMap<>();
    private Runnable positionTick;
    private Runnable accessRetry;
    private String lastArtKey = "";
    private String lastArtData = "";
    private Handler artHandler;
    private String inflightArtUri = "";
    private boolean started;

    private MediaTrack sessionTrack;
    private MediaTrack mediaCenterTrack;
    private MediaTrack carPlayTrack;
    private MediaController sessionController;
    private int lastWinnerSource;
    private String lastPayloadSignature = "";
    private String lastArtMissLog = "";
    private String inflightSearch = "";
    private String failedSearch = "";
    private long failedSearchAtMs;
    private final java.util.HashMap<String, String> searchedArtUrl = new java.util.HashMap<>();
    private final java.util.LinkedHashMap<String, String> artCache = new java.util.LinkedHashMap<>();

    void attach(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        this.listenerComponent = new ComponentName(context, MediaNotificationListener.class);
        INSTANCE = this;
        if (artHandler == null) {
            HandlerThread t = new HandlerThread("H6Art");
            t.start();
            artHandler = new Handler(t.getLooper());
        }
    }

    boolean hasListenerAccess() {
        if (appContext == null || listenerComponent == null) return false;
        if (MediaNotificationListener.isConnected()) return true;
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                NotificationManager nm =
                        (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && nm.isNotificationListenerAccessGranted(listenerComponent)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        String enabled = Settings.Secure.getString(
                appContext.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null || enabled.isEmpty()) return false;
        String flat = listenerComponent.flattenToString();
        String shortFlat = listenerComponent.flattenToShortString();
        for (String part : enabled.split(":")) {
            if (flat.equals(part) || shortFlat.equals(part)) return true;
        }
        return false;
    }

    void start() {
        started = true;
        stopAccessRetry();
        startProjectionSources();
        syncPositionTick();
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null) {
            Log.w(TAG, "Using connected notification listener for sessions");
            nls.startSessionWatch();
            nls.requestPush();
            return;
        }
        if (!hasListenerAccess()) {
            Log.w(TAG, "Notification listener not enabled — session media unavailable");
            clearSessionTrack();
            scheduleAccessRetry();
            return;
        }
        // Allowed in settings but service not bound yet — wait for onListenerConnected.
        Log.w(TAG, "Listener allowed; waiting for service connection");
        clearSessionTrack();
        scheduleAccessRetry();
    }

    /** Android Auto / CarPlay do not need the notification listener, so they start regardless. */
    private void startProjectionSources() {
        if (appContext == null) return;
        try {
            mediaCenter.start(appContext, track -> handler.post(() -> {
                mediaCenterTrack = track;
                emit();
            }));
        } catch (Throwable t) {
            Log.w(TAG, "MediaCenter source start failed", t);
        }
        try {
            carPlay.start(appContext, track -> handler.post(() -> {
                carPlayTrack = track;
                emit();
            }));
        } catch (Throwable t) {
            Log.w(TAG, "CarPlay source start failed", t);
        }
    }

    void stop() {
        started = false;
        stopAccessRetry();
        stopPositionTick();
        mediaCenter.stop();
        carPlay.stop();
    }

    boolean hasAndroidAutoTrack() {
        return mediaCenterTrack != null && mediaCenterTrack.hasTrack()
                && "ANDROID AUTO".equals(mediaCenterTrack.appLabel);
    }

    boolean hasCarPlayTrack() {
        return carPlayTrack != null && carPlayTrack.hasTrack();
    }

    /** Force a full payload — the page reloaded and has nothing on screen yet. */
    void pushNow() {
        lastPayloadSignature = "";
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null) nls.requestPush();
        else emit();
    }

    void prev() {
        MediaTrack winner = winner();
        if (winner != null && winner.source == MediaTrack.SRC_CARPLAY && carPlay.previous()) return;
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null && (winner == null || winner.source == MediaTrack.SRC_SESSION)) {
            nls.prev();
            return;
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    void playPause() {
        MediaTrack winner = winner();
        if (winner != null && winner.source == MediaTrack.SRC_CARPLAY
                && carPlay.setPlaying(!winner.playing)) {
            return;
        }
        if (winner != null && winner.source == MediaTrack.SRC_MEDIA_CENTER
                && mediaCenter.setPlaying(!winner.playing)) {
            return;
        }
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null && (winner == null || winner.source == MediaTrack.SRC_SESSION)) {
            nls.playPause();
            return;
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    void next() {
        MediaTrack winner = winner();
        if (winner != null && winner.source == MediaTrack.SRC_CARPLAY && carPlay.next()) return;
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null && (winner == null || winner.source == MediaTrack.SRC_SESSION)) {
            nls.next();
            return;
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    boolean isPlaying() {
        MediaTrack winner = winner();
        return winner != null && winner.playing;
    }

    private void dispatchMediaKey(int keyCode) {
        if (appContext == null) return;
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long t = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, keyCode, 0));
            Log.w(TAG, "media key " + keyCode);
        } catch (Throwable t) {
            Log.w(TAG, "media key failed", t);
        }
    }

    static void onListenerConnected() {
        MediaNowPlaying self = INSTANCE;
        if (self == null || !self.started) return;
        self.handler.post(() -> {
            self.stopAccessRetry();
            MediaNotificationListener nls = MediaNotificationListener.getInstance();
            if (nls != null) {
                nls.startSessionWatch();
                nls.requestPush();
            }
        });
    }

    static void onListenerDisconnected() {
        MediaNowPlaying self = INSTANCE;
        if (self == null) return;
        self.handler.post(() -> {
            self.clearSessionTrack();
            if (self.started) self.scheduleAccessRetry();
        });
    }

    static void emitNeedsListener() {
        MediaNowPlaying self = INSTANCE;
        if (self == null) return;
        self.handler.post(self::clearSessionTrack);
    }

    /** The notification listener re-pushes on notification posts while art is still missing. */
    static boolean needsArtwork() {
        MediaNowPlaying self = INSTANCE;
        if (self == null) return true;
        MediaTrack winner = self.winner();
        if (winner != null && winner.source != MediaTrack.SRC_SESSION) return false;
        String art = self.lastArtData;
        return art == null || art.isEmpty() || art.startsWith("http");
    }

    static void emitFromController(Context ctx, MediaController controller) {
        MediaNowPlaying self = INSTANCE;
        if (self == null || self.callback == null) return;
        self.handler.post(() -> self.readSession(ctx, controller));
    }

    static void syncPositionFromListener(MediaController controller) {
        MediaNowPlaying self = INSTANCE;
        if (self == null) return;
        self.handler.post(() -> {
            self.sessionController = controller;
            self.syncPositionTick();
        });
    }

    private void scheduleAccessRetry() {
        stopAccessRetry();
        accessRetry = () -> {
            MediaNotificationListener nls = MediaNotificationListener.getInstance();
            if (nls != null) {
                Log.w(TAG, "Notification listener connected — starting session watch");
                nls.startSessionWatch();
                nls.requestPush();
                return;
            }
            if (!hasListenerAccess()) clearSessionTrack();
            handler.postDelayed(accessRetry, 2500);
        };
        handler.postDelayed(accessRetry, 2500);
    }

    private void stopAccessRetry() {
        if (accessRetry != null) handler.removeCallbacks(accessRetry);
        accessRetry = null;
    }

    private void syncPositionTick() {
        stopPositionTick();
        positionTick = new Runnable() {
            @Override
            public void run() {
                MediaTrack winner = winner();
                if (winner != null && winner.playing && callback != null) {
                    callback.onPosition(livePosition(winner));
                }
                handler.postDelayed(this, POSITION_INTERVAL_MS);
            }
        };
        handler.postDelayed(positionTick, POSITION_INTERVAL_MS);
    }

    private void stopPositionTick() {
        if (positionTick != null) handler.removeCallbacks(positionTick);
        positionTick = null;
    }

    private long livePosition(MediaTrack track) {
        if (track.source == MediaTrack.SRC_SESSION && sessionController != null) {
            PlaybackState st = sessionController.getPlaybackState();
            if (st != null) return extrapolate(st, track.durationMs);
        }
        return track.currentPositionMs();
    }

    private void clearSessionTrack() {
        sessionTrack = null;
        sessionController = null;
        emit();
    }

    // ---------------------------------------------------------------- sources

    private void readSession(Context ctx, MediaController controller) {
        sessionController = controller;
        if (controller == null) {
            sessionTrack = null;
            emit();
            return;
        }
        Context useCtx = ctx != null ? ctx : appContext;
        MediaMetadata meta = controller.getMetadata();
        PlaybackState st = controller.getPlaybackState();

        MediaTrack track = new MediaTrack(MediaTrack.SRC_SESSION);
        track.title = meta == null ? "" : nz(meta.getString(MediaMetadata.METADATA_KEY_TITLE));
        track.artist = meta == null ? "" : nz(meta.getString(MediaMetadata.METADATA_KEY_ARTIST));
        track.album = meta == null ? "" : nz(meta.getString(MediaMetadata.METADATA_KEY_ALBUM));
        track.durationMs = meta == null ? 0 : Math.max(0, meta.getLong(MediaMetadata.METADATA_KEY_DURATION));
        if (st != null) {
            track.playing = st.getState() == PlaybackState.STATE_PLAYING;
            track.positionMs = extrapolate(st, track.durationMs);
            track.positionAtMs = SystemClock.elapsedRealtime();
        }
        String pkg = controller.getPackageName() == null ? "" : controller.getPackageName();
        track.packageName = pkg;
        track.appLabel = appLabel(useCtx, pkg);
        resolveSessionArt(track, meta);

        sessionTrack = track;
        emit();
    }

    /**
     * Resolves the app icon drawable, preferring our branded marks for projection
     * sources (Android Auto and CarPlay) before querying PackageManager.
     */
    private Drawable resolveAppIconDrawable(String pkg, String label, int source) {
        if (source == MediaTrack.SRC_CARPLAY || MainActivity.isCarPlayMediaSource(pkg, label)) {
            try {
                Drawable d = appContext.getDrawable(R.drawable.ic_carplay);
                if (d != null) return d;
            } catch (Throwable ignored) {}
            try {
                return appContext.getDrawable(R.drawable.ic_carplay_default);
            } catch (Throwable ignored) {}
        }
        if ((source == MediaTrack.SRC_MEDIA_CENTER && "ANDROID AUTO".equalsIgnoreCase(label))
                || MainActivity.isAndroidAutoMediaSource(pkg, label)) {
            try {
                Drawable d = appContext.getDrawable(R.drawable.ic_android_auto);
                if (d != null) return d;
            } catch (Throwable ignored) {}
            try {
                return appContext.getDrawable(R.drawable.ic_android_auto_default);
            } catch (Throwable ignored) {}
        }
        if (pkg != null && !pkg.isEmpty()) {
            try {
                return appContext.getPackageManager().getApplicationIcon(pkg);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String appIconDataUrl(String pkg) {
        return appIconDataUrl(pkg, null, 0);
    }

    private String appIconDataUrl(String pkg, String label, int source) {
        if (appContext == null) return "";
        if ((pkg == null || pkg.isEmpty()) && (label == null || label.isEmpty()) && source == 0) return "";
        String cacheKey = (pkg != null ? pkg : "") + "|" + (label != null ? label : "") + "|" + source;
        String cached = appIconCache.get(cacheKey);
        if (cached != null) return cached;
        if (pkg != null && !pkg.isEmpty() && appIconCache.containsKey(pkg) && (label == null || label.isEmpty()) && source == 0) {
            return appIconCache.get(pkg);
        }
        String encoded = "";
        try {
            Drawable icon = resolveAppIconDrawable(pkg, label, source);
            if (icon != null) {
                int size = APP_ICON_PX;
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                icon.setBounds(0, 0, size, size);
                icon.draw(canvas);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                bmp.recycle();
                encoded = "data:image/png;base64,"
                        + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Exception ignored) {}
        if (appIconCache.size() >= 8) appIconCache.clear();
        if (pkg != null && !pkg.isEmpty()) appIconCache.put(pkg, encoded);
        appIconCache.put(cacheKey, encoded);
        return encoded;
    }

    /**
     * Whether this package can be brought to the front at all.
     *
     * A projection source can publish a track from a package with no launcher
     * entry, and launchAppFullscreen returns silently when there is no launch
     * intent — so the card has to know, or it offers a tap that does nothing.
     */
    private boolean canLaunch(String pkg) {
        if (appContext == null || pkg == null || pkg.isEmpty()) return false;
        // AapActivity is not exported; PackageManager often has no launcher
        // entry, but the live projection task can still be raised.
        if (ProjectionPresence.isProjectionPackage(pkg)) return true;
        try {
            return appContext.getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String appLabel(Context ctx, String pkg) {
        if (ctx == null || pkg.isEmpty()) return pkg;
        try {
            android.content.pm.ApplicationInfo info = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = ctx.getPackageManager().getApplicationLabel(info);
            if (label != null) return label.toString();
        } catch (Exception ignored) {}
        return pkg;
    }

    private static long extrapolate(PlaybackState st, long durationMs) {
        long position = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            long elapsed = SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
            float speed = st.getPlaybackSpeed() > 0 ? st.getPlaybackSpeed() : 1f;
            position = Math.max(0, position + (long) (elapsed * speed));
            if (durationMs > 0) position = Math.min(position, durationMs);
        }
        return Math.max(0, position);
    }

    // ------------------------------------------------------------ arbitration

    /** Playing beats paused; between equals, projection beats a plain session. */
    private MediaTrack winner() {
        MediaTrack best = null;
        int bestScore = Integer.MIN_VALUE;
        // Both projection sources publish null when they go quiet, so a track
        // sitting here is still current.
        MediaTrack[] candidates = {carPlayTrack, mediaCenterTrack, sessionTrack};
        for (MediaTrack t : candidates) {
            if (t == null || !t.hasTrack()) continue;
            int score = (t.playing ? 100 : 0) + t.source;
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        // A bound session with no metadata yet still names the app on the rail.
        if (best == null) best = sessionTrack;
        return best;
    }

    private void emit() {
        if (callback == null) return;
        MediaTrack winner = winner();
        if (winner == null) {
            boolean needsListener = !hasListenerAccess() || !MediaNotificationListener.isConnected();
            lastArtKey = "";
            lastArtData = "";
            lastWinnerSource = 0;
            if (!changed("empty|" + needsListener)) return;
            callback.onUpdate(emptyPayload(needsListener));
            return;
        }
        if (winner.source != lastWinnerSource) {
            lastWinnerSource = winner.source;
            Log.w(TAG, "now playing source=" + winner.appLabel + " (" + winner.packageName + ")");
        }
        // MediaCenter polls every 1.5s; only push when something other than the
        // position moved, or the WebView re-renders the card (and the cover)
        // several times a second.
        String art = artDataUrl(winner);
        String signature = winner.source + "|" + winner.packageName + "|" + winner.title + "|"
                + winner.artist + "|" + winner.album + "|" + winner.durationMs + "|"
                + winner.playing + "|" + winner.appLabel + "|" + lastArtKey + "|" + art.length()
                + "|" + canLaunch(winner.packageName)
                + "|" + appIconDataUrl(winner.packageName, winner.appLabel, winner.source).length();
        if (!changed(signature)) return;

        JSONObject o = emptyPayload(false);
        try {
            o.put("title", winner.title);
            o.put("artist", winner.artist);
            o.put("album", winner.album);
            o.put("durationMs", winner.durationMs);
            o.put("positionMs", livePosition(winner));
            o.put("playing", winner.playing);
            o.put("appLabel", winner.appLabel);
            o.put("packageName", winner.packageName);
            o.put("hasTrack", !winner.title.isEmpty() || !winner.artist.isEmpty());
            o.put("artDataUrl", art);
            o.put("appIcon", appIconDataUrl(winner.packageName, winner.appLabel, winner.source));
            o.put("canLaunch", canLaunch(winner.packageName));
            o.put("needsListener", false);
        } catch (Exception e) {
            Log.w(TAG, "emit failed", e);
        }
        callback.onUpdate(o);
    }

    /** Base64 covers are ~100KB each, so keep only the handful in active rotation. */
    private void cacheArt(String key, String dataUrl) {
        if (key.isEmpty() || dataUrl.isEmpty()) return;
        if (artCache.size() >= 3) {
            java.util.Iterator<String> it = artCache.keySet().iterator();
            it.next();
            it.remove();
        }
        artCache.put(key, dataUrl);
    }

    private boolean changed(String signature) {
        if (signature.equals(lastPayloadSignature)) return false;
        lastPayloadSignature = signature;
        return true;
    }

    private static JSONObject emptyPayload(boolean needsListener) {
        JSONObject o = new JSONObject();
        try {
            o.put("title", "");
            o.put("artist", "");
            o.put("album", "");
            o.put("durationMs", 0);
            o.put("positionMs", 0);
            o.put("playing", false);
            o.put("appLabel", needsListener ? "ENABLE MEDIA ACCESS" : "");
            o.put("packageName", "");
            o.put("artDataUrl", "");
            o.put("appIcon", "");
            o.put("canLaunch", false);
            o.put("hasTrack", false);
            o.put("needsListener", needsListener);
        } catch (Exception ignored) {}
        return o;
    }

    // --------------------------------------------------------------- artwork

    private String artDataUrl(MediaTrack track) {
        String key = track.source + "|" + track.artKey;
        // Keyed by art identity, not by winner, so flipping between sources
        // (a session dropping out for a moment) does not re-encode or re-download.
        String cached = artCache.get(key);
        if (cached != null) {
            lastArtKey = key;
            lastArtData = cached;
            return cached;
        }
        if (track.art != null && !track.art.isRecycled()) {
            lastArtKey = key;
            lastArtData = encodeArt(track.art);
            cacheArt(key, lastArtData);
            Log.w(TAG, "art " + track.art.getWidth() + "x" + track.art.getHeight()
                    + " src=" + track.appLabel);
            return lastArtData;
        }
        if (!track.remoteArtUrl.isEmpty()) {
            lastArtKey = key;
            lastArtData = track.remoteArtUrl;
            scheduleArtworkDownload(track.remoteArtUrl, key);
            return lastArtData;
        }
        if (!track.artSearchQuery.isEmpty()) {
            scheduleYouTubeLookup(track.artSearchQuery, key);
        }
        lastArtKey = "";
        lastArtData = "";
        return "";
    }

    /**
     * Resolve a YouTube video by title and use its thumbnail as the cover.
     * One request per track, cached both ways so a miss is not retried in a loop.
     */
    private void scheduleYouTubeLookup(String query, String expectKey) {
        if (artHandler == null || query.isEmpty()) return;
        if (query.equals(inflightSearch)) return;
        // A miss is usually a flaky mobile connection, not a missing video, so
        // retry after a cooldown instead of giving up on the track.
        if (query.equals(failedSearch)
                && SystemClock.elapsedRealtime() - failedSearchAtMs < SEARCH_RETRY_MS) {
            return;
        }
        String cached = searchedArtUrl.get(query);
        if (cached != null) {
            scheduleArtworkDownload(cached, expectKey);
            return;
        }
        inflightSearch = query;
        artHandler.post(() -> {
            String videoId = searchYouTubeVideoId(query);
            handler.post(() -> {
                inflightSearch = "";
                if (videoId == null) {
                    failedSearch = query;
                    failedSearchAtMs = SystemClock.elapsedRealtime();
                    Log.w(TAG, "youtube lookup failed for " + query);
                    return;
                }
                String url = "https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg";
                if (searchedArtUrl.size() > 64) searchedArtUrl.clear();
                searchedArtUrl.put(query, url);
                Log.w(TAG, "youtube lookup " + query + " -> " + videoId);
                scheduleArtworkDownload(url, expectKey);
            });
        });
    }

    private static String searchYouTubeVideoId(String query) {
        HttpURLConnection conn = null;
        try {
            String url = "https://www.youtube.com/results?hl=en&search_query="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            // The results page is large and the car is often on mobile data.
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                            + " Chrome/124.0 Safari/537.36");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            // The first videoId in the results payload is the top hit; stop there
            // instead of buffering the whole (multi-MB) page.
            try (InputStream is = conn.getInputStream()) {
                byte[] chunk = new byte[16384];
                StringBuilder window = new StringBuilder();
                int read;
                int total = 0;
                while ((read = is.read(chunk)) != -1 && total < SEARCH_SCAN_LIMIT) {
                    total += read;
                    window.append(new String(chunk, 0, read, "UTF-8"));
                    Matcher m = SEARCH_VIDEO_ID.matcher(window);
                    if (m.find()) return m.group(1);
                    if (window.length() > 32768) window.delete(0, window.length() - 64);
                }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "youtube lookup error: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Session artwork, best resolution first.
     *
     * <p>Bitmaps that travel inside {@code MediaMetadata} are capped at 320dp by
     * the framework, so an art URI we can decode ourselves usually beats the
     * bitmap the app handed us. YouTube gives neither and only leaves a video
     * id behind, which resolves to an i.ytimg thumbnail.
     */
    private void resolveSessionArt(MediaTrack track, MediaMetadata meta) {
        String uri = artworkUri(meta);
        Bitmap parceled = usable(artworkBitmap(meta));
        Bitmap fromUri = usable(loadLocalArtworkUri(uri));
        Bitmap best = larger(parceled, fromUri);

        if (best == null) {
            MediaNotificationListener nls = MediaNotificationListener.getInstance();
            if (nls != null) best = usable(nls.artworkFromNotification(track.packageName));
        }

        String remote = remoteArtworkUrl(meta, uri);
        // A notification-sized thumbnail loses to a real remote cover.
        if (best != null && !remote.isEmpty() && Math.max(best.getWidth(), best.getHeight()) < 320) {
            best = null;
        }

        track.art = best;
        track.remoteArtUrl = best == null ? remote : "";
        if (best == null && remote.isEmpty() && isYouTubeApp(track.packageName)) {
            // The YouTube app (and ReVanced) publishes title, channel and
            // duration and nothing else — no art bitmap, no art URI, not even
            // the video id. The title is exact, so the video can be looked up.
            track.artSearchQuery = (track.title + " " + track.artist).trim();
        }
        track.artKey = (best == null
                        ? "0"
                        : best.getWidth() + "x" + best.getHeight() + ":" + best.hashCode())
                + "|" + uri + "|" + remote + "|" + track.artSearchQuery + "|" + track.packageName;

        if (!track.hasArtSource()) logArtMiss(track.packageName, meta);
    }

    private static boolean isYouTubeApp(String packageName) {
        return packageName != null && packageName.toLowerCase(Locale.US).contains("youtube");
    }

    /** One line per distinct metadata shape — the only way to see what a player actually exposes. */
    private void logArtMiss(String packageName, MediaMetadata meta) {
        StringBuilder sb = new StringBuilder();
        if (meta != null) {
            try {
                for (String k : meta.keySet()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(k == null ? "?" : k.replace("android.media.metadata.", ""));
                    String v = null;
                    try {
                        v = meta.getString(k);
                    } catch (Throwable ignored) {}
                    if (v != null && !v.isEmpty()) {
                        sb.append('=').append(v.length() > 60 ? v.substring(0, 60) : v);
                    } else if (meta.getBitmap(k) != null) {
                        sb.append("=<bitmap ").append(meta.getBitmap(k).getWidth())
                                .append('x').append(meta.getBitmap(k).getHeight()).append('>');
                    }
                }
            } catch (Throwable ignored) {}
        }
        String line = "art miss pkg=" + packageName + " meta[" + sb + "]";
        if (line.equals(lastArtMissLog)) return;
        lastArtMissLog = line;
        Log.w(TAG, line);
    }

    private static Bitmap larger(Bitmap a, Bitmap b) {
        if (b == null) return a;
        if (a == null) return b;
        return b.getWidth() * b.getHeight() > a.getWidth() * a.getHeight() ? b : a;
    }

    /** YouTube puts the video thumbnail on DISPLAY_ICON, not ALBUM_ART. */
    private static Bitmap artworkBitmap(MediaMetadata meta) {
        if (meta == null) return null;
        Bitmap best = null;
        try {
            MediaDescription desc = meta.getDescription();
            if (desc != null) best = larger(best, desc.getIconBitmap());
        } catch (Throwable ignored) {}
        best = larger(best, meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART));
        best = larger(best, meta.getBitmap(MediaMetadata.METADATA_KEY_ART));
        best = larger(best, meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON));
        return best;
    }

    private static String artworkUri(MediaMetadata meta) {
        if (meta == null) return "";
        String uri = nz(meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
        if (uri.isEmpty()) uri = nz(meta.getString(MediaMetadata.METADATA_KEY_ART_URI));
        if (uri.isEmpty()) uri = nz(meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
        if (uri.isEmpty()) {
            try {
                MediaDescription desc = meta.getDescription();
                if (desc != null && desc.getIconUri() != null) uri = desc.getIconUri().toString();
            } catch (Throwable ignored) {}
        }
        if (!uri.isEmpty()) return uri;
        try {
            for (String k : meta.keySet()) {
                if (k == null) continue;
                String v = meta.getString(k);
                if (v == null || v.isEmpty()) continue;
                String lower = v.toLowerCase(Locale.US);
                if (lower.contains("ytimg.com") || lower.contains("ggpht.com")
                        || lower.startsWith("http://") || lower.startsWith("https://")
                        || lower.startsWith("content://")) {
                    String kl = k.toLowerCase(Locale.US);
                    if (kl.contains("art") || kl.contains("icon") || kl.contains("thumb")
                            || kl.contains("image") || kl.contains("cover")
                            || lower.contains("ytimg") || lower.contains("youtube")) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String remoteArtworkUrl(MediaMetadata meta, String uri) {
        String ytId = youtubeVideoId(uri);
        if (ytId == null) ytId = youtubeVideoIdFromMeta(meta);
        if (ytId != null) return "https://i.ytimg.com/vi/" + ytId + "/maxresdefault.jpg";
        if (isHttp(uri)) return uri;
        return "";
    }

    private static String youtubeVideoIdFromMeta(MediaMetadata meta) {
        if (meta == null) return null;
        String id = youtubeVideoIdOrBare(nz(meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)));
        if (id == null) id = youtubeVideoId(nz(meta.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)));
        if (id == null) id = youtubeVideoId(nz(meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)));
        if (id != null) return id;
        try {
            MediaDescription desc = meta.getDescription();
            if (desc != null) {
                id = youtubeVideoIdOrBare(desc.getMediaId());
                if (id == null && desc.getMediaUri() != null) {
                    id = youtubeVideoId(desc.getMediaUri().toString());
                }
                if (id != null) return id;
            }
        } catch (Throwable ignored) {}
        try {
            for (String k : meta.keySet()) {
                String found = youtubeVideoId(meta.getString(k));
                if (found != null) return found;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String youtubeVideoId(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = YT_ID.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String youtubeVideoIdOrBare(String s) {
        String id = youtubeVideoId(s);
        if (id != null) return id;
        if (s != null && YT_ID_BARE.matcher(s).matches()) return s;
        return null;
    }

    private static boolean isHttp(String uri) {
        if (uri == null) return false;
        String u = uri.toLowerCase(Locale.US);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** 120x90 gray frame YouTube returns when a quality doesn't exist; tiny square = app icon. */
    private static Bitmap usable(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int max = Math.max(w, h);
        if (max < 48) return null;
        if (w <= 120 && h <= 90) return null;
        if (max < 96 && Math.abs(w - h) < 8) return null;
        return bmp;
    }

    private void scheduleArtworkDownload(String uri, String expectKey) {
        if (artHandler == null || uri == null || uri.isEmpty()) return;
        if (uri.equals(inflightArtUri)) return;
        inflightArtUri = uri;
        artHandler.post(() -> {
            Bitmap bmp = downloadArtwork(uri);
            handler.post(() -> {
                if (!uri.equals(inflightArtUri)) return;
                inflightArtUri = "";
                if (bmp == null || bmp.isRecycled()) {
                    Log.w(TAG, "art download failed " + uri);
                    return;
                }
                lastArtKey = expectKey;
                lastArtData = encodeArt(bmp);
                cacheArt(expectKey, lastArtData);
                Log.w(TAG, "art downloaded " + bmp.getWidth() + "x" + bmp.getHeight() + " " + uri);
                MediaNotificationListener nls = MediaNotificationListener.getInstance();
                if (nls != null) nls.requestPush();
                else emit();
            });
        });
    }

    private Bitmap downloadArtwork(String uri) {
        String ytId = youtubeVideoId(uri);
        if (ytId != null) {
            // Highest first: maxres only exists for HD uploads, hq720 for most.
            String[] urls = {
                    "https://i.ytimg.com/vi/" + ytId + "/maxresdefault.jpg",
                    "https://i.ytimg.com/vi/" + ytId + "/hq720.jpg",
                    "https://i.ytimg.com/vi/" + ytId + "/hqdefault.jpg",
                    "https://i.ytimg.com/vi/" + ytId + "/mqdefault.jpg"
            };
            for (String u : urls) {
                Bitmap b = downloadBitmap(u);
                if (usable(b) != null) return b;
            }
            return null;
        }
        return downloadBitmap(uri);
    }

    private static Bitmap downloadBitmap(String uri) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "H6Viewer/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
                byte[] data = buf.toByteArray();
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Throwable t) {
            Log.w(TAG, "art http failed " + uri, t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String encodeArt(Bitmap bmp) {
        Bitmap scaled = scaleArt(bmp);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, ART_JPEG_QUALITY, baos);
        return "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    /** Load content/file URIs locally. http(s) is handled by {@link #scheduleArtworkDownload}. */
    private Bitmap loadLocalArtworkUri(String uri) {
        if (appContext == null || uri == null || uri.isEmpty() || isHttp(uri)) return null;
        Uri parsed;
        try {
            parsed = Uri.parse(uri);
        } catch (Throwable t) {
            return null;
        }
        String scheme = parsed.getScheme();
        if (scheme == null) return null;
        try (InputStream is = appContext.getContentResolver().openInputStream(parsed)) {
            if (is == null) return null;
            return BitmapFactory.decodeStream(is);
        } catch (Throwable t) {
            Log.w(TAG, "art uri load failed", t);
            return null;
        }
    }

    private static Bitmap scaleArt(Bitmap src) {
        Bitmap soft = src;
        if (Build.VERSION.SDK_INT >= 26 && src.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, false);
            if (copy != null) soft = copy;
        }
        int w = soft.getWidth();
        int h = soft.getHeight();
        if (w <= ART_MAX_PX && h <= ART_MAX_PX) return soft;
        float s = Math.min(ART_MAX_PX / (float) w, ART_MAX_PX / (float) h);
        return Bitmap.createScaledBitmap(
                soft, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
