package com.havalh6.viewer;

import android.graphics.Bitmap;
import android.os.SystemClock;

/**
 * One now-playing snapshot, whatever produced it. The viewer reads three
 * sources that never overlap cleanly (MediaSession, the native MediaCenter
 * binder, the CarPlay binder), so {@link MediaNowPlaying} keeps the latest
 * track per source and picks a winner instead of letting them fight.
 */
final class MediaTrack {
    /** Apps that publish a MediaSession (Spotify, YouTube, browsers…). */
    static final int SRC_SESSION = 1;
    /** com.beantechs.mediacenter — Android Auto audio and USB media. */
    static final int SRC_MEDIA_CENTER = 2;
    /** com.ts.carplay — iPhone projection. */
    static final int SRC_CARPLAY = 3;

    final int source;
    String packageName = "";
    String appLabel = "";
    String title = "";
    String artist = "";
    String album = "";
    long durationMs;
    long positionMs;
    /** elapsedRealtime when positionMs was sampled, so playback can be extrapolated. */
    long positionAtMs = SystemClock.elapsedRealtime();
    boolean playing;
    /** Decoded artwork at the best resolution the source could give us. */
    Bitmap art;
    /** Stable identity for {@link #art} so the JPEG is only re-encoded on change. */
    String artKey = "";
    /** http(s) artwork to fetch when {@link #art} is null (YouTube thumbnails). */
    String remoteArtUrl = "";
    /**
     * Last resort for players that publish no artwork of any kind: look the
     * track up by name. Only YouTube needs this today.
     */
    String artSearchQuery = "";

    MediaTrack(int source) {
        this.source = source;
    }

    boolean hasTrack() {
        return !title.isEmpty() || !artist.isEmpty() || art != null || !remoteArtUrl.isEmpty();
    }

    boolean hasArtSource() {
        return art != null || !remoteArtUrl.isEmpty() || !artSearchQuery.isEmpty();
    }

    /** Position advanced to now, so the progress bar keeps moving between updates. */
    long currentPositionMs() {
        if (!playing) return positionMs;
        long pos = positionMs + Math.max(0, SystemClock.elapsedRealtime() - positionAtMs);
        if (durationMs > 0) pos = Math.min(pos, durationMs);
        return pos;
    }
}
