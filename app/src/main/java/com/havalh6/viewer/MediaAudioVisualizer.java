package com.havalh6.viewer;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Prototype: drive the MEDIA rail bars from {@link Visualizer} on audio
 * session 0 (the mixed output).
 *
 * <p>This is the only path that can follow real playback on Android 9 — there
 * is no {@code AudioPlaybackCapture}. Session 0 often returns silence on OEM
 * builds, which is why every status change is logged under {@code H6Viz} and
 * why callers must keep a synthetic fallback.
 *
 * <p>Measure on the car with music playing:
 * <pre>adb logcat -s H6Viz</pre>
 * Look for {@code live} vs {@code silent}/{@code create-failed}/{@code no-permission}.
 */
final class MediaAudioVisualizer {
    static final String TAG = "H6Viz";
    static final int BARS = 24;

    private static final long SILENT_GRACE_MS = 1500;
    private static final float SILENT_PEAK = 0.025f;
    private static final long STATUS_LOG_MS = 2000;
    /** Mild gain so typical music peaks read mid-travel on the rail bars. */
    private static final float GAIN = 2.4f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] levels = new float[BARS];
    private final float[] scratch = new float[BARS];
    private final Object lock = new Object();

    private Visualizer visualizer;
    private Runnable onUpdate;
    private boolean wanted;
    private boolean enabled;
    private long firstNonSilentAt;
    private long lastNonSilentAt;
    private long lastStatusAt;
    private String lastStatus = "";
    private int captureFrames;
    private float lastRms;
    private float lastPeak;

    void setOnUpdate(Runnable onUpdate) {
        this.onUpdate = onUpdate;
    }

    /** True when a recent capture had audible energy (not all-zero session 0). */
    boolean hasLiveAudio() {
        if (!enabled) return false;
        long now = SystemClock.uptimeMillis();
        return lastNonSilentAt > 0 && (now - lastNonSilentAt) < SILENT_GRACE_MS;
    }

    /**
     * Copy the latest bar levels (0..1). Returns false when the probe is off
     * or the mix has been silent long enough that the UI should synthesise.
     */
    boolean copyLiveLevels(float[] dest) {
        if (dest == null || dest.length < BARS || !hasLiveAudio()) return false;
        synchronized (lock) {
            System.arraycopy(levels, 0, dest, 0, BARS);
        }
        return true;
    }

    void setWanted(boolean wanted) {
        if (this.wanted == wanted) return;
        this.wanted = wanted;
        if (wanted) start();
        else stop();
    }

    void start() {
        wanted = true;
        if (enabled) return;
        try {
            Visualizer viz = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int size = range[1];
            // Prefer a mid size when the max is huge — 1024 is plenty for 24 bars.
            if (size > 1024 && range[0] <= 1024) size = 1024;
            viz.setCaptureSize(size);
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 15000);
            int ok = viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform,
                        int samplingRate) {
                    onWaveform(waveform);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    // Waveform alone is enough for the prototype; FFT kept off
                    // so we do not pay for a second callback path until measured.
                }
            }, rate, true, false);
            if (ok != Visualizer.SUCCESS) {
                viz.release();
                status("listener-failed code=" + ok);
                return;
            }
            viz.setEnabled(true);
            visualizer = viz;
            enabled = true;
            captureFrames = 0;
            firstNonSilentAt = 0;
            lastNonSilentAt = 0;
            status("started session=0 size=" + size + " rateHz=" + (rate / 1000));
        } catch (Throwable t) {
            enabled = false;
            visualizer = null;
            status("create-failed " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    void stop() {
        wanted = false;
        releaseViz();
        if (!"stopped".equals(lastStatus)) status("stopped");
    }

    void release() {
        wanted = false;
        onUpdate = null;
        releaseViz();
    }

    private void releaseViz() {
        enabled = false;
        Visualizer viz = visualizer;
        visualizer = null;
        if (viz == null) return;
        try {
            viz.setEnabled(false);
        } catch (Throwable ignored) {}
        try {
            viz.setDataCaptureListener(null, 0, false, false);
        } catch (Throwable ignored) {}
        try {
            viz.release();
        } catch (Throwable ignored) {}
    }

    private void onWaveform(byte[] waveform) {
        if (waveform == null || waveform.length < BARS) return;
        int n = BARS;
        int chunk = Math.max(1, waveform.length / n);
        float sumSq = 0f;
        float peakAll = 0f;
        for (int i = 0; i < n; i++) {
            int start = i * chunk;
            int end = Math.min(waveform.length, start + chunk);
            float peak = 0f;
            for (int j = start; j < end; j++) {
                float v = Math.abs((waveform[j] & 0xff) - 128) / 128f;
                if (v > peak) peak = v;
                sumSq += v * v;
            }
            float scaled = peak * GAIN;
            if (scaled > 1f) scaled = 1f;
            scratch[i] = scaled;
            if (peak > peakAll) peakAll = peak;
        }
        float rms = (float) Math.sqrt(sumSq / Math.max(1, waveform.length));
        lastRms = rms;
        lastPeak = peakAll;
        captureFrames++;

        long now = SystemClock.uptimeMillis();
        boolean audible = peakAll >= SILENT_PEAK;
        if (audible) {
            if (firstNonSilentAt == 0) firstNonSilentAt = now;
            lastNonSilentAt = now;
        }

        synchronized (lock) {
            System.arraycopy(scratch, 0, levels, 0, BARS);
        }

        if (now - lastStatusAt >= STATUS_LOG_MS) {
            if (audible || lastNonSilentAt > 0) {
                status(String.format(
                        java.util.Locale.US,
                        "live frames=%d rms=%.3f peak=%.3f",
                        captureFrames, rms, peakAll));
            } else {
                status(String.format(
                        java.util.Locale.US,
                        "silent frames=%d rms=%.3f peak=%.3f (session 0 empty?)",
                        captureFrames, rms, peakAll));
            }
        }

        Runnable cb = onUpdate;
        if (cb != null) main.post(cb);
    }

    private void status(String msg) {
        lastStatus = msg;
        lastStatusAt = SystemClock.uptimeMillis();
        Log.i(TAG, msg);
    }
}
