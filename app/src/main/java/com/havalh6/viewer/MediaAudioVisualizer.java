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
 * <p>FFT is preferred (a real spectrum). Some captures on this MMI come back
 * flat even while music is audible; waveform RMS then fills the row with a
 * bass-heavy envelope so it does not freeze and does not clip every bar to
 * the ceiling the way a raw peak map did.
 *
 * <p>Measure on the car with music playing:
 * <pre>adb logcat -s H6Viz</pre>
 */
final class MediaAudioVisualizer {
    static final String TAG = "H6Viz";
    static final int BARS = 24;

    private static final long SILENT_GRACE_MS = 1500;
    private static final float SILENT_MAG = 0.02f;
    private static final long STATUS_LOG_MS = 2000;
    /** FFT: hypot(re,im)/128, then gamma. Calm peaks were saturating the old waveform path. */
    private static final float FFT_GAIN = 1.6f;
    private static final float FFT_GAMMA = 0.62f;
    private static final float WAVE_GAIN = 1.35f;
    private static final float WAVE_GAMMA = 0.7f;
    private static final float LEVEL_CAP = 0.78f;
    /** FFT must move this much or we treat it as a dead capture. */
    private static final float FFT_USEFUL_MEAN = 0.06f;
    private static final float FFT_USEFUL_SPREAD = 0.04f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] levels = new float[BARS];
    private final float[] fftBars = new float[BARS];
    private final float[] waveBars = new float[BARS];
    private final Object lock = new Object();

    private Visualizer visualizer;
    private Runnable onUpdate;
    private boolean wanted;
    private boolean enabled;
    private long lastNonSilentAt;
    private long lastStatusAt;
    private String lastStatus = "";
    private int captureFrames;
    private float lastFftMean;
    private float lastFftPeak;
    private float lastFftSpread;
    private float lastWaveRms;
    private boolean lastUsedFft;

    void setOnUpdate(Runnable onUpdate) {
        this.onUpdate = onUpdate;
    }

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
                    onFft(fft);
                }
            }, rate, true, true);
            if (ok != Visualizer.SUCCESS) {
                viz.release();
                status("listener-failed code=" + ok);
                return;
            }
            viz.setEnabled(true);
            visualizer = viz;
            enabled = true;
            captureFrames = 0;
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
        for (int i = 0; i < n; i++) {
            int start = i * chunk;
            int end = Math.min(waveform.length, start + chunk);
            float acc = 0f;
            int count = 0;
            for (int j = start; j < end; j++) {
                float v = Math.abs((waveform[j] & 0xff) - 128) / 128f;
                acc += v * v;
                count++;
            }
            float rms = count > 0 ? (float) Math.sqrt(acc / count) : 0f;
            sumSq += acc;
            // Bass-heavy envelope so a single RMS does not paint a flat wall.
            float tilt = 0.28f + 0.72f * (1f - (float) i / Math.max(1, n - 1));
            waveBars[i] = cap(shape(rms * WAVE_GAIN, WAVE_GAMMA) * tilt);
        }
        lastWaveRms = (float) Math.sqrt(sumSq / Math.max(1, waveform.length));
        if (lastWaveRms >= SILENT_MAG) lastNonSilentAt = SystemClock.uptimeMillis();
        publish();
    }

    /**
     * Android FFT layout: byte 0 = DC, byte 1 = nyquist, then interleaved
     * real/imag pairs for bins 1..n/2-1.
     */
    private void onFft(byte[] fft) {
        if (fft == null || fft.length < 4) return;
        int n = BARS;
        int binCount = (fft.length / 2) - 1;
        if (binCount < n) return;

        float sumMag = 0f;
        float peakMag = 0f;
        float sumLevel = 0f;
        float minLevel = 1f;
        float maxLevel = 0f;
        for (int i = 0; i < n; i++) {
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
            float raw = avg * 0.72f + peak * 0.28f;
            float level = cap(shape(raw * FFT_GAIN, FFT_GAMMA));
            fftBars[i] = level;
            sumLevel += level;
            sumMag += avg;
            if (peak > peakMag) peakMag = peak;
            if (level < minLevel) minLevel = level;
            if (level > maxLevel) maxLevel = level;
        }
        lastFftMean = sumLevel / n;
        lastFftPeak = peakMag;
        lastFftSpread = maxLevel - minLevel;
        if (peakMag >= SILENT_MAG) lastNonSilentAt = SystemClock.uptimeMillis();
        publish();
    }

    private void publish() {
        captureFrames++;
        boolean useFft = lastFftMean >= FFT_USEFUL_MEAN && lastFftSpread >= FFT_USEFUL_SPREAD;
        lastUsedFft = useFft;
        synchronized (lock) {
            System.arraycopy(useFft ? fftBars : waveBars, 0, levels, 0, BARS);
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastStatusAt >= STATUS_LOG_MS) {
            if (hasLiveAudio()) {
                status(String.format(
                        java.util.Locale.US,
                        "live src=%s frames=%d fftMean=%.3f spread=%.3f waveRms=%.3f",
                        useFft ? "fft" : "wave",
                        captureFrames, lastFftMean, lastFftSpread, lastWaveRms));
            } else {
                status(String.format(
                        java.util.Locale.US,
                        "silent frames=%d fftMean=%.3f waveRms=%.3f (session 0 empty?)",
                        captureFrames, lastFftMean, lastWaveRms));
            }
        }

        Runnable cb = onUpdate;
        if (cb != null) main.post(cb);
    }

    private static float shape(float mag, float gamma) {
        if (mag <= 0f) return 0f;
        if (mag > 1f) mag = 1f;
        return (float) Math.pow(mag, gamma);
    }

    private static float cap(float v) {
        if (v < 0f) return 0f;
        if (v > LEVEL_CAP) return LEVEL_CAP;
        return v;
    }

    private void status(String msg) {
        lastStatus = msg;
        lastStatusAt = SystemClock.uptimeMillis();
        Log.w(TAG, msg);
    }
}
