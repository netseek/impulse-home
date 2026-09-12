package com.havalh6.viewer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.io.InputStream;

/**
 * Now-playing from the head unit's native MediaCenter (com.beantechs.mediacenter).
 *
 * <p>Android Auto audio never registers a {@code MediaSession}: the projection
 * service hands metadata straight to MediaCenter, which is why the notification
 * listener sees nothing while Android Auto plays. MediaCenter also carries USB
 * media, and it hands out the artwork as a full-size bitmap instead of the
 * 320dp-capped copy {@code MediaMetadata} allows across a binder.
 *
 * <p>Transaction ids and the {@code MediaInfo} parcel layout come from Haval
 * Impulse (`BottomBarService`), which drives the same binder.
 */
final class MediaCenterSource {
    interface Listener {
        /** Called on the poll thread; {@code null} means MediaCenter has nothing to show. */
        void onMediaCenterTrack(MediaTrack track);
    }

    private static final String TAG = "H6Media";

    private static final String PACKAGE = "com.beantechs.mediacenter";
    private static final String SERVICE_CLASS =
            "com.beantechs.mediacenter.mediacentermodel.MediaCenterService";
    private static final String SERVICE_DESCRIPTOR =
            "com.beantechs.mediacenter.mediacentermodel.IMediaCenterService";
    private static final String PLAY_DESCRIPTOR =
            "com.beantechs.mediacenter.mediacentermodel.IPlayService";

    private static final int BINDER_PLAY_SERVICE = 2;
    private static final int TX_QUERY_BINDER = 1;
    private static final int TX_GET_PLAY_STATE_BY_SOURCE = 19;
    private static final int TX_GET_MEDIA_INFO_BY_SOURCE = 22;
    private static final int TX_GET_CURRENT_SOURCE = 25;
    private static final int TX_GET_CURRENT_AUDIO_SOURCE = 26;
    private static final int TX_PAUSE_BY_SOURCE = 27;
    private static final int TX_RESUME_BY_SOURCE = 28;

    static final int SOURCE_USB = 2;
    static final int SOURCE_ANDROID_AUTO = 402;

    private static final int STATE_PLAYING = 3;

    private static final long POLL_MS = 1500;

    private Context appContext;
    private Listener listener;
    private Handler poll;
    private ServiceConnection connection;
    private volatile IBinder serviceBinder;
    private volatile IBinder playBinder;
    private volatile int activeSource;
    private boolean started;

    /** Art is re-decoded only when the identity changes; polling runs every 1.5s. */
    private String cachedArtKey = "";
    private Bitmap cachedArt;
    private String lastLogged = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                pollOnce();
            } catch (Throwable t) {
                Log.w(TAG, "MediaCenter poll failed", t);
            }
            if (poll != null) poll.postDelayed(this, POLL_MS);
        }
    };

    void start(Context context, Listener listener) {
        if (started) return;
        started = true;
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        HandlerThread t = new HandlerThread("H6MediaCenter");
        t.start();
        poll = new Handler(t.getLooper());
        bind();
        poll.post(tick);
    }

    void stop() {
        started = false;
        if (poll != null) poll.removeCallbacks(tick);
        unbind();
    }

    private void bind() {
        if (connection != null || appContext == null) return;
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceBinder = service;
                playBinder = null;
                Log.w(TAG, "MediaCenter service connected");
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                serviceBinder = null;
                playBinder = null;
                Log.w(TAG, "MediaCenter service disconnected");
            }
        };
        Intent intent = new Intent().setComponent(new ComponentName(PACKAGE, SERVICE_CLASS));
        boolean bound = false;
        try {
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "MediaCenter bind failed", t);
        }
        if (!bound) {
            try {
                appContext.unbindService(connection);
            } catch (Throwable ignored) {}
            connection = null;
            Log.w(TAG, "MediaCenter bind returned false");
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
        playBinder = null;
    }

    private void pollOnce() {
        IBinder play = resolvePlayBinder();
        if (play == null) {
            publish(null);
            return;
        }
        Integer current = readInt(play, TX_GET_CURRENT_SOURCE);
        Integer currentAudio = readInt(play, TX_GET_CURRENT_AUDIO_SOURCE);
        int source = pickSource(current, currentAudio);
        activeSource = source;
        if (source == 0) {
            publish(null);
            return;
        }
        MediaInfo info = readMediaInfo(play, source);
        PlayState state = readPlayState(play, source);
        if (info == null && state == null) {
            publish(null);
            return;
        }
        publish(toTrack(source, info, state));
    }

    /** Android Auto wins over USB; anything else (radio, BT, tuner) is left alone. */
    private static int pickSource(Integer current, Integer currentAudio) {
        if (isSource(current, SOURCE_ANDROID_AUTO) || isSource(currentAudio, SOURCE_ANDROID_AUTO)) {
            return SOURCE_ANDROID_AUTO;
        }
        if (isSource(current, SOURCE_USB) || isSource(currentAudio, SOURCE_USB)) {
            return SOURCE_USB;
        }
        return 0;
    }

    private static boolean isSource(Integer value, int want) {
        return value != null && value == want;
    }

    private MediaTrack toTrack(int source, MediaInfo info, PlayState state) {
        MediaTrack track = new MediaTrack(MediaTrack.SRC_MEDIA_CENTER);
        // AA audio is MediaCenter source 402, but launching MediaCenter's MAIN
        // activity skips tracks. Name the projection app so the card raises
        // AapActivity instead (see launchAppForPackage).
        track.packageName = source == SOURCE_ANDROID_AUTO
                ? ProjectionPresence.androidAutoAppPackage() : PACKAGE;
        track.appLabel = source == SOURCE_ANDROID_AUTO ? "ANDROID AUTO" : "USB";
        if (info != null) {
            track.title = nz(info.title);
            track.artist = nz(info.artist);
            track.album = nz(info.album);
        }
        long duration = 0;
        if (state != null && state.durationMs > 0) duration = state.durationMs;
        else if (info != null && info.durationMs > 0) duration = info.durationMs;
        track.durationMs = duration;
        if (state != null) {
            track.playing = state.state == STATE_PLAYING;
            track.positionMs = Math.max(0, state.elapsedMs);
        }
        track.remoteArtUrl = info != null && isHttp(info.imageUrl) ? info.imageUrl : "";
        String artKey = source + "|" + track.title + "|" + track.artist + "|" + track.album
                + "|" + (info == null ? "" : nz(info.imageUrl))
                + "|" + (info == null || info.imageBitmap == null
                        ? "0"
                        : info.imageBitmap.getWidth() + "x" + info.imageBitmap.getHeight());
        track.artKey = artKey;
        track.art = resolveArt(artKey, info);
        if (!track.hasTrack()) return null;
        return track;
    }

    private Bitmap resolveArt(String artKey, MediaInfo info) {
        if (artKey.equals(cachedArtKey) && cachedArt != null && !cachedArt.isRecycled()) {
            return cachedArt;
        }
        // The parceled bitmap is whatever MediaCenter cached (often 256px); a
        // file/content URI decodes at full size, so take the bigger of the two.
        Bitmap art = info == null ? null : info.imageBitmap;
        if (art != null && art.isRecycled()) art = null;
        Bitmap fromUri = info == null ? null : decodeUri(info.imageUrl);
        if (fromUri != null && !fromUri.isRecycled()
                && (art == null
                        || fromUri.getWidth() * fromUri.getHeight() > art.getWidth() * art.getHeight())) {
            art = fromUri;
        }
        cachedArtKey = artKey;
        cachedArt = art;
        return art;
    }

    private Bitmap decodeUri(String uri) {
        if (appContext == null || uri == null || uri.isEmpty()) return null;
        try {
            Uri parsed = Uri.parse(uri);
            String scheme = parsed.getScheme();
            if (scheme == null || scheme.isEmpty()) return BitmapFactory.decodeFile(uri);
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return null;
            if ("file".equalsIgnoreCase(scheme)) {
                String path = parsed.getPath();
                return path == null ? null : BitmapFactory.decodeFile(path);
            }
            try (InputStream is = appContext.getContentResolver().openInputStream(parsed)) {
                return is == null ? null : BitmapFactory.decodeStream(is);
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaCenter art decode failed " + uri, t);
            return null;
        }
    }

    private void publish(MediaTrack track) {
        String signature = track == null
                ? "none"
                : (track.appLabel + "|" + track.title + "|" + track.artist + "|" + track.playing
                        + "|" + (track.art == null
                                ? "noart"
                                : track.art.getWidth() + "x" + track.art.getHeight())
                        + "|" + track.remoteArtUrl);
        if (!signature.equals(lastLogged)) {
            lastLogged = signature;
            Log.w(TAG, "MediaCenter " + signature);
        }
        Listener l = listener;
        if (l != null) l.onMediaCenterTrack(track);
    }

    /** @return true when the command reached MediaCenter. */
    boolean setPlaying(boolean play) {
        IBinder binder = resolvePlayBinder();
        int source = activeSource;
        if (binder == null || source == 0) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(PLAY_DESCRIPTOR);
            data.writeInt(source);
            boolean sent = binder.transact(
                    play ? TX_RESUME_BY_SOURCE : TX_PAUSE_BY_SOURCE, data, null, IBinder.FLAG_ONEWAY);
            Log.w(TAG, "MediaCenter " + (play ? "resume" : "pause") + " source=" + source + " sent=" + sent);
            return sent;
        } catch (Throwable t) {
            playBinder = null;
            Log.w(TAG, "MediaCenter transport failed", t);
            return false;
        } finally {
            data.recycle();
        }
    }

    private IBinder resolvePlayBinder() {
        IBinder cached = playBinder;
        if (cached != null && cached.isBinderAlive()) return cached;
        playBinder = null;

        IBinder service = serviceBinder;
        if (service == null || !service.isBinderAlive()) {
            serviceBinder = null;
            unbind();
            bind();
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeInt(BINDER_PLAY_SERVICE);
            if (!service.transact(TX_QUERY_BINDER, data, reply, 0)) return null;
            reply.readException();
            IBinder resolved = reply.readStrongBinder();
            if (resolved != null) {
                playBinder = resolved;
                Log.w(TAG, "MediaCenter play binder resolved");
            }
            return resolved;
        } catch (Throwable t) {
            Log.w(TAG, "MediaCenter play binder query failed", t);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private Integer readInt(IBinder binder, int transaction) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PLAY_DESCRIPTOR);
            if (!binder.transact(transaction, data, reply, 0)) return null;
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            playBinder = null;
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private PlayState readPlayState(IBinder binder, int source) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PLAY_DESCRIPTOR);
            data.writeInt(source);
            if (!binder.transact(TX_GET_PLAY_STATE_BY_SOURCE, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            PlayState state = new PlayState();
            state.mediaSource = reply.readInt();
            state.state = reply.readInt();
            state.durationMs = Math.max(0, reply.readLong());
            reply.readLong();  // buffered position
            reply.readFloat(); // speed
            reply.readLong();  // tcp speed
            reply.readString(); // error
            state.elapsedMs = Math.max(0, reply.readLong());
            if (state.mediaSource != 0 && state.mediaSource != source) return null;
            return state;
        } catch (Throwable t) {
            playBinder = null;
            Log.w(TAG, "MediaCenter play state read failed source=" + source, t);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private MediaInfo readMediaInfo(IBinder binder, int source) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PLAY_DESCRIPTOR);
            data.writeInt(source);
            if (!binder.transact(TX_GET_MEDIA_INFO_BY_SOURCE, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            int mediaSource = reply.readInt();
            reply.readByte();
            String serializableClass = reply.readString();
            if (serializableClass != null) reply.createByteArray();
            String parcelableClass = reply.readString();
            if (parcelableClass == null) return null;
            if (mediaSource != 0 && mediaSource != source) return null;
            return MediaInfo.read(reply);
        } catch (Throwable t) {
            playBinder = null;
            Log.w(TAG, "MediaCenter media info read failed source=" + source, t);
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean isHttp(String uri) {
        if (uri == null) return false;
        String u = uri.toLowerCase(java.util.Locale.US);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static final class PlayState {
        int mediaSource;
        int state;
        long durationMs;
        long elapsedMs;
    }

    /** Field order of com.beantechs.mediacenter.core_common.data.MediaInfo. */
    private static final class MediaInfo {
        String album;
        String title;
        String artist;
        String imageUrl;
        long durationMs;
        Bitmap imageBitmap;

        static MediaInfo read(Parcel p) {
            MediaInfo info = new MediaInfo();
            p.readString(); // mediaId
            p.readString(); // channelId
            p.readString(); // path
            p.readString(); // cpId
            p.readString(); // categoryId
            p.readString(); // contentId
            info.album = p.readString();
            p.readString(); // trackId
            info.title = p.readString();
            p.readString(); // subTitle
            info.artist = p.readString();
            info.imageUrl = p.readString();
            p.readString(); // description
            p.readString(); // intro
            info.durationMs = p.readLong();
            p.readInt(); // order
            p.readInt(); // index
            p.readByte(); // playFromHistoryPosition
            try {
                info.imageBitmap = p.readParcelable(Bitmap.class.getClassLoader());
            } catch (Throwable ignored) {
                info.imageBitmap = null;
            }
            return info;
        }
    }
}
