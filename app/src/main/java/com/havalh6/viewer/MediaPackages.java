package com.havalh6.viewer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Music apps that open in the right freeform slot instead of the left popup. */
final class MediaPackages {
    private static final Set<String> PACKAGES = new HashSet<>(Arrays.asList(
            "com.spotify.music",
            "com.spotify.tv.android",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube.tvmusic",
            "deezer.android.app",
            "deezer.android.tv",
            "com.aspiro.tidal",
            "com.amazon.mp3",
            "com.apple.android.music"
    ));

    private MediaPackages() {}

    static boolean isMediaApp(String packageName, String label) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (PACKAGES.contains(packageName)) return true;
        String pkg = packageName.toLowerCase(Locale.US);
        String lbl = label == null ? "" : label.toLowerCase(Locale.US);
        return pkg.contains("spotify")
                || pkg.contains("youtube.music")
                || pkg.contains("youtubemusic")
                || pkg.contains("deezer")
                || pkg.contains("tidal")
                || pkg.contains("amazon.mp3")
                || pkg.contains("apple.android.music")
                || lbl.contains("youtube music")
                || lbl.contains("spotify")
                || lbl.contains("deezer");
    }
}
