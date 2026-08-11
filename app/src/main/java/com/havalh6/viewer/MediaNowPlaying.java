package com.havalh6.viewer;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Bridge between {@link MediaNotificationListener} (where sessions are watched)
 * and the WebView now-playing UI.
 */
final class MediaNowPlaying {
    interface Callback {
        void onUpdate(JSONObject payload);
        void onPosition(long positionMs);
    }

    private static final String TAG = "H6Media";
    private static final long POSITION_INTERVAL_MS = 250;

    private static volatile MediaNowPlaying INSTANCE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ComponentName listenerComponent;
    private Callback callback;
    private Context appContext;
    private Runnable positionTick;
    private Runnable accessRetry;
    private String lastArtKey = "";
    private String lastArtData = "";
    private boolean started;

    void attach(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        this.listenerComponent = new ComponentName(context, MediaNotificationListener.class);
        INSTANCE = this;
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
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null) {
            Log.i(TAG, "Using connected notification listener for sessions");
            nls.startSessionWatch();
            nls.requestPush();
            return;
        }
        if (!hasListenerAccess()) {
            Log.w(TAG, "Notification listener not enabled — now-playing unavailable");
            emitEmpty(true);
            scheduleAccessRetry();
            return;
        }
        // Allowed in settings but service not bound yet — wait for onListenerConnected.
        Log.i(TAG, "Listener allowed; waiting for service connection");
        emitEmpty(true);
        scheduleAccessRetry();
    }

    void stop() {
        started = false;
        stopAccessRetry();
        stopPositionTick();
    }

    void pushNow() {
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null) nls.requestPush();
        else emitEmpty(!hasListenerAccess() || !MediaNotificationListener.isConnected());
    }

    void prev() {
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null) {
            nls.prev();
            return;
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    void playPause() {
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null) {
            nls.playPause();
            return;
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    void next() {
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        if (nls != null) {
            nls.next();
            return;
        }
        dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    boolean isPlaying() {
        MediaNotificationListener nls = MediaNotificationListener.getInstance();
        return nls != null && nls.isPlaying();
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
            Log.i(TAG, "media key " + keyCode);
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
            self.stopPositionTick();
            self.emitEmpty(true);
            if (self.started) self.scheduleAccessRetry();
        });
    }

    static void emitNeedsListener() {
        MediaNowPlaying self = INSTANCE;
        if (self == null) return;
        self.handler.post(() -> self.emitEmpty(true));
    }

    static void emitFromController(Context ctx, MediaController controller) {
        MediaNowPlaying self = INSTANCE;
        if (self == null || self.callback == null) return;
        self.handler.post(() -> self.emitCurrent(ctx, controller));
    }

    static void syncPositionFromListener(MediaController controller) {
        MediaNowPlaying self = INSTANCE;
        if (self == null) return;
        self.handler.post(() -> self.syncPositionTick(controller));
    }

    private void scheduleAccessRetry() {
        stopAccessRetry();
        accessRetry = () -> {
            MediaNotificationListener nls = MediaNotificationListener.getInstance();
            if (nls != null) {
                Log.i(TAG, "Notification listener connected — starting session watch");
                nls.startSessionWatch();
                nls.requestPush();
                return;
            }
            if (!hasListenerAccess()) {
                emitEmpty(true);
            }
            handler.postDelayed(accessRetry, 2500);
        };
        handler.postDelayed(accessRetry, 2500);
    }

    private void stopAccessRetry() {
        if (accessRetry != null) handler.removeCallbacks(accessRetry);
        accessRetry = null;
    }

    private void syncPositionTick(MediaController controller) {
        stopPositionTick();
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (!playing || controller == null) return;
        final MediaController ctrl = controller;
        positionTick = new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    PlaybackState s = ctrl.getPlaybackState();
                    if (s != null) callback.onPosition(s.getPosition());
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

    private void emitEmpty(boolean needsListener) {
        lastArtKey = "";
        lastArtData = "";
        if (callback == null) return;
        callback.onUpdate(emptyPayload(needsListener));
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
            o.put("hasTrack", false);
            o.put("needsListener", needsListener);
        } catch (Exception ignored) {}
        return o;
    }

    private void emitCurrent(Context ctx, MediaController controller) {
        if (callback == null) return;
        if (controller == null) {
            emitEmpty(false);
            return;
        }
        Context useCtx = ctx != null ? ctx : appContext;
        MediaMetadata meta = controller.getMetadata();
        PlaybackState st = controller.getPlaybackState();
        JSONObject o = emptyPayload(false);
        try {
            String title = meta == null ? "" : nz(meta.getString(MediaMetadata.METADATA_KEY_TITLE));
            String artist = meta == null ? "" : nz(meta.getString(MediaMetadata.METADATA_KEY_ARTIST));
            String album = meta == null ? "" : nz(meta.getString(MediaMetadata.METADATA_KEY_ALBUM));
            long duration = meta == null ? 0 : meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = 0;
            boolean playing = false;
            if (st != null) {
                playing = st.getState() == PlaybackState.STATE_PLAYING;
                position = st.getPosition();
                if (playing && st.getLastPositionUpdateTime() > 0) {
                    long elapsed = android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
                    float speed = st.getPlaybackSpeed() > 0 ? st.getPlaybackSpeed() : 1f;
                    position = Math.max(0, position + (long) (elapsed * speed));
                    if (duration > 0) position = Math.min(position, duration);
                }
            }
            String pkg = controller.getPackageName() == null ? "" : controller.getPackageName();
            String appLabel = pkg;
            if (useCtx != null) {
                try {
                    android.content.pm.ApplicationInfo info =
                            useCtx.getPackageManager().getApplicationInfo(pkg, 0);
                    CharSequence label = useCtx.getPackageManager().getApplicationLabel(info);
                    if (label != null) appLabel = label.toString();
                } catch (Exception ignored) {}
            }

            o.put("title", title);
            o.put("artist", artist);
            o.put("album", album);
            o.put("durationMs", duration);
            o.put("positionMs", position);
            o.put("playing", playing);
            o.put("appLabel", appLabel);
            o.put("packageName", pkg);
            o.put("hasTrack", !title.isEmpty() || !artist.isEmpty());
            o.put("artDataUrl", artworkDataUrl(meta));
            o.put("needsListener", false);
        } catch (Exception e) {
            Log.w(TAG, "emitCurrent failed", e);
        }
        callback.onUpdate(o);
    }

    private String artworkDataUrl(MediaMetadata meta) {
        if (meta == null) return "";
        Bitmap bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
        String uri = nz(meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
        if (uri.isEmpty()) uri = nz(meta.getString(MediaMetadata.METADATA_KEY_ART_URI));
        String key = (bmp == null ? "0" : (bmp.getWidth() + "x" + bmp.getHeight() + ":" + bmp.hashCode()))
                + "|" + uri;
        if (key.equals(lastArtKey)) return lastArtData;
        lastArtKey = key;
        if (bmp == null) {
            lastArtData = "";
            return "";
        }
        Bitmap scaled = scaleArt(bmp);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 72, baos);
        lastArtData = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        return lastArtData;
    }

    private static Bitmap scaleArt(Bitmap src) {
        int max = 512;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= max && h <= max) return src;
        float s = Math.min(max / (float) w, max / (float) h);
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
