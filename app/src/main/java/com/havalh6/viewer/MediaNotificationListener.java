package com.havalh6.viewer;

import android.app.Notification;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;

/**
 * Notification listener required for {@link MediaSessionManager#getActiveSessions}.
 * Session watch runs here (not from the Activity) so OEM/API media permission checks pass.
 * Enable via:
 * {@code adb shell cmd notification allow_listener com.havalh6.viewer/com.havalh6.viewer.MediaNotificationListener}
 */
public final class MediaNotificationListener extends NotificationListenerService {
    private static final String TAG = "H6Media";
    private static volatile MediaNotificationListener instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager manager;
    private MediaController controller;
    private MediaController.Callback controllerCb;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private ComponentName selfComponent;

    static MediaNotificationListener getInstance() {
        return instance;
    }

    static boolean isConnected() {
        return instance != null;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        selfComponent = new ComponentName(this, MediaNotificationListener.class);
        manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        Log.i(TAG, "Notification listener connected");
        startSessionWatch();
        MediaNowPlaying.onListenerConnected();
    }

    @Override
    public void onListenerDisconnected() {
        stopSessionWatch();
        instance = null;
        Log.i(TAG, "Notification listener disconnected");
        MediaNowPlaying.onListenerDisconnected();
        super.onListenerDisconnected();
    }

    void startSessionWatch() {
        stopSessionWatch();
        if (manager == null) return;
        sessionsListener = this::pickController;
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, selfComponent);
            pickController(manager.getActiveSessions(selfComponent));
        } catch (SecurityException e) {
            Log.w(TAG, "NLS still missing media session access", e);
            MediaNowPlaying.emitNeedsListener();
        } catch (Throwable t) {
            Log.w(TAG, "NLS session watch failed", t);
        }
    }

    void requestPush() {
        emitCurrent();
    }

    void prev() {
        if (!transport(PlaybackState.ACTION_SKIP_TO_PREVIOUS, () ->
                controller.getTransportControls().skipToPrevious())) {
            dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    void playPause() {
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (playing) {
            if (!transport(PlaybackState.ACTION_PAUSE, () ->
                    controller.getTransportControls().pause())) {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE);
            }
        } else {
            if (!transport(PlaybackState.ACTION_PLAY, () ->
                    controller.getTransportControls().play())) {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
            }
        }
    }

    void next() {
        if (!transport(PlaybackState.ACTION_SKIP_TO_NEXT, () ->
                controller.getTransportControls().skipToNext())) {
            dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
        }
    }

    boolean isPlaying() {
        if (controller == null) return false;
        PlaybackState st = controller.getPlaybackState();
        return st != null && st.getState() == PlaybackState.STATE_PLAYING;
    }

    private boolean transport(long action, Runnable run) {
        if (controller == null) return false;
        try {
            PlaybackState st = controller.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_ERROR) return false;
            if (st != null && (st.getActions() & action) == 0) return false;
            run.run();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "transport failed", t);
            return false;
        }
    }

    private void dispatchMediaKey(int keyCode) {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            long t = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, keyCode, 0));
            Log.i(TAG, "dispatched media key " + keyCode);
        } catch (Throwable t) {
            Log.w(TAG, "media key dispatch failed", t);
        }
    }

    private void stopSessionWatch() {
        if (manager != null && sessionsListener != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {}
        }
        sessionsListener = null;
        unbindController();
        controller = null;
    }

    private void unbindController() {
        if (controller != null && controllerCb != null) {
            try {
                controller.unregisterCallback(controllerCb);
            } catch (Exception ignored) {}
        }
        controllerCb = null;
    }

    private void pickController(List<MediaController> controllers) {
        MediaController next = null;
        MediaController fallback = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (c == null) continue;
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_ERROR) continue;
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    next = c;
                    break;
                }
                if (fallback == null) fallback = c;
            }
            if (next == null) next = fallback;
            // Last resort: take an ERROR session so art/title still show.
            if (next == null) {
                for (MediaController c : controllers) {
                    if (c != null) { next = c; break; }
                }
            }
        }
        if (controller == next) {
            emitCurrent();
            return;
        }
        unbindController();
        controller = next;
        if (controller != null) {
            controllerCb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    emitCurrent();
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    emitCurrent();
                    MediaNowPlaying.syncPositionFromListener(controller);
                }
            };
            try {
                controller.registerCallback(controllerCb, handler);
            } catch (Exception e) {
                Log.w(TAG, "registerCallback failed", e);
            }
        }
        emitCurrent();
        MediaNowPlaying.syncPositionFromListener(controller);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (controller == null || sbn == null) return;
        if (!sbn.getPackageName().equals(controller.getPackageName())) return;
        if (!MediaNowPlaying.needsArtwork()) return;
        emitCurrent();
    }

    private void emitCurrent() {
        MediaNowPlaying.emitFromController(this, controller);
    }

    /**
     * YouTube (and some other players) put the video thumbnail on the media
     * notification instead of {@code MediaMetadata} album art.
     */
    Bitmap artworkFromNotification(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        StatusBarNotification[] notifs;
        try {
            notifs = getActiveNotifications();
        } catch (Throwable t) {
            Log.w(TAG, "getActiveNotifications failed", t);
            return null;
        }
        if (notifs == null) return null;
        Bitmap best = null;
        int bestArea = 0;
        for (StatusBarNotification sbn : notifs) {
            if (sbn == null || !packageName.equals(sbn.getPackageName())) continue;
            Bitmap candidate = bitmapFromNotification(sbn.getNotification());
            if (candidate == null || candidate.isRecycled()) continue;
            int area = candidate.getWidth() * candidate.getHeight();
            if (area > bestArea) {
                best = candidate;
                bestArea = area;
            }
        }
        return best;
    }

    private Bitmap bitmapFromNotification(Notification n) {
        if (n == null) return null;
        Bitmap best = null;
        Bundle extras = n.extras;
        if (extras != null) {
            best = larger(best, extraBitmap(extras, Notification.EXTRA_PICTURE));
            best = larger(best, extraBitmap(extras, Notification.EXTRA_LARGE_ICON_BIG));
            best = larger(best, extraBitmap(extras, Notification.EXTRA_LARGE_ICON));
            if (Build.VERSION.SDK_INT >= 31) {
                best = larger(best, iconToBitmap(extraIcon(extras, Notification.EXTRA_PICTURE_ICON)));
            }
        }
        best = larger(best, iconToBitmap(n.getLargeIcon()));
        return usableArt(best);
    }

    private static Bitmap larger(Bitmap a, Bitmap b) {
        if (b == null || b.isRecycled()) return a;
        if (a == null || a.isRecycled()) return b;
        int aArea = a.getWidth() * a.getHeight();
        int bArea = b.getWidth() * b.getHeight();
        return bArea > aArea ? b : a;
    }

    /** Skip empty / tiny frames; keep small non-square thumbs (YouTube 16:9). */
    private static Bitmap usableArt(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int max = Math.max(w, h);
        if (max < 48) return null;
        if (w <= 120 && h <= 90) return null;
        if (max < 96 && Math.abs(w - h) < 8) return null;
        return bmp;
    }

    @SuppressWarnings("deprecation")
    private static Bitmap extraBitmap(Bundle extras, String key) {
        if (extras == null || key == null) return null;
        try {
            Object v;
            if (Build.VERSION.SDK_INT >= 33) {
                v = extras.getParcelable(key, Bitmap.class);
            } else {
                v = extras.getParcelable(key);
            }
            return v instanceof Bitmap ? (Bitmap) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Icon extraIcon(Bundle extras, String key) {
        if (extras == null || key == null) return null;
        try {
            Object v;
            if (Build.VERSION.SDK_INT >= 33) {
                v = extras.getParcelable(key, Icon.class);
            } else {
                v = extras.getParcelable(key);
            }
            return v instanceof Icon ? (Icon) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Bitmap iconToBitmap(Icon icon) {
        if (icon == null) return null;
        try {
            return drawableToBitmap(icon.loadDrawable(this));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable) d).getBitmap();
            return bmp == null || bmp.isRecycled() ? null : bmp;
        }
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        if (w <= 0) w = 256;
        if (h <= 0) h = 256;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return bmp;
    }
}
