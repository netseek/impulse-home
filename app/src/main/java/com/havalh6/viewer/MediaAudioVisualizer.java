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
 * <p>Uses the FFT capture (not the time-domain waveform). Waveform peaks on
 * this unit sit around 0.5–0.8 even on calm tracks, so a peak×gain map
 * saturates every bar; slicing the waveform into columns also makes every
 * bar read the same. Magnitude bins give a real spectrum and leave headroom.
 *
 * <p>Measure on the car with music playing:
 * <pre>adb logcat -s H6Viz</pre>
 * Look for {@code live} vs {@code silent}/{@code create-failed}/{@code no-permission}.
 */
final class MediaAudioVisualizer {
    static final String TAG = "H6Viz";
    static final int BARS = 24;

    private static final long SILENT_GRACE_MS = 1500;
    private static final float SILENT_MAG = 0.02f;
    private static final long STATUS_LOG_MS = 2000;
    /**
     * Soft scale on hypot(re,im)/128. Measured calm peaks were ~0.5–0.8 on the
     * waveform path; FFT magnitudes are smaller per bin, and the sqrt below
     * keeps loud passages from pinning the ceiling.
     */
    private static final float MAG_GAIN = 1.15f;
    /** Exponent &lt; 1 expands quiet detail without lifting the floor as much. */
    private static final float MAG_GAMMA = 0.55f;
    /** Cap after gamma so a spike cannot fill the whole travel. */
    private static final float LEVEL_CAP = 0.82f;

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
    private float lastMean;

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
                    // FFT path only — waveform saturated the rail on this MMI.
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    onFft(fft);
                }
            }, rate, false, true);
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
            status("started session=0 fft size=" + size + " rateHz=" + (rate / 1000));
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

    /**
     * Android FFT layout: byte 0 = DC, byte 1 = nyquist, then interleaved
     * real/imag pairs for bins 1..n/2-1. Magnitudes are hypot(re,im).
     */
    private void onFft(byte[] fft) {
        if (fft == null || fft.length < 4) return;
        int n = BARS;
        // Usable complex bins (skip DC at [0]/nyquist at [1]).
        int binCount = (fft.length / 2) - 1;
        if (binCount < n) return;

        float sumMag = 0f;
        float peakMag = 0f;
        float sumLevel = 0f;
        for (int i = 0; i < n; i++) {
            // Log-ish spacing: more resolution in the low/mid band that music
            // actually moves, less weight on empty top octaves.
            float t0 = (float) i / n;
            float t1 = (float) (i + 1) / n;
            int b0 = 1 + (int) (Math.pow(t0, 1.55) * (binCount - 1));
            int b1 = 1 + (int) (Math.pow(t1, 1.55) * (binCount - 1));
            if (b1 <= b0) b1 = b0 + 1;
            if (b1 > binCount) b1 = binCount;

            float peak = 0f;
            float acc = 0f;
            int count = 0;
            for (int b = b0; b < b1; b++) {
                int idx = b * 2;
                if (idx + 1 >= fft.length) break;
                float re = fft[idx];
                float im = fft[idx + 1];
                float mag = (float) Math.hypot(re, im) / 128f;
                acc += mag;
                if (mag > peak) peak = mag;
                count++;
            }
            float avg = count > 0 ? acc / count : 0f;
            // Blend avg+peak so a kick lifts the bar without a single bin
            // slamming every column to the ceiling.
            float raw = avg * 0.72f + peak * 0.28f;
            float level = shape(raw);
            scratch[i] = level;
            sumLevel += level;
            sumMag += avg;
            if (peak > peakMag) peakMag = peak;
        }

        float meanMag = sumMag / n;
        float meanLevel = sumLevel / n;
        lastRms = meanMag;
        lastPeak = peakMag;
        lastMean = meanLevel;
        captureFrames++;

        long now = SystemClock.uptimeMillis();
        boolean audible = peakMag >= SILENT_MAG;
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
                        "live frames=%d mag=%.3f peak=%.3f meanBar=%.3f",
                        captureFrames, meanMag, peakMag, meanLevel));
            } else {
                status(String.format(
                        java.util.Locale.US,
                        "silent frames=%d mag=%.3f peak=%.3f (session 0 empty?)",
                        captureFrames, meanMag, peakMag));
            }
        }

        Runnable cb = onUpdate;
        if (cb != null) main.post(cb);
    }

    private static float shape(float mag) {
        if (mag <= 0f) return 0f;
        float v = mag * MAG_GAIN;
        if (v > 1f) v = 1f;
        v = (float) Math.pow(v, MAG_GAMMA);
        if (v > LEVEL_CAP) v = LEVEL_CAP;
        return v;
    }

    private void status(String msg) {
        lastStatus = msg;
        lastStatusAt = SystemClock.uptimeMillis();
        Log.w(TAG, msg);
    }
}
