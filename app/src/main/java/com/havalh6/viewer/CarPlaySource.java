package com.havalh6.viewer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;

/**
 * Now-playing from the CarPlay projection service (com.ts.carplay).
 *
 * <p>CarPlay never registers a {@code MediaSession} either, and unlike Android
 * Auto it does not report through MediaCenter — it pushes metadata (including
 * the cover as raw JPEG bytes, at full resolution) to a registered callback
 * binder. Transaction ids and the parcel layout come from Haval Impulse
 * (`CarPlayNowPlayingMonitor`), which drives the same service.
 */
final class CarPlaySource {
    interface Listener {
        /** Called off the main thread; {@code null} means CarPlay has nothing to show. */
        void onCarPlayTrack(MediaTrack track);
    }

    private static final String TAG = "H6Media";

    private static final String PACKAGE = "com.ts.carplay";
    private static final String SERVICE_CLASS = "com.ts.carplay.CarPlayService";
    private static final String SERVICE_ACTION = "com.ts.carplay.action.CarPlayService";
    private static final String SERVICE_DESCRIPTOR = "com.ts.carplay.common.aidl.ICarPlayService";
    private static final String CALLBACK_DESCRIPTOR =
            "com.ts.carplay.common.aidl.INowPlayingUpdateCallback";

    private static final int TX_START_NOW_PLAYING = 9;
    private static final int TX_STOP_NOW_PLAYING = 10;
    private static final int TX_SEND_HID_OVER_IAP = 25;
    private static final int TX_NOW_PLAYING_UPDATE = 1;

    private static final int PLAYBACK_PLAYING = 1;
    private static final int HID_PLAY = 1;
    private static final int HID_PAUSE = 2;
    private static final int HID_NEXT = 4;
    private static final int HID_PREVIOUS = 8;
    private static final int HID_DOWN = 0;
    private static final int HID_UP = 1;
    private static final long HID_PRESS_MS = 90;

    private static final long WATCHDOG_MS = 5000;
    /** CarPlay interleaves blank frames with real ones; only a sustained blank is a stop. */
    private static final long BLANK_GRACE_MS = 4000;

    private Context appContext;
    private Listener listener;
    private Handler worker;
    private ServiceConnection connection;
    private volatile IBinder serviceBinder;
    private volatile boolean callbackRegistered;
    private volatile long lastValidAtMs;
    private boolean started;
    private String lastLogged = "";
    private String lastArtKey = "";
    private Bitmap lastArt;

    private final NowPlayingCallback callback = new NowPlayingCallback();

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                bind();
                if (serviceBinder != null && serviceBinder.isBinderAlive() && !callbackRegistered) {
                    registerCallback();
                }
            } catch (Throwable t) {
                Log.w(TAG, "CarPlay watchdog failed", t);
            }
            if (worker != null) worker.postDelayed(this, WATCHDOG_MS);
        }
    };

    void start(Context context, Listener listener) {
        if (started) return;
        started = true;
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        HandlerThread t = new HandlerThread("H6CarPlay");
        t.start();
        worker = new Handler(t.getLooper());
        worker.post(watchdog);
    }

    void stop() {
        started = false;
        if (worker != null) worker.removeCallbacks(watchdog);
        unregisterCallback();
        unbind();
    }

    private void bind() {
        if (connection != null) return;
        if (appContext == null) return;
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceBinder = service;
                callbackRegistered = false;
                Log.w(TAG, "CarPlay service connected");
                registerCallback();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                serviceBinder = null;
                callbackRegistered = false;
                Log.w(TAG, "CarPlay service disconnected");
                publish(null, "service disconnected");
            }
        };
        boolean bound = false;
        try {
            Intent explicit = new Intent().setComponent(new ComponentName(PACKAGE, SERVICE_CLASS));
            bound = appContext.bindService(explicit, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                Intent byAction = new Intent(SERVICE_ACTION).setPackage(PACKAGE);
                bound = appContext.bindService(byAction, connection, Context.BIND_AUTO_CREATE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "CarPlay bind failed", t);
        }
        if (!bound) {
            try {
                appContext.unbindService(connection);
            } catch (Throwable ignored) {}
            connection = null;
        }
    }

    private void unbind() {
        if (connection != null && appContext != null) {
            try {
                appContext.unbindService(connection);
            } catch (Throwable ignored) {}
        }
        connection = null;
        serviceBinder = null;
        callbackRegistered = false;
    }

    private void registerCallback() {
        IBinder binder = serviceBinder;
        if (binder == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(callback);
            callbackRegistered =
                    binder.transact(TX_START_NOW_PLAYING, data, null, IBinder.FLAG_ONEWAY);
            Log.w(TAG, "CarPlay now-playing callback registered=" + callbackRegistered);
        } catch (Throwable t) {
            callbackRegistered = false;
            Log.w(TAG, "CarPlay callback registration failed", t);
        } finally {
            data.recycle();
        }
    }

    private void unregisterCallback() {
        IBinder binder = serviceBinder;
        if (binder == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(callback);
            binder.transact(TX_STOP_NOW_PLAYING, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable ignored) {
        } finally {
            callbackRegistered = false;
            data.recycle();
        }
    }

    private final class NowPlayingCallback extends Binder {
        NowPlayingCallback() {
            attachInterface(new IInterface() {
                @Override
                public IBinder asBinder() {
                    return NowPlayingCallback.this;
                }
            }, CALLBACK_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code != TX_NOW_PLAYING_UPDATE) {
                try {
                    return super.onTransact(code, data, reply, flags);
                } catch (Throwable t) {
                    return true;
                }
            }
            callbackRegistered = true;
            try {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                if (data.readInt() == 0) {
                    scheduleBlankClear("empty now playing update");
                    return true;
                }
                handleUpdate(readNowPlaying(data));
            } catch (Throwable t) {
                Log.w(TAG, "CarPlay now-playing decode failed", t);
            }
            return true;
        }
    }

    private static RawNowPlaying readNowPlaying(Parcel p) {
        RawNowPlaying raw = new RawNowPlaying();
        raw.title = p.readString();
        raw.artist = p.readString();
        raw.durationMs = p.readLong();
        raw.artworkPath = p.readString();
        raw.playbackStatus = p.readInt();
        raw.elapsedMs = p.readLong();
        p.readInt(); // shuffle
        p.readInt(); // repeat
        int artworkSize = p.readInt();
        if (artworkSize > 0) {
            raw.artworkData = new byte[artworkSize];
            p.readByteArray(raw.artworkData);
        }
        return raw;
    }

    private void handleUpdate(RawNowPlaying raw) {
        boolean playing = raw.playbackStatus == PLAYBACK_PLAYING;
        boolean anyContent = !isBlank(raw.title) || !isBlank(raw.artist)
                || !isBlank(raw.artworkPath) || (raw.artworkData != null && raw.artworkData.length > 0)
                || playing;
        if (!anyContent) {
            scheduleBlankClear("blank now playing update");
            return;
        }

        MediaTrack track = new MediaTrack(MediaTrack.SRC_CARPLAY);
        track.packageName = PACKAGE;
        track.appLabel = "CARPLAY";
        track.title = nz(raw.title);
        track.artist = nz(raw.artist);
        track.durationMs = Math.max(0, raw.durationMs);
        track.positionMs = Math.max(0, raw.elapsedMs);
        track.positionAtMs = SystemClock.elapsedRealtime();
        track.playing = playing;
        track.artKey = "carplay|" + track.title + "|" + track.artist + "|"
                + (raw.artworkData == null ? 0 : raw.artworkData.length) + "|" + nz(raw.artworkPath);
        if (track.artKey.equals(lastArtKey) && lastArt != null && !lastArt.isRecycled()) {
            track.art = lastArt;
        } else {
            Bitmap art = decodeBytes(raw.artworkData);
            if (art == null) art = decodePath(raw.artworkPath);
            lastArtKey = track.artKey;
            lastArt = art;
            track.art = art;
        }
        lastValidAtMs = SystemClock.elapsedRealtime();
        publish(track, null);
    }

    private void scheduleBlankClear(String reason) {
        long valid = lastValidAtMs;
        if (valid > 0 && SystemClock.elapsedRealtime() - valid < BLANK_GRACE_MS) return;
        publish(null, reason);
    }

    private void publish(MediaTrack track, String clearReason) {
        if (track == null) {
            lastValidAtMs = 0;
            lastArtKey = "";
            lastArt = null;
        }
        String signature = track == null
                ? "cleared:" + nz(clearReason)
                : (track.title + "|" + track.artist + "|" + track.playing + "|"
                        + (track.art == null ? "noart" : track.art.getWidth() + "x" + track.art.getHeight()));
        if (!signature.equals(lastLogged)) {
            lastLogged = signature;
            Log.w(TAG, "CarPlay " + signature);
        }
        Listener l = listener;
        if (l != null) l.onCarPlayTrack(track);
    }

    private Bitmap decodeBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            Log.w(TAG, "CarPlay artwork decode failed", t);
            return null;
        }
    }

    private Bitmap decodePath(String path) {
        if (appContext == null || path == null || path.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(path);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty()) return BitmapFactory.decodeFile(path);
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return null;
            if ("file".equalsIgnoreCase(scheme)) {
                String p = uri.getPath();
                return p == null ? null : BitmapFactory.decodeFile(p);
            }
            try (InputStream is = appContext.getContentResolver().openInputStream(uri)) {
                return is == null ? null : BitmapFactory.decodeStream(is);
            }
        } catch (Throwable t) {
            Log.w(TAG, "CarPlay artwork path decode failed " + path, t);
            return null;
        }
    }

    boolean setPlaying(boolean play) {
        return sendHid(play ? HID_PLAY : HID_PAUSE, play ? "play" : "pause");
    }

    boolean next() {
        return sendHid(HID_NEXT, "next");
    }

    boolean previous() {
        return sendHid(HID_PREVIOUS, "previous");
    }

    private boolean sendHid(final int keyCode, final String label) {
        if (!sendHidEvent(keyCode, HID_DOWN, label)) return false;
        if (worker != null) {
            worker.postDelayed(() -> sendHidEvent(keyCode, HID_UP, label + " release"), HID_PRESS_MS);
        }
        return true;
    }

    private boolean sendHidEvent(int keyCode, int action, String label) {
        IBinder binder = serviceBinder;
        if (binder == null) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeInt(keyCode);
            data.writeInt(action);
            boolean sent = binder.transact(TX_SEND_HID_OVER_IAP, data, null, IBinder.FLAG_ONEWAY);
            Log.w(TAG, "CarPlay HID " + label + " sent=" + sent);
            return sent;
        } catch (Throwable t) {
            Log.w(TAG, "CarPlay HID failed " + label, t);
            return false;
        } finally {
            data.recycle();
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static final class RawNowPlaying {
        String title;
        String artist;
        long durationMs;
        String artworkPath;
        int playbackStatus;
        long elapsedMs;
        byte[] artworkData;
    }
}
