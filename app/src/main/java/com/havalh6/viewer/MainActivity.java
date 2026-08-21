package com.havalh6.viewer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.ConsoleMessage;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.MediaMetadataRetriever;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

import android.app.ActivityOptions;
import android.widget.FrameLayout;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.content.ComponentName;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String TAG = "H6Viewer";
    private static final String PERF_TAG = "H6Perf";
    /**
     * Public telemetry broadcast from havalshisuku
     * ({@code ServiceManager.dispatchTelemetryOnly}). Both actions carry the same
     * {@code key}/{@code value} string extras; which one the installed build
     * sends depends on its vintage, so listen for both.
     * <p>
     * The per-key {@code android.intent.haval.*} broadcasts are NOT usable here —
     * they are {@code setPackage()}-scoped to havalshisuku itself.
     */
    private static final String ACTION_VEHICLE_EVENT_CHANGED = "com.haval.vehicle.EVENT_CHANGED";
    /** Asks havalshisuku to re-broadcast every cached value (change-only stream otherwise). */
    private static final String ACTION_REQUEST_SNAPSHOT = "com.haval.vehicle.REQUEST_SNAPSHOT";
    private static final String HAVALSHISUKU_PACKAGE = "br.com.redesurftank.havalshisuku";
    /** havalshisuku may still be starting when we first ask. */
    private static final long[] SNAPSHOT_RETRY_DELAYS_MS = {4000, 15000};
    private static final String ACTION_CAR_DATA_UPDATE = "br.com.redesurftank.havalshisuku.CAR_DATA_UPDATE";
    /**
     * Freeform task ids come from Haval Impulse. We may call moveTaskToFront on a
     * foreign task (REORDER_TASKS, declared) but cannot discover its id on
     * Android 9 — getRunningTasks filters foreign tasks and
     * registerTaskStackListener is denied without MANAGE_ACTIVITY_STACKS.
     * Impulse has that reach through Shizuku.
     */
    private static final String IMPULSE_PACKAGE = "br.com.redesurftank.havalshisuku";
    private static final String ACTION_RESOLVE_TASK =
            "br.com.redesurftank.havalshisuku.ACTION_RESOLVE_TASK";
    private static final String ACTION_TASK_RESOLVED =
            "br.com.redesurftank.havalshisuku.ACTION_TASK_RESOLVED";
    private static final String ACTION_SET_TASK_BOUNDS =
            "br.com.redesurftank.havalshisuku.ACTION_SET_TASK_BOUNDS";

    /** Names shared with Impulse's API gate. */
    private static final class ImpulseApi {
        static final String EXTRA_CALLER = "caller";
    }

    private android.app.PendingIntent apiCallerToken;
    /** Viewer → Impulse: write an allowlisted vehicle setting (MODES widget). */
    private static final String ACTION_UPDATE_CAR_DATA =
            "br.com.redesurftank.havalshisuku.ACTION_UPDATE_CAR_DATA";
    private static final java.util.Set<String> WRITABLE_CAR_KEYS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "car.drive_setting.drive_mode",
                    "car.ev_setting.power_model_config",
                    "car.drive_setting.steering_wheel_assist_mode",
                    "car.ev_setting.energy_recovery_level",
                    "car.drive_setting.esp_enable"
            ));
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String VIEWER_URL =
            "https://" + ASSET_HOST + ASSET_PREFIX + "www/index.html?android";

    private WebView webView;
    private ValueCallback<Uri[]> pendingFiles;
    private final ConcurrentHashMap<String, String> telemetryCache = new ConcurrentHashMap<>();
    private View launchAnchor;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pinBoundsRunnable;

    private final BroadcastReceiver taskResolvedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_TASK_RESOLVED.equals(intent.getAction())) return;
            String pkg = intent.getStringExtra("package");
            int taskId = intent.getIntExtra("taskId", -1);
            if (pkg == null || pkg.isEmpty() || taskId < 0) return;
            // Only adopt an id for a slot we actually have open.
            if (pkg.equals(activePopupPackage)) activePopupTaskId = taskId;
            else if (pkg.equals(activeMediaPackage)) activeMediaTaskId = taskId;
            else return;
            Log.w(TAG, "Impulse resolved " + pkg + " -> task " + taskId);
        }
    };

    private final BroadcastReceiver telemetryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (!ACTION_VEHICLE_EVENT_CHANGED.equals(action)
                    && !ACTION_CAR_DATA_UPDATE.equals(action)) return;
            String key = intent.getStringExtra("key");
            String value = intent.getStringExtra("value");

            // Raw capture point for calibrating value encodings (door bitmask,
            // speed unit). Logged before the null guard so a publisher using
            // different extra names is visible rather than silently dropped.
            // Log.w, not Log.i: this ROM drops info-level output for this app
            // (verified — zero I lines in logcat, while W and E come through).
            if (isDebuggableBuild()) {
                Log.w(TAG, "CarSignal " + action + " key=" + key + " value=" + value);
            }
            if (key == null || value == null) return;

            telemetryCache.put(key, value);
            if (webView != null) {
                webView.post(() -> webView.evaluateJavascript(
                        String.format("if(window.onCarDataUpdate){window.onCarDataUpdate('%s','%s');}",
                                key.replace("'", "\\'"), value.replace("'", "\\'")),
                        null
                ));
            }
        }
    };

    /**
     * Ask havalshisuku to re-broadcast every cached car value.
     *
     * The event stream is change-only, and this launcher starts long after the
     * car service, so without a snapshot a parked car with its lights on is
     * indistinguishable from one with them off. Retried a couple of times
     * because havalshisuku may still be coming up when we first ask.
     */
    private void requestTelemetrySnapshot(String reason) {
        try {
            Intent request = new Intent(ACTION_REQUEST_SNAPSHOT);
            request.setPackage(HAVALSHISUKU_PACKAGE);
            request.putExtra("requester", getPackageName());
            sendBroadcast(request);
            Log.w(TAG, "Telemetry snapshot requested (" + reason + ")");
        } catch (Exception e) {
            Log.w(TAG, "Telemetry snapshot request failed", e);
        }
    }

    private void scheduleTelemetrySnapshotRequests() {
        requestTelemetrySnapshot("startup");
        for (long delay : SNAPSHOT_RETRY_DELAYS_MS) {
            mainHandler.postDelayed(() -> requestTelemetrySnapshot("retry"), delay);
        }
    }

    private boolean isDebuggableBuild() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public class TelemetryBridge {
        @JavascriptInterface
        public String getCarData(String key) {
            String val = telemetryCache.get(key);
            return val != null ? val : "";
        }

        @JavascriptInterface
        public void setCarData(String key, String value) {
            if (key == null || key.isEmpty() || !WRITABLE_CAR_KEYS.contains(key)) {
                Log.w(TAG, "Blocked setCarData for " + key);
                return;
            }
            final String safeValue = value == null ? "" : value;
            if (safeValue.length() > 64) {
                Log.w(TAG, "Blocked oversized setCarData for " + key);
                return;
            }
            mainHandler.post(() -> {
                try {
                    Intent request = new Intent(ACTION_UPDATE_CAR_DATA);
                    request.setPackage(IMPULSE_PACKAGE);
                    request.putExtra("key", key);
                    request.putExtra("value", safeValue);
                    sendBroadcast(request);
                } catch (Exception e) {
                    Log.w(TAG, "setCarData broadcast failed for " + key
                            + " (" + e.getClass().getSimpleName() + ")");
                }
            });
        }

        /**
         * Frame metrics from the viewer's window.__perf(). Renderer performance
         * otherwise never leaves the WebView: the only readouts are the on-screen
         * FPS pill and a devtools console, both of which need someone physically
         * at the head unit with a laptop attached. Piping them to logcat makes a
         * before/after comparison something you can capture with:
         *
         *     adb logcat -s H6Perf
         *
         * Debug builds only — this is a measurement tool, not telemetry, and it
         * should not spam a release logcat.
         */
        @JavascriptInterface
        public void reportPerf(String json) {
            if (isDebuggableBuild()) Log.i(PERF_TAG, json);
        }
    }

    public class MediaBridge {
        @JavascriptInterface
        public void prev() {
            Log.i(TAG, "MediaBridge.prev");
            mainHandler.post(mediaNowPlaying::prev);
        }

        @JavascriptInterface
        public void playPause() {
            Log.i(TAG, "MediaBridge.playPause");
            mainHandler.post(mediaNowPlaying::playPause);
        }

        @JavascriptInterface
        public void next() {
            Log.i(TAG, "MediaBridge.next");
            mainHandler.post(mediaNowPlaying::next);
        }

        /** Called by the viewer once the model loader overlay can clear. */
        @JavascriptInterface
        public void onViewerReady() {
            mainHandler.post(() -> {
                try {
                    mediaNowPlaying.start();
                    mediaNowPlaying.pushNow();
                } catch (Throwable t) {
                    Log.w(TAG, "mediaNowPlaying.start failed", t);
                }
            });
        }

        /** Hide the right freeform music app (keep playback); return to idle rail. */
        @JavascriptInterface
        public void dismissSlot() {
            mainHandler.post(() -> {
                try {
                    if (activeMediaPackage != null && !activeMediaPackage.isEmpty()) {
                        dismissMediaPopup(activeMediaPackage);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "dismissSlot failed", t);
                }
            });
        }
    }

    /** Always-left GWM shortcuts, same order as the OEM AppList. */
    private static final String[] PINNED_PACKAGES = {
        "com.beantechs.vehiclecenter",
        "com.beantechs.settings",
        "com.beantechs.energyassistant",
        "com.beantechs.launcher"
    };
    private static final String CAR_SETTINGS_PACKAGE = "com.android.car.settings";
    private static final String ANDROID_SETTINGS_PACKAGE = "com.android.settings";
    private static final String ANDROID_SETTINGS_ACTIVITY = "com.android.settings.Settings";

    private static final java.util.Set<String> IGNORED_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList(
        "com.beantechs.hvac",
        "com.beantechs.btphone",
        "com.beantechs.drivinganalysisservice",
        "com.beantechs.personalcenter",
        "com.beantechs.operatorcenter",
        "com.beantechs.account",
        "com.beantechs.applist",
        "com.beantechs.guidance",
        "com.beantechs.fotaui",
        "com.beantechs.PKIMaintain",
        "com.beantechs.adaptertool.client",
        "com.beantechs.sshost.client",
        "com.android.car.media",
        "com.android.car.radio",
        "com.beantechs.mediacenter",
        "com.beantechs.mediacenter.h5.ui",
        "com.beantechs.mediacenter.h5.core",
        "com.android.support.car.lenspicker",
        "com.autolink.enginmode",
        "com.apical.cj1005",
        "com.google.android.car.kitchensink",
        "com.nextdoordeveloper.miperf.miperf",
        "com.android.systemui",
        "com.android.keyguard",
        "com.android.webview",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.gwm.hvac",
        "com.gwm.vehicle",
        "com.gwm.car",
        "com.android.vending",
        "app.revanced.android.gms",
        "com.google.android.gms",
        "moe.shizuku.privileged.api",
        "com.ts.androidauto.app",
        "com.ts.androidauto",
        "com.ts.androidauto.projectionservice",
        "com.ts.carplay.app",
        "com.ts.carplay",
        "com.google.android.projection.gearhead"
    ));

    public class AppLauncherBridge {
        @JavascriptInterface
        public String getInstalledApps() {
            JSONArray appsArray = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);

                for (ResolveInfo info : apps) {
                    String pkg = info.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || IGNORED_PACKAGES.contains(pkg)
                            || hiddenPackages.contains(pkg)) continue;

                    String label = info.loadLabel(pm).toString();
                    JSONObject appObj = new JSONObject();
                    appObj.put("packageName", pkg);
                    appObj.put("label", label);

                    try {
                        Drawable icon = info.loadIcon(pm);
                        if (icon != null) {
                            int w = Math.max(1, icon.getIntrinsicWidth());
                            int h = Math.max(1, icon.getIntrinsicHeight());
                            if (w > 128 || h > 128 || w <= 0 || h <= 0) { w = 96; h = 96; }
                            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                            icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                            icon.draw(canvas);

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
                            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                            appObj.put("icon", "data:image/png;base64," + b64);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error drawing icon for " + pkg, e);
                    }

                    appsArray.put(appObj);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting installed apps", e);
            }
            return appsArray.toString();
        }

        @JavascriptInterface
        public void launchAppInPopup(String packageName) {
            launchAppForPackage(packageName, "");
        }

        @JavascriptInterface
        public void closeApp(String packageName) {
            closePopupApp(packageName);
        }

        @JavascriptInterface
        public void maximizeApp(String packageName) {
            maximizePopupApp(packageName);
        }

        @JavascriptInterface
        public void setShellMode(String mode) {
            mainHandler.post(() -> applyShellMode(mode));
        }

        @JavascriptInterface
        public void setLaunchSide(String side) {
            mainHandler.post(() -> applyLaunchSide(side));
        }

        /** Close freeform slots so WebView chrome (layout menu) can sit on top. */
        @JavascriptInterface
        public void dismissOverlays() {
            mainHandler.post(() -> dismissAllOverlays());
        }

        /**
         * Put the viewer's own chrome above the freeform windows while a menu is
         * open, instead of closing those windows to see it.
         */
        @JavascriptInterface
        public void setChromeOnTop(boolean onTop) {
            mainHandler.post(() -> applyChromeOnTop(onTop));
        }

        @JavascriptInterface
        public void setSlotUse(String side, String use) {
            mainHandler.post(() -> applySlotUse(side, use));
        }

        @JavascriptInterface
        public void saveWidgets(String json) {
            getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                    .edit()
                    .putString("widgets", json != null ? json : "")
                    .apply();
        }

        @JavascriptInterface
        public String loadWidgets() {
            return getSharedPreferences(PREFS_SHELL, MODE_PRIVATE).getString("widgets", "");
        }

        /**
         * Remember the shell background so the NEXT cold start can paint the
         * WebView with it. The WebView's own colour is what fills the screen
         * between Activity start and the page's first paint, so without this a
         * night-mode car flashes light grey on every launch.
         */
        @JavascriptInterface
        public void saveShellBackground(String css) {
            int color = parseCssColor(css, DEFAULT_SHELL_BG);
            getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                    .edit()
                    .putInt("shellBg", color)
                    .apply();
        }

        /**
         * Viewer → shell: the boot splash has handed off to the car, bring the
         * launcher icons in. The page owns the timing because it is the only side
         * that knows when the clip ended and the cross-fade finished.
         */
        @JavascriptInterface
        public void revealLauncher() {
            runOnUiThread(MainActivity.this::revealLauncherStrip);
        }

        /**
         * Viewer → shell: is anything already playing on the music stream?
         *
         * The boot clip carries a soundtrack, and an unmuted media element in
         * the WebView takes audio focus — which pauses whatever the driver had
         * running. There is no way to ask WebView for a ducking/transient focus
         * instead, so the only way not to interrupt is to stay silent. The
         * viewer calls this ONCE, before the clip's first play(), and keeps the
         * clip muted when it returns true.
         *
         * isMusicActive() covers any app on STREAM_MUSIC, including ones that
         * publish no MediaSession and so never reach MediaNowPlaying.
         */
        @JavascriptInterface
        public boolean isMusicActive() {
            try {
                android.media.AudioManager am =
                        (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                return am != null && am.isMusicActive();
            } catch (Throwable t) {
                Log.w(TAG, "isMusicActive failed", t);
                return false;
            }
        }

        /**
         * Viewer → shell: cross-fade the last splash frame over the 3D car.
         * Duration is milliseconds; matches HavalSplash.fadeMs.
         */
        @JavascriptInterface
        public void beginSplashFade(int durationMs) {
            final int ms = durationMs;
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(() -> {
                try {
                    MainActivity.this.beginSplashFade(ms);
                } finally {
                    latch.countDown();
                }
            });
            // Hold JS until the still is on screen, otherwise destroyVideo()
            // uncovers the car for a frame before the fade cover exists.
            try {
                latch.await(400, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Viewer → shell: the splash <video> overlay is coming down. Switch the
         * WebView back to a hardware layer NOW so the 3D canvas is actually
         * composited under the fading HTML. Waiting until the launcher icons
         * fly in leaves LAYER_TYPE_NONE for the whole cross-fade, which hides
         * WebGL and makes the clip look like it never hands off.
         */
        @JavascriptInterface
        public void endSplashOverlay() {
            runOnUiThread(MainActivity.this::endSplashOverlay);
        }

        /**
         * Boot HUD while the splash overlay covers the HTML loader. Progress is
         * 0–100; {@code text} is the short status ("42%", "PROCESSING…").
         */
        @JavascriptInterface
        public void setBootProgress(int progress, String text, String title) {
            final int pct = Math.max(0, Math.min(100, progress));
            final String label = text != null ? text : (pct + "%");
            final String heading = (title != null && title.length() > 0) ? title : "LOADING MODEL";
            runOnUiThread(() -> showBootHud(pct, label, heading));
        }

        @JavascriptInterface
        public void hideBootProgress() {
            runOnUiThread(() -> hideBootHud());
        }
    }

    /**
     * Fallback shell background when nothing has been saved yet. Black, because
     * every cold start now opens on the splash clip (assets/app-splash.mp4),
     * which is pure black edge to edge — so the window, the root container and
     * the WebView all match it and the gap before the page's first paint is
     * invisible instead of a grey flash.
     */
    private static final int DEFAULT_SHELL_BG = 0xff000000;

    /** Parse "#rgb"/"#rrggbb" into an opaque ARGB int, or fall back. */
    private static int parseCssColor(String css, int fallback) {
        if (css == null) return fallback;
        String s = css.trim();
        if (!s.startsWith("#")) return fallback;
        s = s.substring(1);
        if (s.length() == 3) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 3; i++) { b.append(s.charAt(i)).append(s.charAt(i)); }
            s = b.toString();
        }
        if (s.length() != 6) return fallback;
        try {
            return 0xff000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String activePopupPackage = "";
    private int activePopupTaskId = -1;
    private ComponentName activePopupComponent;
    private String activeMediaPackage = "";
    private int activeMediaTaskId = -1;
    private ComponentName activeMediaComponent;
    private long lastOverlayRaiseMs;
    private long overlayLaunchUntilMs;
    /** Short window after a re-raise where the watchdog must not declare a slot gone. */
    private long overlayRaiseGraceUntilMs;
    /** False once ATM.setTaskAlwaysOnTop is rejected (needs a signature permission). */
    private boolean taskAlwaysOnTopSupported = true;
    /** True while a viewer menu is open and must render above the freeform slots. */
    private boolean chromeOnTop;
    private Runnable chromeOnTopTimeout;
    private Runnable raiseOverlayRunnable;
    private long lastSlotRestartMs;
    private long lastLeftTaskRequestMs;
    private long lastRightTaskRequestMs;
    /** Null = untested, FALSE = this ROM has no reachable activity service / task list. */
    private Boolean systemActivityServiceAvailable;
    private Boolean systemTaskListAvailable;
    private Object taskStackListener;
    private final Runnable overlayWatchdog = new Runnable() {
        @Override
            public void run() {
            syncOverlaySlots(false);
            if (hasOverlayWindow()) mainHandler.postDelayed(this, 1000);
        }
    };
    private View mediaLaunchAnchor;
    private View stripContainer;
    /** Launcher icons, left to right — the order the boot reveal staggers them in. */
    private final List<MotionTrailLayout> launcherItems = new ArrayList<>();
    private ProjectionPresence projectionPresence;
    private MotionTrailLayout projectionItem;
    private View projectionGap;
    private final MotionTrailLayout[] pinnedItems = new MotionTrailLayout[PINNED_PACKAGES.length];
    private View pinnedGap;
    private final MotionTrailLayout[] recentItems = new MotionTrailLayout[3];
    private View recentsGap;
    private final List<String> recentPackages = new ArrayList<>();
    /** User-hidden packages (long-press → Hide). Survives restarts. */
    private final java.util.Set<String> hiddenPackages = new java.util.HashSet<>();
    /** Main-strip icons keyed by package so recents can hide the duplicate. */
    private final java.util.Map<String, MotionTrailLayout> dockItemsByPackage =
            new java.util.LinkedHashMap<>();
    private android.widget.PopupWindow dockEditMenu;
    private final BroadcastReceiver packageRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getData() == null) return;
            String pkg = intent.getData().getSchemeSpecificPart();
            if (pkg == null || pkg.isEmpty()) return;
            mainHandler.post(() -> removeDockPackage(pkg, false));
        }
    };
    /** One-shot: the boot reveal must not replay on a later viewer reload. */
    private boolean launcherRevealed;
    /**
     * Backstop for the launcher reveal. The strip starts hidden and normally
     * comes back when the page calls AppLauncherBridge.revealLauncher() — but if
     * the page fails to load, throws before that, or is an older build without
     * the call, the launcher would be gone for the whole session. Comfortably
     * longer than a cold start (~14s) plus the splash hand-off.
     */
    private final Runnable launcherRevealFallback = new Runnable() {
        @Override
        public void run() {
            if (launcherRevealed) return;
            Log.w(TAG, "Viewer never signalled splash hand-off — revealing launcher anyway");
            revealLauncherStrip();
        }
    };
    private FrameLayout rootLayout;
    private String shellMode = SHELL_TRIPLE;
    private String launchSidePref = "auto";
    private boolean nextLaunchLeft = true;
    /** Side that most recently received a freeform launch (for L/R HUD). */
    private String lastLaunchSide = "";
    private String leftSlotUse = "app";
    private String rightSlotUse = "app";
    private Runnable pinMediaBoundsRunnable;
    private final MediaNowPlaying mediaNowPlaying = new MediaNowPlaying();
    private View bootHud;
    private android.widget.TextView bootHudTitle;
    private android.widget.TextView bootHudPct;
    private View bootHudFill;
    private int bootHudBarWidthPx;
    private View splashSkipBtn;
    private android.widget.ImageView splashFadeView;
    private Bitmap splashHoldFrame;
    private boolean splashFadeStarted;
    private boolean splashOverlayEnded;
    private boolean splashDropScheduled;
    private final Runnable forceDropSplash = new Runnable() {
        @Override
        public void run() {
            dropSplashFromShell();
        }
    };

    /**
     * Left freeform slot over our fullscreen launcher (not split-screen).
     * Right slot: slim idle now-playing in the WebView; music apps open wider.
     * Bounds are screen pixels for the Haval ~1920×720 panel.
     * <p>
     * Emulator note: stock AVDs ship with freeform off. Enable once:
     * {@code adb shell settings put global enable_freeform_support 1}
     * {@code adb shell settings put global force_resizable_activities 1}
     */
    private static final Rect LEFT_POPUP_BOUNDS = new Rect(48, 100, 740, 530);
    /** Right media slot (idle now-playing + music apps share these bounds). */
    private static final Rect RIGHT_APP_BOUNDS = new Rect(1180, 100, 1872, 530);
    private static final Rect RIGHT_IDLE_BOUNDS = RIGHT_APP_BOUNDS;
    private static final String SHELL_TRIPLE = "triple";
    private static final String SHELL_APP_CAR = "appCar";
    private static final String SHELL_APPS = "appsOnly";
    private static final String PREFS_SHELL = "h6_shell";
    private static final Rect FULLSCREEN_BOUNDS = new Rect(0, 0, 1920, 720);
    /** {@code WindowConfiguration.WINDOWING_MODE_FREEFORM} (API 28+). */
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final String KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode";
    private static final String KEY_LAUNCH_BOUNDS = "android:activity.launchBounds";
    /** Debug/test: {@code adb shell am start -n com.havalh6.viewer/.MainActivity --es launch_freeform <pkg>} */
    private static final String EXTRA_LAUNCH_FREEFORM = "launch_freeform";
    /** Debug/test: {@code --ei raise_task_id <taskId>} (see maybeRaiseTaskFromIntent). */
    private static final String EXTRA_RAISE_TASK_ID = "raise_task_id";

    /** Best-effort: emulator needs this; car MMI usually already has freeform. */
    private void ensureFreeformSettings() {
        try {
            android.provider.Settings.Global.putInt(getContentResolver(), "enable_freeform_support", 1);
        } catch (SecurityException e) {
            Log.i(TAG, "Cannot write enable_freeform_support (need shell / WRITE_SECURE_SETTINGS)");
        }
        try {
            android.provider.Settings.Global.putInt(getContentResolver(), "force_resizable_activities", 1);
        } catch (SecurityException ignored) {}
    }

    private static void allowHiddenApis() {
        try {
            java.lang.reflect.Method forName = Class.class.getDeclaredMethod("forName", String.class);
            java.lang.reflect.Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            java.lang.reflect.Method getRuntime =
                    (java.lang.reflect.Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null);
            java.lang.reflect.Method setHiddenApiExemptions =
                    (java.lang.reflect.Method) getDeclaredMethod.invoke(
                            vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});
            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) {}
    }

    private Bundle buildWindowOptions(int windowingMode, Rect bounds) {
        allowHiddenApis();
        ActivityOptions options;
        // Clip-reveal from the matching slot anchor so the system prefers that screen region
        // (helps when an app remembers a previous freeform position).
        View anchor = launchAnchorForBounds(bounds);
        if (windowingMode == WINDOWING_MODE_FREEFORM && anchor != null
                && anchor.getWidth() > 0 && anchor.getHeight() > 0) {
            options = ActivityOptions.makeClipRevealAnimation(
                    anchor, 0, 0, anchor.getWidth(), anchor.getHeight());
        } else {
            options = ActivityOptions.makeBasic();
        }
        try {
            java.lang.reflect.Method method =
                    ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            method.invoke(options, windowingMode);
        } catch (Exception e) {
            Log.w(TAG, "setLaunchWindowingMode reflection failed; using bundle key", e);
        }
        if (bounds != null) options.setLaunchBounds(new Rect(bounds));
        Bundle bundle = options.toBundle();
        if (bundle == null) bundle = new Bundle();
        bundle.putInt(KEY_LAUNCH_WINDOWING_MODE, windowingMode);
        if (bounds != null) bundle.putParcelable(KEY_LAUNCH_BOUNDS, new Rect(bounds));
        // Do NOT set taskAlwaysOnTop here. Android 12+ (this emulator) throws
        // SecurityException and aborts the entire startActivity. Stay-on-top is
        // applied after the task exists via setTaskAlwaysOnTop / moveTaskToFront.
        return bundle;
    }

    private View launchAnchorForBounds(Rect bounds) {
        Rect d = displayRect();
        if (bounds != null && bounds.left >= d.width() / 2) return mediaLaunchAnchor;
        return launchAnchor;
    }

    private Rect leftFreeformBounds() {
        Rect d = usableDisplayRect();
        int dock = dockReservePx();
        if (SHELL_APPS.equals(shellMode)) {
            int inset = Math.max(6, Math.round(d.width() * 0.004f));
            int gap = Math.max(6, Math.round(d.width() * 0.004f));
            return clampSlot(new Rect(d.left + inset, d.top,
                    d.centerX() - gap / 2, d.bottom - dock), d);
        }
        if (SHELL_APP_CAR.equals(shellMode)) {
            return clampSlot(new Rect(
                    d.left + Math.round(d.width() * 0.016f),
                    d.top,
                    d.left + Math.round(d.width() * 0.54f),
                    d.bottom - dock), d);
        }
        return clampSlot(scaleToUsable(LEFT_POPUP_BOUNDS, d), d);
    }

    private Rect rightFreeformBounds() {
        Rect d = usableDisplayRect();
        int dock = dockReservePx();
        if (SHELL_APPS.equals(shellMode)) {
            int inset = Math.max(6, Math.round(d.width() * 0.004f));
            int gap = Math.max(6, Math.round(d.width() * 0.004f));
            return clampSlot(new Rect(d.centerX() + gap / 2, d.top,
                    d.right - inset, d.bottom - dock), d);
        }
        return clampSlot(scaleToUsable(RIGHT_APP_BOUNDS, d), d);
    }

    /**
     * Display area the MMI's own bars leave free, in **display** coordinates.
     * Freeform launch bounds are display-relative, so they have to skip the OEM
     * left nav bar and top header instead of starting at 0,0 — otherwise a slot
     * opens underneath them.
     */
    private Rect usableDisplayRect() {
        android.graphics.Point real = new android.graphics.Point();
        try {
            getWindowManager().getDefaultDisplay().getRealSize(real);
        } catch (Exception ignored) {}
        if (real.x <= 0 || real.y <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            real.set(dm.widthPixels, dm.heightPixels);
        }
        Rect full = new Rect(0, 0, real.x, real.y);
        Rect r = new Rect(full);
        try {
            View decor = getWindow() != null ? getWindow().getDecorView() : null;
            android.view.WindowInsets insets = decor != null ? decor.getRootWindowInsets() : null;
            if (insets != null) {
                // Remember the rail's real width from a moment it was reported, so
                // slots still clear it while we have it hidden.
                widestNavInsetPx = Math.max(widestNavInsetPx, insets.getSystemWindowInsetLeft());
                r.left += insets.getSystemWindowInsetLeft();
                r.top += insets.getSystemWindowInsetTop();
                r.right -= insets.getSystemWindowInsetRight();
                r.bottom -= insets.getSystemWindowInsetBottom();
            }
        } catch (Exception ignored) {}
        // Insets arrive late during startup; a nonsense rect means "not yet".
        if (r.width() < 320 || r.height() < 240) return full;
        return r;
    }

    /** Panel the slot constants were authored against (the MMI is exactly this). */
    private static final Rect SLOT_REFERENCE = new Rect(0, 0, 1920, 720);

    /** Map a constant authored in full-panel pixels onto the usable area. */
    private Rect scaleToUsable(Rect reference, Rect usable) {
        float sx = usable.width() / (float) SLOT_REFERENCE.width();
        float sy = usable.height() / (float) SLOT_REFERENCE.height();
        return new Rect(
                usable.left + Math.round(reference.left * sx),
                usable.top + Math.round(reference.top * sy),
                usable.left + Math.round(reference.right * sx),
                usable.top + Math.round(reference.bottom * sy));
    }

    /**
     * Top band owned by the viewer's own chrome (layout / L / R buttons, CONFIG),
     * measured from the top of our window. The button row starts at the page's
     * safeTop (22dp) and is 58dp tall, so it ends at 80dp; the rest is breathing
     * room. A freeform window overlapping this band makes the layout button
     * unreachable, so every slot starts below it.
     */
    private int chromeReservePx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(96f * density);
    }

    /**
     * Width of the MMI's left nav rail. We hide it while we hold focus, so the
     * window insets then report 0 — but a freeform popup that takes focus brings
     * it back (a multi-window window cannot control system bars on Android 9), and
     * it draws above every app window. Slots must clear it whatever the insets say,
     * or the popup's left edge ends up underneath it.
     */
    /** The MMI's rail measured off the panel: NavigationBar window is [0,0][128,720]. */
    private static final float NAV_RAIL_FALLBACK_DP = 128f;

    private int navRailReservePx() {
        int id = getResources().getIdentifier("navigation_bar_width", "dimen", "android");
        int fromRes = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        // The resource is not guaranteed to describe this ROM's side rail, and the
        // insets read 0 whenever we have it hidden, so floor it at the measured width.
        float density = getResources().getDisplayMetrics().density;
        int floor = Math.round(NAV_RAIL_FALLBACK_DP * (density <= 0f ? 1f : density));
        int reserve = Math.max(Math.max(fromRes, widestNavInsetPx), floor);
        if (reserve != loggedNavReservePx) {
            loggedNavReservePx = reserve;
            Log.w(TAG, "Nav rail reserve=" + reserve + " (res=" + fromRes
                    + " observed=" + widestNavInsetPx + ")");
        }
        return reserve;
    }

    private Rect clampSlot(Rect r, Rect usable) {
        int top = Math.max(r.top, usable.top + chromeReservePx());
        int bottom = Math.min(r.bottom, usable.bottom - dockReservePx());
        if (bottom - top < 200) bottom = Math.min(usable.bottom, top + 200);
        // Symmetric side band: the rail's width is kept clear on both edges.
        int rail = navRailReservePx();
        android.graphics.Point real = new android.graphics.Point();
        try {
            getWindowManager().getDefaultDisplay().getRealSize(real);
        } catch (Exception ignored) {}
        int rightLimit = real.x > 0 ? real.x - rail : usable.right;
        return new Rect(
                Math.max(Math.max(r.left, usable.left), rail),
                top,
                Math.min(Math.min(r.right, usable.right), rightLimit),
                bottom);
    }

    private Rect displayRect() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    /** Gap between the launcher icon strip and the OEM climate dock. */
    private int launcherBottomGapPx() {
        float density = getResources().getDisplayMetrics().density;
        // +5px lifts the strip slightly above the OEM climate dock.
        return Math.round(40 * density) + 5;
    }

    /** Bottom offset for FPS / SKIP chrome (CSS 60px). */
    private int chromeBottomGapPx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(60f * density);
    }

    private int dockReservePx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((110f + 40f + 8f) * density);
    }

    private void updateSlotAnchors() {
        // Slot rects are display-relative; the anchors live inside our window.
        Rect usable = pageOriginRect();
        applyAnchorRect(launchAnchor, toWindowRect(leftFreeformBounds(), usable));
        applyAnchorRect(mediaLaunchAnchor, toWindowRect(rightFreeformBounds(), usable));
    }

    private static Rect toWindowRect(Rect displayBounds, Rect usable) {
        Rect r = new Rect(displayBounds);
        r.offset(-usable.left, -usable.top);
        return r;
    }

    private static void applyAnchorRect(View anchor, Rect bounds) {
        if (anchor == null || bounds == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) anchor.getLayoutParams();
        if (lp == null) return;
        lp.width = bounds.width();
        lp.height = bounds.height();
        lp.leftMargin = bounds.left;
        lp.topMargin = bounds.top;
        anchor.setLayoutParams(lp);
    }

    private float cssPx(int px) {
        float density = getResources().getDisplayMetrics().density;
        return px / (density <= 0f ? 1f : density);
    }

    /**
     * Margin the page must keep clear on one side so chrome, widgets and icons sit
     * inside a band matching the nav rail — the WebView itself stays full-bleed, so
     * the 3D background still runs edge to edge behind it.
     * <p>
     * {@code origin.left + safeLeft} always equals the rail width on the display,
     * whether the OEM has inset our window (freeform stole focus and brought the
     * rail back) or we are still full-bleed. Using the UI mode instead of the
     * measured origin double-counted the band and shoved everything to the right.
     */
    private int pageSideInsetPx(boolean rightSide) {
        android.graphics.Point real = new android.graphics.Point();
        try {
            getWindowManager().getDefaultDisplay().getRealSize(real);
        } catch (Exception ignored) {}
        int rail = navRailReservePx();
        Rect origin = pageOriginRect();
        int windowInset = rightSide
                ? Math.max(0, real.x - origin.right)
                : Math.max(0, origin.left);
        return Math.max(0, rail - windowInset);
    }

    /**
     * Display-space rect the page's (0,0) maps to, measured from the content view.
     * <p>
     * Do not infer this from {@link #laidOutFullBleed()}: a focused freeform
     * window forces the OEM rail back and the window manager re-frames us even
     * in floating/full modes. LAYOUT_STABLE insets also keep reporting bars we
     * have hidden. {@link View#getLocationOnScreen} is the only source that
     * answers "where did the system actually put us".
     */
    private Rect pageOriginRect() {
        android.graphics.Point real = new android.graphics.Point();
        try {
            getWindowManager().getDefaultDisplay().getRealSize(real);
        } catch (Exception ignored) {}
        if (real.x <= 0 || real.y <= 0) return usableDisplayRect();
        if (rootLayout != null && rootLayout.getWidth() > 0 && rootLayout.getHeight() > 0) {
            int[] loc = new int[2];
            rootLayout.getLocationOnScreen(loc);
            return new Rect(loc[0], loc[1],
                    loc[0] + rootLayout.getWidth(), loc[1] + rootLayout.getHeight());
        }
        if (!laidOutFullBleed()) return usableDisplayRect();
        return new Rect(0, 0, real.x, real.y);
    }

    private int statusBarHeightPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /**
     * Top margin the page must keep clear. Zero when the window already starts
     * below the header; the leftover header height when it floats over us.
     * Measured the same way as {@link #pageSideInsetPx} so a freeform-driven
     * re-frame cannot double-count the header.
     */
    private int pageTopInsetPx() {
        if (!statusBarShown()) return 0;
        return Math.max(0, statusBarHeightPx() - Math.max(0, pageOriginRect().top));
    }

    private String boundsToCssJson(Rect bounds) {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) density = 1f;
        // Slot rects are display-relative, but the page's origin is our window.
        // Must be the window's real position: in the full-bleed modes the stable
        // insets still claim bars we have hidden, which shifted the boards out of
        // the side band.
        Rect r = toWindowRect(bounds, pageOriginRect());
        return "{l:" + (r.left / density)
                + ",t:" + (r.top / density)
                + ",r:" + (r.right / density)
                + ",b:" + (r.bottom / density) + "}";
    }

    private void launchAppForPackage(String packageName, String label) {
        if (packageName == null || packageName.isEmpty()) return;
        rememberRecentApp(packageName);
        if (CAR_SETTINGS_PACKAGE.equals(packageName)) {
            launchAndroidSettingsRoot();
            return;
        }
        if (isGwmApp(packageName) || isPinnedPackage(packageName)) {
            launchAppFullscreen(packageName);
            return;
        }
        if (packageName.equals(activePopupPackage)) {
            dismissPopup(packageName);
            return;
        }
        if (packageName.equals(activeMediaPackage)) {
            dismissMediaPopup(packageName);
            return;
        }
        String side = resolveLaunchSide();
        if (side == null || side.isEmpty()) {
            // Log.w, not Log.i: the ROM drops info level, and this path is a
            // tapped icon doing nothing — the reason has to be visible.
            Log.w(TAG, "No APP slot for " + packageName + ": mode=" + shellMode
                    + " leftUse=" + leftSlotUse + " rightUse=" + rightSlotUse
                    + " leftApp=" + activePopupPackage + " rightApp=" + activeMediaPackage);
            return;
        }
        applySlotUse(side, "app");
        if ("right".equals(side)) launchAppInRightSlot(packageName);
        else launchAppInLeftSlot(packageName);
    }

    private void loadShellPrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE);
        String mode = prefs.getString("mode", SHELL_TRIPLE);
        if (SHELL_APPS.equals(mode)) mode = SHELL_TRIPLE;
        shellMode = mode;
        launchSidePref = prefs.getString("launchSide", "auto");
        nextLaunchLeft = prefs.getBoolean("nextLeft", true);
        uiMode = readUiModePref();
        loadSlotUsesForMode();
    }

    private void saveShellPrefs() {
        android.content.SharedPreferences.Editor ed = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE).edit();
        if (!SHELL_APPS.equals(shellMode)) ed.putString("mode", shellMode);
        ed.putString("launchSide", launchSidePref);
        ed.putBoolean("nextLeft", nextLaunchLeft);
        ed.putString("uiMode", uiMode);
        ed.apply();
    }

    private void applyShellMode(String mode) {
        if (mode == null) return;
        if (!SHELL_TRIPLE.equals(mode) && !SHELL_APP_CAR.equals(mode) && !SHELL_APPS.equals(mode)) {
            mode = SHELL_TRIPLE;
        }
        String prev = shellMode;
        shellMode = mode;
        saveShellPrefs();
        loadSlotUsesForMode();
        updateSlotAnchors();

        boolean leftOpen = activePopupPackage != null && !activePopupPackage.isEmpty();
        boolean rightOpen = activeMediaPackage != null && !activeMediaPackage.isEmpty();

        // APP+CAR has no right freeform slot — close the right app if open.
        if (SHELL_APP_CAR.equals(shellMode) && rightOpen) {
            dismissMediaPopup(activeMediaPackage);
            rightOpen = false;
        }

        // APP+APP is full-bleed halves: if somehow only the "wrong" slot matters,
        // still keep both when present. Switching away from appsOnly just re-pins
        // to the smaller triple/appCar rects below.

        // Resize remaining freeform windows to the new layout bounds.
        if (leftOpen && activePopupComponent != null) {
            schedulePinPopupBounds(activePopupPackage, activePopupComponent, leftFreeformBounds(), false);
        }
        if (rightOpen && activeMediaComponent != null) {
            schedulePinPopupBounds(activeMediaPackage, activeMediaComponent, rightFreeformBounds(), true);
        }

        // Entering app+car from a two-app layout already closed right; if left is
        // still open, the pin above expands it into the wide left slot.
        if (!prev.equals(shellMode)) {
            Log.i(TAG, "Shell mode " + prev + " → " + shellMode
                    + " leftOpen=" + leftOpen + " rightOpen=" + rightOpen);
        }
        notifyViewerShellLayout();
    }

    private void applyLaunchSide(String side) {
        if ("left".equals(side) || "right".equals(side) || "auto".equals(side)) {
            launchSidePref = side;
        } else {
            launchSidePref = "auto";
        }
        // When locking a side, mirror that onto the next-side indicator.
        if ("left".equals(launchSidePref)) nextLaunchLeft = true;
        else if ("right".equals(launchSidePref)) nextLaunchLeft = false;
        saveShellPrefs();
        notifyViewerShellLayout();
    }

    private void loadSlotUsesForMode() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE);
        // Retired concept: boards are always widgets and apps float over them.
        // Reported as "widgets" so the page keeps drawing the board underneath.
        leftSlotUse = "widgets";
        rightSlotUse = "widgets";
    }

    /**
     * Which sides can host an app, decided by the layout alone. The old per-slot
     * "app vs widgets" switch is gone: widgets are always present and a launched
     * app simply floats above them, so a slot never stops accepting apps.
     */
    private boolean slotAllowsApp(String side) {
        if ("right".equals(side)) return !SHELL_APP_CAR.equals(shellMode);
        return true;
    }

    private void applySlotUse(String side, String use) {
        if (!"widgets".equals(use)) use = "app";
        String current = "right".equals(side) ? rightSlotUse : leftSlotUse;
        if (use.equals(current)) return;
        if ("right".equals(side)) rightSlotUse = use;
        else leftSlotUse = use;
        getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .edit()
                .putString("slot_" + shellMode + "_" + (("right".equals(side) ? "right" : "left")), use)
                .apply();
        if ("widgets".equals(use)) {
            if ("right".equals(side) && activeMediaPackage != null && !activeMediaPackage.isEmpty()) {
                dismissMediaPopup(activeMediaPackage);
            } else if ("left".equals(side) && activePopupPackage != null && !activePopupPackage.isEmpty()) {
                dismissPopup(activePopupPackage);
            }
        }
        notifyViewerShellLayout();
    }

    private String resolveLaunchSide() {
        boolean leftFree = (activePopupPackage == null || activePopupPackage.isEmpty()) && slotAllowsApp("left");
        boolean rightFree = (activeMediaPackage == null || activeMediaPackage.isEmpty()) && slotAllowsApp("right");
        if (!leftFree && !rightFree) return "";
        if (SHELL_APP_CAR.equals(shellMode)) return leftFree ? "left" : "";

        String want;
        if ("left".equals(launchSidePref) || "right".equals(launchSidePref)) {
            want = launchSidePref;
            if ("left".equals(want) && !leftFree) want = rightFree ? "right" : "";
            else if ("right".equals(want) && !rightFree) want = leftFree ? "left" : "";
        } else {
            // Auto round-robin: pick the pending side, then prefer an empty slot.
            want = nextLaunchLeft ? "left" : "right";
            if (!slotAllowsApp(want)) want = "left".equals(want) ? "right" : "left";
            if (SHELL_APPS.equals(shellMode)) {
                if (leftFree && !rightFree) want = "left";
                else if (rightFree && !leftFree) want = "right";
            }
            if ("left".equals(want) && !leftFree && rightFree) want = "right";
            else if ("right".equals(want) && !rightFree && leftFree) want = "left";
            if (!slotAllowsApp(want)) want = "";
        }

        // L / R name where the *next* app lands, so every launch hands the
        // pointer to the other side — including an explicitly picked one, and
        // including a launch that had to fall back to the free slot.
        if (!want.isEmpty()) {
            nextLaunchLeft = !"left".equals(want);
            if ("left".equals(launchSidePref) || "right".equals(launchSidePref)) {
                launchSidePref = nextLaunchLeft ? "left" : "right";
            }
            saveShellPrefs();
            mainHandler.post(this::notifyViewerShellLayout);
        }
        lastLaunchSide = want;
        return want;
    }

    private void forceStopPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(packageName);
        } catch (Exception ignored) {}
        try {
            new ProcessBuilder("am", "force-stop", packageName)
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            Log.w(TAG, "force-stop failed for " + packageName, e);
        }
    }

    private int findTaskIdForPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return -1;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            @SuppressWarnings("deprecation")
            List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(32);
            if (tasks != null) {
                for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                    int id = taskIdIfPackage(task, packageName);
                    if (id >= 0) return id;
                }
            }
            @SuppressWarnings("deprecation")
            List<android.app.ActivityManager.RecentTaskInfo> recent =
                    am.getRecentTasks(32, android.app.ActivityManager.RECENT_WITH_EXCLUDED);
            if (recent != null) {
                for (android.app.ActivityManager.RecentTaskInfo task : recent) {
                    int id = recentTaskIdIfPackage(task, packageName);
                    if (id >= 0) return id;
                }
            }
            int hiddenId = findTaskIdViaSystemService(packageName);
            if (hiddenId >= 0) return hiddenId;
        } catch (Exception e) {
            Log.w(TAG, "findTaskIdForPackage failed", e);
        }
        return -1;
    }

    /**
     * The system's activity service: {@code IActivityTaskManager} on Android 10+,
     * {@code IActivityManager} on Android 9 and older. The Haval MMI is Android 9,
     * where {@code android.app.ActivityTaskManager} does not exist at all.
     */
    private Object systemActivityService() {
        if (Boolean.FALSE.equals(systemActivityServiceAvailable)) return null;
        allowHiddenApis();
        try {
            Object service;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                service = Class.forName("android.app.ActivityTaskManager")
                        .getMethod("getService").invoke(null);
            } else {
                service = android.app.ActivityManager.class.getMethod("getService").invoke(null);
            }
            if (service == null) {
                systemActivityServiceAvailable = false;
                return null;
            }
            systemActivityServiceAvailable = true;
            return service;
        } catch (Throwable t) {
            // Log once: callers run from a 200 ms pin loop, and a stack trace per
            // tick floods the ROM's log buffer.
            if (systemActivityServiceAvailable == null) {
                Log.w(TAG, "System activity service unavailable (" + t.getClass().getSimpleName() + ")");
            }
            systemActivityServiceAvailable = false;
            return null;
        }
    }

    /**
     * Platform task list. Foreign tasks are filtered out unless the caller holds
     * REAL_GET_TASKS, so on the MMI this normally returns our own tasks only.
     */
    private List<?> systemTaskList() {
        Object service = systemActivityService();
        if (service == null) return null;
        try {
            java.lang.reflect.Method getTasks = null;
            for (java.lang.reflect.Method cand : service.getClass().getMethods()) {
                if ("getTasks".equals(cand.getName()) && cand.getParameterTypes().length >= 1) {
                    getTasks = cand;
                    break;
                }
            }
            if (getTasks == null) return null;
            Class<?>[] p = getTasks.getParameterTypes();
            Object result;
            if (p.length == 1) result = getTasks.invoke(service, 32);
            else if (p.length == 2 && p[1] == boolean.class) result = getTasks.invoke(service, 32, false);
            else result = getTasks.invoke(service, 32, false, false);
            return result instanceof List ? (List<?>) result : null;
        } catch (Throwable t) {
            if (!Boolean.FALSE.equals(systemTaskListAvailable)) {
                Log.w(TAG, "System getTasks failed (" + t.getClass().getSimpleName() + ")");
            }
            systemTaskListAvailable = false;
            return null;
        }
    }

    /** 1 visible, 0 gone, -1 tasks filtered/unavailable. */
    private int overlayVisibleViaSystemService(String packageName) {
        List<?> tasks = systemTaskList();
        if (tasks == null) return -1;
        boolean sawForeign = false;
        for (Object item : tasks) {
            if (!(item instanceof android.app.ActivityManager.RunningTaskInfo)) continue;
            android.app.ActivityManager.RunningTaskInfo task =
                    (android.app.ActivityManager.RunningTaskInfo) item;
            android.content.ComponentName top = task.topActivity;
            android.content.ComponentName base = task.baseActivity;
            String pkg = top != null ? top.getPackageName()
                    : (base != null ? base.getPackageName() : null);
            if (pkg == null) continue;
            if (!getPackageName().equals(pkg)) sawForeign = true;
            if (packageName.equals(pkg)) return runningTaskIsVisible(task) ? 1 : 0;
        }
        if (!sawForeign) return -1;
        return 0;
    }

    private int findTaskIdViaSystemService(String packageName) {
        List<?> tasks = systemTaskList();
        if (tasks == null) return -1;
        for (Object item : tasks) {
            if (item instanceof android.app.ActivityManager.RunningTaskInfo) {
                int id = taskIdIfPackage((android.app.ActivityManager.RunningTaskInfo) item, packageName);
                if (id >= 0) return id;
            }
        }
        return -1;
    }

    private static int taskIdIfPackage(android.app.ActivityManager.RunningTaskInfo task, String packageName) {
        if (task == null) return -1;
        android.content.ComponentName top = task.topActivity;
        android.content.ComponentName base = task.baseActivity;
        if ((top != null && packageName.equals(top.getPackageName()))
                || (base != null && packageName.equals(base.getPackageName()))) {
            return task.id;
        }
        return -1;
    }

    private static int recentTaskIdIfPackage(android.app.ActivityManager.RecentTaskInfo task, String packageName) {
        if (task == null) return -1;
        android.content.ComponentName orig = task.origActivity;
        android.content.ComponentName base = task.baseIntent != null ? task.baseIntent.getComponent() : null;
        // realActivity is API 29+; read it reflectively so API 28 compile still works.
        android.content.ComponentName real = recentTaskRealActivity(task);
        if ((orig != null && packageName.equals(orig.getPackageName()))
                || (real != null && packageName.equals(real.getPackageName()))
                || (base != null && packageName.equals(base.getPackageName()))) {
            return task.persistentId > 0 ? task.persistentId : task.id;
        }
        return -1;
    }

    private static android.content.ComponentName recentTaskRealActivity(
            android.app.ActivityManager.RecentTaskInfo task) {
        try {
            java.lang.reflect.Field field =
                    android.app.ActivityManager.RecentTaskInfo.class.getField("realActivity");
            Object value = field.get(task);
            if (value instanceof android.content.ComponentName) {
                return (android.content.ComponentName) value;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Best-effort pin of a freeform task to a slot rect. */
    private boolean resizeTaskToBounds(int taskId, Rect bounds) {
        if (taskId < 0 || bounds == null) return false;
        allowHiddenApis();
        Rect copy = new Rect(bounds);

        // Android 7–9: ActivityManager.resizeTask (hidden).
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            try {
                java.lang.reflect.Method m = android.app.ActivityManager.class.getMethod(
                        "resizeTask", int.class, Rect.class);
                m.invoke(am, taskId, copy);
                Log.w(TAG, "Pinned task " + taskId + " via AM.resizeTask(2) " + copy);
                return true;
            } catch (NoSuchMethodException ignored) {}
            try {
                java.lang.reflect.Method m = android.app.ActivityManager.class.getMethod(
                        "resizeTask", int.class, Rect.class, int.class);
                m.invoke(am, taskId, copy, 0);
                Log.w(TAG, "Pinned task " + taskId + " via AM.resizeTask(3) " + copy);
                return true;
            } catch (NoSuchMethodException ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "AM.resizeTask failed for task " + taskId, e);
        }

        // Same call on the system activity service (ATM on 10+, AM on 9).
        try {
            Object service = systemActivityService();
            if (service == null) return false;
            java.lang.reflect.Method resize = null;
            for (java.lang.reflect.Method m : service.getClass().getMethods()) {
                if (!"resizeTask".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length >= 2 && p[0] == int.class && Rect.class.isAssignableFrom(p[1])) {
                    resize = m;
                    break;
                }
            }
            if (resize != null) {
                Class<?>[] p = resize.getParameterTypes();
                if (p.length == 2) {
                    resize.invoke(service, taskId, copy);
                } else if (p.length >= 3 && p[2] == int.class) {
                    resize.invoke(service, taskId, copy, 0);
                } else {
                    return false;
                }
                Log.w(TAG, "Pinned task " + taskId + " via ATM.resizeTask " + copy);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "ATM.resizeTask failed for task " + taskId, e);
        }
        return false;
    }

    /**
     * Apps often restore a previous freeform rect after first layout. Keep
     * re-applying the slot bounds for a few seconds, and re-issue
     * startActivity with the same bounds so LaunchParams pick them up.
     */
    private void schedulePinPopupBounds(final String packageName,
            final android.content.ComponentName component, final Rect bounds, final boolean rightSlot) {
        Runnable prev = rightSlot ? pinMediaBoundsRunnable : pinBoundsRunnable;
        if (prev != null) mainHandler.removeCallbacks(prev);
        final int[] attempts = {0};
        Runnable pin = new Runnable() {
            @Override
            public void run() {
                String active = rightSlot ? activeMediaPackage : activePopupPackage;
                if (packageName == null || !packageName.equals(active)) return;
                int taskId = findTaskIdForPackage(packageName);
                if (taskId >= 0) {
                    if (rightSlot) activeMediaTaskId = taskId;
                    else activePopupTaskId = taskId;
                    resizeTaskToBounds(taskId, bounds);
                    setTaskAlwaysOnTop(taskId, true);
                }
                // Second start on the same task (no MULTIPLE_TASK) re-applies launch bounds
                // when resizeTask is blocked or the app overwrote saved window size.
                if (attempts[0] == 1 && component != null) {
                    try {
                        Intent again = new Intent(Intent.ACTION_MAIN);
                        again.addCategory(Intent.CATEGORY_LAUNCHER);
                        again.setComponent(component);
                        again.setPackage(packageName);
                        again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        startActivity(again, buildWindowOptions(WINDOWING_MODE_FREEFORM, bounds));
                    } catch (Exception e) {
                        Log.w(TAG, "Re-apply launch bounds failed", e);
                    }
                }
                attempts[0]++;
                if (attempts[0] < 16) mainHandler.postDelayed(this, 200);
            }
        };
        if (rightSlot) pinMediaBoundsRunnable = pin;
        else pinBoundsRunnable = pin;
        mainHandler.postDelayed(pin, 150);
    }

    /**
     * Contract with the WebView viewer:
     * {@code window.onAndroidShellLayout({left, right})} where right is
     * {@code 'idle'|'app'}. Also keeps {@code onAndroidLauncherPopup(left)}
     * for older viewer builds.
     */
    /** Keep the native icon strip inside the same side band as the page chrome. */
    private void applyChromeSideInsets() {
        if (stripContainer == null) return;
        stripContainer.setPadding(pageSideInsetPx(false), stripContainer.getPaddingTop(),
                pageSideInsetPx(true), stripContainer.getPaddingBottom());
    }

    private void notifyViewerShellLayout() {
        if (webView == null) return;
        // Before the first layout pass rootLayout has no size, so pageOriginRect()
        // falls back to insets that describe a window we are not in yet — which
        // sent safeTop=22 during startup and put the chrome under the header.
        // Re-push once it is measured.
        if (rootLayout == null || rootLayout.getWidth() <= 0 || rootLayout.getHeight() <= 0) {
            mainHandler.postDelayed(this::notifyViewerShellLayout, 300);
            return;
        }
        applyChromeSideInsets();
        boolean left = activePopupPackage != null && !activePopupPackage.isEmpty();
        String right = (activeMediaPackage != null && !activeMediaPackage.isEmpty())
                ? "\"app\"" : "\"idle\"";
        applyLauncherFocusPolicy();
        String nextSide = nextLaunchLeft ? "left" : "right";
        if ("left".equals(launchSidePref) || "right".equals(launchSidePref)) nextSide = launchSidePref;
        String lastSide = (lastLaunchSide != null && !lastLaunchSide.isEmpty())
                ? lastLaunchSide : nextSide;
        // Prefer the richer shell contract; fall back to the legacy left-only callback.
        final String js = "try{"
                + "if(window.onAndroidShellLayout){window.onAndroidShellLayout({left:" + left
                + ",right:" + right
                + ",mode:\"" + shellMode + "\""
                + ",launchSide:\"" + launchSidePref + "\""
                + ",nextSide:\"" + nextSide + "\""
                + ",lastSide:\"" + lastSide + "\""
                + ",leftUse:\"" + leftSlotUse + "\""
                + ",rightUse:\"" + rightSlotUse + "\""
                + ",leftBounds:" + boundsToCssJson(leftFreeformBounds())
                + ",rightBounds:" + boundsToCssJson(rightFreeformBounds())
                + ",safeLeft:" + cssPx(pageSideInsetPx(false))
                + ",safeRight:" + cssPx(pageSideInsetPx(true))
                + ",safeTop:" + (cssPx(pageTopInsetPx()) + 22f)
                + ",safeBottom:" + cssPx(dockReservePx())
                + ",launcherBottom:" + cssPx(chromeBottomGapPx())
                + ",uiMode:\"" + uiMode + "\""
                + "});}"
                + "else if(window.onAndroidLauncherPopup){window.onAndroidLauncherPopup(" + left + ");}"
                + "else if(window.__app&&window.__app.applyLauncherPopupLayout){"
                + "window.__app.applyLauncherPopupLayout(" + left + ");}"
                + "}catch(e){}";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void notifyMediaNowPlaying(JSONObject payload) {
        if (webView == null || payload == null) return;
        final String js = "try{if(window.onMediaNowPlaying){window.onMediaNowPlaying("
                + payload.toString() + ");}}catch(e){}";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void notifyMediaPosition(long positionMs) {
        if (webView == null) return;
        final String js = "try{if(window.onMediaPosition){window.onMediaPosition("
                + positionMs + ");}}catch(e){}";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private boolean hasOverlayWindow() {
        return (activePopupPackage != null && !activePopupPackage.isEmpty())
                || (activeMediaPackage != null && !activeMediaPackage.isEmpty());
    }

    /**
     * While a freeform slot is open, do not take window focus. Taps on the 3D
     * scene still reach our views, but the system will not raise this task over
     * the floating app (which is what hides it and replays the open animation).
     */
    private void applyLauncherFocusPolicy() {
        if (getWindow() == null) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp == null) return;
        int flags = lp.flags;
        if (hasOverlayWindow() && !chromeOnTop) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        // A focused freeform window cannot hide the OEM rail (Android 9). The
        // rail coming back normally re-insets us and shoves the layout right.
        // LAYOUT_NO_LIMITS keeps this window at the full display under the rail
        // — only while a slot is open; with focus it would hide the rail/header.
        if (hasOverlayWindow()) {
            flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        } else {
            flags &= ~(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        }
        if (flags == lp.flags) return;
        lp.flags = flags;
        getWindow().setAttributes(lp);
        if (!hasOverlayWindow()) enterImmersiveMode();
        Log.w(TAG, hasOverlayWindow()
                ? "Launcher NOT_FOCUSABLE while freeform overlay is open"
                : "Launcher focusable");
        if (hasOverlayWindow()) startOverlayWatchdog();
        else stopOverlayWatchdog();
    }

    /**
     * Tapping our fullscreen window makes the window manager focus this task and
     * raise it over the freeform windows. Android 16 honours FLAG_NOT_FOCUSABLE
     * here, but older ROMs (the MMI) raise the task anyway, so re-raise the
     * freeform tasks right after the tap is dispatched.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // ACTION_UP only: scheduling on DOWN too meant a tap (DOWN then UP) armed
        // the raise twice, and a drag armed it again on release.
        if (ev != null && hasOverlayWindow() && ev.getActionMasked() == MotionEvent.ACTION_UP) {
            // A tap on our own top chrome may be the layout button, whose
            // WebView click lands ~300 ms later and calls setChromeOnTop().
            // Give that click time to cancel the raise, or the popup would be
            // back on top before the menu it opened has rendered.
            raiseOverlayTasksSoon(ev.getY() <= chromeReservePx() ? 700 : 0);
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * One raise per gesture. Every moveTaskToFront re-composites the freeform
     * window, so the 60/250/600 ms retry ladder this replaced read as a flicker
     * on each tap. A late ROM reorder is covered by onWindowFocusChanged, which
     * schedules its own raise when our task actually comes forward.
     */
    private static final long RAISE_DELAY_MS = 60;
    /** Window in which a follow-up raise is treated as an echo of the last one. */
    private static final long RAISE_ECHO_COOLDOWN_MS = 350;
    private long lastRaiseCompletedMs;
    private int lastRaisedTaskId = -1;
    /** Largest left inset ever reported — the nav rail's width while it was up. */
    private int widestNavInsetPx;
    private int loggedNavReservePx = -1;

    /**
     * While the viewer's own menu is open its window must win the z-order, so we
     * stop re-raising the popups and raise our task instead. The freeform windows
     * stay alive behind us — they used to be closed outright to reveal the menu.
     */
    private void applyChromeOnTop(boolean onTop) {
        if (chromeOnTop == onTop) return;
        chromeOnTop = onTop;
        Log.w(TAG, "Chrome on top: " + onTop);
        // Focusable again while the menu is up, so the tap that opened it also
        // keeps our task in front; NOT_FOCUSABLE is restored when it closes.
        applyLauncherFocusPolicy();
        if (chromeOnTopTimeout != null) mainHandler.removeCallbacks(chromeOnTopTimeout);
        if (onTop) {
            // Only cancel the pending popup raise from the tap that opened the menu.
            // Do NOT moveTaskToFront ourselves: that tap already brought this task
            // forward, and self-raising re-lays out the WebView right as the menu
            // renders, which wiped it off screen.
            if (raiseOverlayRunnable != null) mainHandler.removeCallbacks(raiseOverlayRunnable);
            // Failsafe: a menu closed by a path that never calls back would
            // otherwise leave the slots permanently un-raised.
            if (chromeOnTopTimeout == null) {
                chromeOnTopTimeout = () -> {
                    Log.w(TAG, "Chrome on top timed out; restoring slot raise");
                    applyChromeOnTop(false);
                };
            }
            mainHandler.postDelayed(chromeOnTopTimeout, 20000);
        } else {
            // Menu closed: this raise is wanted even if one just ran, so it must
            // not be mistaken for an echo.
            lastRaiseCompletedMs = 0;
            lastRaisedTaskId = -1;
            raiseOverlayTasksSoon();
        }
    }

    private void raiseOverlayTasksSoon() {
        raiseOverlayTasksSoon(0);
    }

    /** @param leadInMs pushes the whole ladder back so a chrome click can cancel it. */
    private void raiseOverlayTasksSoon(long leadInMs) {
        if (chromeOnTop) return;
        if (raiseOverlayRunnable == null) raiseOverlayRunnable = this::raiseOverlayTasksNow;
        mainHandler.removeCallbacks(raiseOverlayRunnable);
        // A slot covered by our own tap is not a closed slot — hold off the watchdog.
        overlayRaiseGraceUntilMs = android.os.SystemClock.uptimeMillis() + 1600 + leadInMs;
        mainHandler.postDelayed(raiseOverlayRunnable, RAISE_DELAY_MS + leadInMs);
    }

    private void raiseOverlayTasksNow() {
        if (chromeOnTop || !hasOverlayWindow()) return;
        // Repeat suppression lives in moveTaskToFrontNoAnim so every path that
        // reacts to the same tap collapses, per task rather than globally.
        raiseOverlayTask(activePopupPackage, activePopupTaskId, activePopupComponent, false);
        // Both slots can end up naming the same package; raising that one task
        // twice is a second re-composite for nothing.
        if (activeMediaPackage != null && !activeMediaPackage.equals(activePopupPackage)) {
            raiseOverlayTask(activeMediaPackage, activeMediaTaskId, activeMediaComponent, true);
        }
        // A slot covered by our own tap is not a closed slot — hold the watchdog off.
        overlayRaiseGraceUntilMs = android.os.SystemClock.uptimeMillis() + 1200;
    }

    private void raiseOverlayTask(String packageName, int knownTaskId,
            ComponentName component, boolean rightSlot) {
        if (packageName == null || packageName.isEmpty()) return;
        int taskId = knownTaskId >= 0 ? knownTaskId : findTaskIdForPackage(packageName);
        if (taskId >= 0) {
            if (rightSlot) activeMediaTaskId = taskId;
            else activePopupTaskId = taskId;
            // The cheap path: in-process, no IPC, no Shizuku, no relaunch.
            if (moveTaskToFrontNoAnim(taskId)) return;
            // Stale id (the app re-created its task): drop it and ask again.
            if (rightSlot) activeMediaTaskId = -1;
            else activePopupTaskId = -1;
        }
        requestTaskId(packageName, rightSlot);
        // Until an id arrives, re-start the activity in place. A foreground start
        // is always permitted and lands on the same task.
        restartIntoSlot(packageName, component, rightSlot);
    }

    /**
     * Ask Impulse to resolve this package's task id. One lookup per popup (or per
     * failed raise), never per tap — it costs Impulse a Shizuku shell round trip.
     */
    /**
     * Unforgeable proof of who we are, for Impulse's API gate. Only the system can
     * set a PendingIntent's creator, so the receiver reads our package off this
     * rather than trusting an extra we could have written ourselves.
     */
    private android.app.PendingIntent apiCallerToken() {
        if (apiCallerToken == null) {
            Intent noop = new Intent("com.havalh6.viewer.API_IDENTITY").setPackage(getPackageName());
            apiCallerToken = android.app.PendingIntent.getBroadcast(
                    this, 0, noop, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return apiCallerToken;
    }

    /**
     * Ask Impulse to force a package's window to a rect. Needed because this ROM
     * leaves stale bounds behind in two cases we cannot fix ourselves: the freeform
     * caption's maximize (mode changes to fullscreen, bounds do not), and apps that
     * ignore launch bounds and reopen at their remembered rect.
     */
    private void requestTaskBounds(String packageName, Rect bounds) {
        if (packageName == null || packageName.isEmpty() || bounds == null) return;
        try {
            Intent request = new Intent(ACTION_SET_TASK_BOUNDS);
            request.setPackage(IMPULSE_PACKAGE);
            request.putExtra(ImpulseApi.EXTRA_CALLER, apiCallerToken());
            request.putExtra("package", packageName);
            request.putExtra("l", bounds.left);
            request.putExtra("t", bounds.top);
            request.putExtra("r", bounds.right);
            request.putExtra("b", bounds.bottom);
            sendBroadcast(request);
            Log.w(TAG, "Bounds request " + packageName + " -> " + bounds);
        } catch (Exception e) {
            Log.w(TAG, "Bounds request failed for " + packageName + " ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private void requestTaskId(String packageName, boolean rightSlot) {
        if (packageName == null || packageName.isEmpty()) return;
        long now = android.os.SystemClock.uptimeMillis();
        long last = rightSlot ? lastRightTaskRequestMs : lastLeftTaskRequestMs;
        if (now - last < 1500) return;
        if (rightSlot) lastRightTaskRequestMs = now;
        else lastLeftTaskRequestMs = now;
        try {
            Intent request = new Intent(ACTION_RESOLVE_TASK);
            // Explicit: Android 8+ will not start a manifest receiver from an
            // implicit broadcast.
            request.setPackage(IMPULSE_PACKAGE);
            request.putExtra(ImpulseApi.EXTRA_CALLER, apiCallerToken());
            request.putExtra("package", packageName);
            request.putExtra("slot", rightSlot ? "right" : "left");
            sendBroadcast(request);
        } catch (Exception e) {
            Log.w(TAG, "Task id request failed for " + packageName + " ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Bring a freeform task back above this launcher without replaying its enter
     * animation. Needs REORDER_TASKS (declared) and a visible caller.
     */
    private boolean moveTaskToFrontNoAnim(int taskId) {
        if (taskId < 0) return false;
        // Several paths react to the same tap (touch, focus change, resume) and
        // each reorder re-composites the freeform window. Collapse repeats of the
        // same task: report success so callers don't fall back to a relaunch.
        long now = android.os.SystemClock.uptimeMillis();
        if (taskId == lastRaisedTaskId && now - lastRaiseCompletedMs < RAISE_ECHO_COOLDOWN_MS) {
            return true;
        }
        lastRaisedTaskId = taskId;
        lastRaiseCompletedMs = now;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            Bundle opts = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle();
            am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION, opts);
            Log.w(TAG, "Raise: moveTaskToFront task=" + taskId + " ok");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Raise: moveTaskToFront task=" + taskId + " refused ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return false;
        }
    }

    /** Same shape as the pin-bounds re-start: reorders the task and re-applies slot bounds. */
    private void restartIntoSlot(String packageName, ComponentName component, boolean rightSlot) {
        if (component == null || packageName == null || packageName.isEmpty()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSlotRestartMs < 900) return;
        lastSlotRestartMs = now;
        Rect bounds = rightSlot ? rightFreeformBounds() : leftFreeformBounds();
        try {
            Intent again = new Intent(Intent.ACTION_MAIN);
            again.addCategory(Intent.CATEGORY_LAUNCHER);
            again.setComponent(component);
            again.setPackage(packageName);
            again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(again, buildWindowOptions(WINDOWING_MODE_FREEFORM, bounds));
            Log.w(TAG, "Raise: re-started " + packageName + " into " + bounds);
        } catch (Exception e) {
            Log.w(TAG, "Raise: re-start failed for " + packageName, e);
        }
    }

    private void markOverlayLaunch() {
        overlayLaunchUntilMs = android.os.SystemClock.uptimeMillis() + 2000;
    }

    private boolean isOverlayLaunching() {
        return android.os.SystemClock.uptimeMillis() < overlayLaunchUntilMs;
    }

    /** Close any leftover task for this package so a slot never stacks two windows. */
    private void closeExistingTasksForPackage(String packageName) {
        int safety = 0;
        while (safety++ < 4) {
            int id = findTaskIdForPackage(packageName);
            if (id < 0) break;
            removeTaskById(id);
        }
    }

    private void dismissAllOverlays() {
        if (activePopupPackage != null && !activePopupPackage.isEmpty()) {
            dismissPopup(activePopupPackage);
        }
        if (activeMediaPackage != null && !activeMediaPackage.isEmpty()) {
            dismissMediaPopup(activeMediaPackage);
        }
    }

    /**
     * @return 1 visible, 0 gone/minimized, -1 unknown (can't see other apps' tasks)
     */
    private int overlayTaskState(String packageName, int knownTaskId) {
        if (packageName == null || packageName.isEmpty()) return 0;
        int dump = overlayVisibleInDumpsys(packageName);
        if (dump >= 0) return dump;
        int atm = overlayVisibleViaSystemService(packageName);
        if (atm >= 0) return atm;
        int imp = overlayImportance(packageName);
        if (imp >= 0) return imp;
        boolean sawForeignTask = false;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            @SuppressWarnings("deprecation")
            List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(32);
            if (tasks != null) {
                for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                    if (task == null) continue;
                    android.content.ComponentName top = task.topActivity;
                    android.content.ComponentName base = task.baseActivity;
                    String pkg = top != null ? top.getPackageName()
                            : (base != null ? base.getPackageName() : null);
                    if (pkg == null) continue;
                    if (!getPackageName().equals(pkg)) sawForeignTask = true;
                    boolean match = packageName.equals(pkg)
                            || (knownTaskId >= 0 && task.id == knownTaskId);
                    if (match) {
                        return runningTaskIsVisible(task) ? 1 : 0;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "overlayTaskState failed", e);
            return -1;
        }
        if (!sawForeignTask) return -1;
        return 0;
    }

    private int overlayImportance(String packageName) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null || procs.isEmpty()) return -1;
            boolean sawForeign = false;
            for (android.app.ActivityManager.RunningAppProcessInfo proc : procs) {
                if (proc.pkgList == null) continue;
                for (String pkg : proc.pkgList) {
                    if (pkg == null) continue;
                    if (!getPackageName().equals(pkg)) sawForeign = true;
                    if (packageName.equals(pkg)) {
                        return proc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                                ? 1 : 0;
                    }
                }
            }
            if (!sawForeign) return -1;
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 1 visible freeform, 0 gone/minimized, -1 dumpsys unavailable. */
    private int overlayVisibleInDumpsys(String packageName) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "activity", "activities")
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()), 8192);
            StringBuilder sb = new StringBuilder();
            String line;
            long deadline = System.currentTimeMillis() + 900;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 350000 || System.currentTimeMillis() > deadline) break;
            }
            try { process.destroy(); } catch (Exception ignored) {}
            String dump = sb.toString();
            if (dump.length() < 40 || dump.contains("Permission Denial")) return -1;
            boolean sawPkg = false;
            int idx = 0;
            while ((idx = dump.indexOf(packageName, idx)) >= 0) {
                int from = Math.max(0, idx - 400);
                int to = Math.min(dump.length(), idx + 600);
                String chunk = dump.substring(from, to);
                sawPkg = true;
                if (chunk.contains("visible=true")) return 1;
                idx += packageName.length();
            }
            if (!sawPkg) return -1;
            return 0;
        } catch (Exception e) {
            Log.w(TAG, "dumpsys overlay probe failed", e);
            return -1;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean runningTaskIsVisible(android.app.ActivityManager.RunningTaskInfo task) {
        try {
            java.lang.reflect.Field field =
                    android.app.ActivityManager.RunningTaskInfo.class.getField("isVisible");
            Object value = field.get(task);
            if (value instanceof Boolean) return (Boolean) value;
        } catch (Exception ignored) {}
        return true;
    }

    private void syncOverlaySlots() {
        syncOverlaySlots(false);
    }

    private void syncOverlaySlots(boolean assumeUnknownGone) {
        if (isOverlayLaunching()) return;
        if (android.os.SystemClock.uptimeMillis() < overlayRaiseGraceUntilMs) return;
        if (activePopupPackage != null && !activePopupPackage.isEmpty()) {
            int state = overlayTaskState(activePopupPackage, activePopupTaskId);
            if (state == 0 || (assumeUnknownGone && state < 0)) {
                Log.w(TAG, "Left freeform gone/minimized: " + activePopupPackage + " state=" + state);
                clearLeftSlot();
            }
        }
        if (activeMediaPackage != null && !activeMediaPackage.isEmpty()) {
            int state = overlayTaskState(activeMediaPackage, activeMediaTaskId);
            if (state == 0 || (assumeUnknownGone && state < 0)) {
                Log.w(TAG, "Right freeform gone/minimized: " + activeMediaPackage + " state=" + state);
                clearRightSlot();
            }
        }
    }

    private void startOverlayWatchdog() {
        mainHandler.removeCallbacks(overlayWatchdog);
        mainHandler.postDelayed(overlayWatchdog, 700);
    }

    private void stopOverlayWatchdog() {
        mainHandler.removeCallbacks(overlayWatchdog);
    }

    private void registerOverlayTaskListener() {
        allowHiddenApis();
        try {
            Class<?> listenerClass = Class.forName("android.app.ITaskStackListener");
            final java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("onTaskStackChanged".equals(name)
                        || "onTaskRemoved".equals(name)
                        || "onTaskRemovalStarted".equals(name)
                        || "onRecentTaskListUpdated".equals(name)
                        || "onTaskMovedToFront".equals(name)
                        || "onActivityUnpinned".equals(name)) {
                    mainHandler.post(this::syncOverlaySlots);
                }
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class) return 0;
                if (ret == long.class) return 0L;
                return null;
            };
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.getClassLoader(), new Class<?>[]{listenerClass}, handler);
            boolean registered = false;
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                am.getClass().getMethod("registerTaskStackListener", listenerClass)
                        .invoke(am, listener);
                registered = true;
            } catch (Exception ignored) {}
            if (!registered) {
                Object service = systemActivityService();
                if (service == null) return;
                service.getClass().getMethod("registerTaskStackListener", listenerClass)
                        .invoke(service, listener);
            }
            taskStackListener = listener;
            Log.w(TAG, "Registered TaskStackListener");
        } catch (Throwable t) {
            Log.w(TAG, "TaskStackListener unavailable", t);
        }
    }

    private void unregisterOverlayTaskListener() {
        if (taskStackListener == null) return;
        try {
            Class<?> listenerClass = Class.forName("android.app.ITaskStackListener");
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                am.getClass().getMethod("unregisterTaskStackListener", listenerClass)
                        .invoke(am, taskStackListener);
            } catch (Exception e) {
                Object service = systemActivityService();
                if (service != null) {
                    service.getClass().getMethod("unregisterTaskStackListener", listenerClass)
                            .invoke(service, taskStackListener);
                }
            }
        } catch (Throwable ignored) {}
        taskStackListener = null;
    }

    private void launchAppInLeftSlot(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty()) return;
            PackageManager pm = getPackageManager();
            Intent resolve = pm.getLaunchIntentForPackage(packageName);
            if (resolve == null || resolve.getComponent() == null) return;

            ensureFreeformSettings();

            // One left-slot app at a time. Re-tap closes.
            if (activePopupPackage != null && activePopupPackage.equals(packageName)) {
                dismissPopup(packageName);
                return;
            }
            if (activePopupPackage != null && !activePopupPackage.isEmpty()) {
                dismissPopup(activePopupPackage);
            }
            closeExistingTasksForPackage(packageName);

            activePopupPackage = packageName;
            activePopupTaskId = -1;
            activePopupComponent = resolve.getComponent();
            markOverlayLaunch();
            notifyViewerShellLayout();

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(resolve.getComponent());
            intent.setPackage(packageName);
            // Never use FLAG_ACTIVITY_LAUNCH_ADJACENT — forces split-screen on tablets/A12L+.
            // No MULTIPLE_TASK/NEW_DOCUMENT: those spawn extra freeform windows.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_TASK_ON_HOME);

            Bundle opts = buildWindowOptions(WINDOWING_MODE_FREEFORM, leftFreeformBounds());
            Log.w(TAG, "Freeform launch " + resolve.getComponent()
                    + " bounds=" + leftFreeformBounds()
                    + " windowingMode=" + opts.getInt(KEY_LAUNCH_WINDOWING_MODE, -1));
            startActivity(intent, opts);
            schedulePinPopupBounds(packageName, resolve.getComponent(), leftFreeformBounds(), false);
            // Let the task exist before asking Impulse to look it up, then have it
            // enforce the slot rect for apps that reopen at a remembered position.
            mainHandler.postDelayed(() -> requestTaskId(packageName, false), 700);
            final Rect leftRect = leftFreeformBounds();
            mainHandler.postDelayed(() -> requestTaskBounds(packageName, leftRect), 1200);
        } catch (Exception e) {
            Log.e(TAG, "Error launching app in left slot " + packageName, e);
        }
    }

    private void launchAppInRightSlot(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty()) return;
            PackageManager pm = getPackageManager();
            Intent resolve = pm.getLaunchIntentForPackage(packageName);
            if (resolve == null || resolve.getComponent() == null) return;

            ensureFreeformSettings();

            // Re-tap the same music app: dismiss only, return to idle now-playing.
            if (packageName.equals(activeMediaPackage)) {
                dismissMediaPopup(packageName);
                return;
            }
            if (activeMediaPackage != null && !activeMediaPackage.isEmpty()) {
                dismissMediaPopup(activeMediaPackage);
            }
            closeExistingTasksForPackage(packageName);

            activeMediaPackage = packageName;
            activeMediaTaskId = -1;
            activeMediaComponent = resolve.getComponent();
            markOverlayLaunch();
            notifyViewerShellLayout();

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(resolve.getComponent());
            intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_TASK_ON_HOME);

            Bundle opts = buildWindowOptions(WINDOWING_MODE_FREEFORM, rightFreeformBounds());
            Log.w(TAG, "Media freeform launch " + resolve.getComponent()
                    + " bounds=" + rightFreeformBounds());
            startActivity(intent, opts);
            schedulePinPopupBounds(packageName, resolve.getComponent(), rightFreeformBounds(), true);
            mainHandler.postDelayed(() -> requestTaskId(packageName, true), 700);
            final Rect rightRect = rightFreeformBounds();
            mainHandler.postDelayed(() -> requestTaskBounds(packageName, rightRect), 1200);
        } catch (Exception e) {
            Log.e(TAG, "Error launching app in right slot " + packageName, e);
        }
    }

    /**
     * Debug probe: does moveTaskToFront work on a foreign task with only
     * REORDER_TASKS, given an id we could not have discovered ourselves?
     * {@code adb shell am start -n com.havalh6.viewer/.MainActivity --ei raise_task_id <id>}
     */
    private void maybeRaiseTaskFromIntent(Intent intent) {
        if (intent == null) return;
        int taskId = intent.getIntExtra(EXTRA_RAISE_TASK_ID, -1);
        if (taskId < 0) return;
        intent.removeExtra(EXTRA_RAISE_TASK_ID);
        Log.w(TAG, "Probe: raising task " + taskId + " -> " + moveTaskToFrontNoAnim(taskId));
    }

    private void maybeLaunchFreeformFromIntent(Intent intent) {
        if (intent == null) return;
        String mode = intent.getStringExtra("ui_mode");
        if (mode != null && !mode.isEmpty()) {
            intent.removeExtra("ui_mode");
            if (UI_MODE_INSET.equals(mode) || UI_MODE_FLOATING.equals(mode)
                    || UI_MODE_FULL.equals(mode)) {
                uiMode = mode;
                Log.w(TAG, "UI mode: " + uiMode);
                saveShellPrefs();
                enterImmersiveMode();
                mainHandler.postDelayed(this::notifyViewerShellLayout, 250);
            }
        }
        maybeRaiseTaskFromIntent(intent);
        String pkg = intent.getStringExtra(EXTRA_LAUNCH_FREEFORM);
        if (pkg != null && !pkg.isEmpty()) {
            intent.removeExtra(EXTRA_LAUNCH_FREEFORM);
            launchAppForPackage(pkg, "");
        }
    }

    private void removeTaskById(int taskId) {
        if (taskId < 0) return;
        allowHiddenApis();
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            java.lang.reflect.Method remove = android.app.ActivityManager.class.getMethod("removeTask", int.class);
            remove.invoke(am, taskId);
            Log.w(TAG, "Removed task " + taskId);
            return;
        } catch (Exception e) {
            Log.w(TAG, "ActivityManager.removeTask failed", e);
        }
        try {
            Object service = systemActivityService();
            if (service == null) return;
            java.lang.reflect.Method remove = service.getClass().getMethod("removeTask", int.class);
            remove.invoke(service, taskId);
            Log.w(TAG, "Removed task " + taskId + " via system service");
        } catch (Exception e) {
            Log.w(TAG, "removeTask failed for " + taskId + " ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private void dismissPopup(String packageName) {
        String targetPkg = (packageName != null && !packageName.isEmpty())
                ? packageName : activePopupPackage;
        if (targetPkg == null || targetPkg.isEmpty()) return;
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);

        int taskId = activePopupTaskId;
        if (taskId < 0 || (packageName != null && !packageName.equals(activePopupPackage))) {
            taskId = findTaskIdForPackage(targetPkg);
        }
        removeTaskById(taskId);
        forceStopPackage(targetPkg);

        if (targetPkg.equals(activePopupPackage)) clearLeftSlot();
    }

    private void clearLeftSlot() {
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
        activePopupPackage = "";
        activePopupTaskId = -1;
        activePopupComponent = null;
        notifyViewerShellLayout();
    }

    /** Close the right freeform window but keep playback (no force-stop). */
    private void dismissMediaPopup(String packageName) {
        String targetPkg = (packageName != null && !packageName.isEmpty())
                ? packageName : activeMediaPackage;
        if (targetPkg == null || targetPkg.isEmpty()) return;
        if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);

        int taskId = activeMediaTaskId;
        if (taskId < 0 || (packageName != null && !packageName.equals(activeMediaPackage))) {
            taskId = findTaskIdForPackage(targetPkg);
        }
        removeTaskById(taskId);

        if (targetPkg.equals(activeMediaPackage)) clearRightSlot();
    }

    private void clearRightSlot() {
        if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);
        activeMediaPackage = "";
        activeMediaTaskId = -1;
        activeMediaComponent = null;
        notifyViewerShellLayout();
    }

    private void closePopupApp(String packageName) {
        try {
            if (packageName != null && packageName.equals(activeMediaPackage)) {
                dismissMediaPopup(packageName);
            } else {
                dismissPopup(packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing app " + packageName, e);
        }
    }

    private void maximizePopupApp(String packageName) {
        try {
            boolean media = packageName != null && packageName.equals(activeMediaPackage);
            String targetPkg = (packageName != null && !packageName.isEmpty())
                    ? packageName
                    : (media ? activeMediaPackage : activePopupPackage);
            if (targetPkg == null || targetPkg.isEmpty()) return;
            Intent resolve = getPackageManager().getLaunchIntentForPackage(targetPkg);
            if (resolve == null || resolve.getComponent() == null) return;

            if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
            if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(resolve.getComponent());
            intent.setPackage(targetPkg);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            Bundle opts = buildWindowOptions(WINDOWING_MODE_FULLSCREEN, FULLSCREEN_BOUNDS);
            startActivity(intent, opts);

            int taskId = media
                    ? (activeMediaTaskId >= 0 ? activeMediaTaskId : findTaskIdForPackage(targetPkg))
                    : (activePopupTaskId >= 0 ? activePopupTaskId : findTaskIdForPackage(targetPkg));
            if (taskId >= 0) resizeTaskToBounds(taskId, FULLSCREEN_BOUNDS);
            // resizeTaskToBounds is permission-blocked for us, so the bounds would
            // stay at the slot rect and the app would paint small inside a black
            // screen. Impulse has MANAGE_ACTIVITY_STACKS and does it for real.
            requestTaskBounds(targetPkg, FULLSCREEN_BOUNDS);

            if (targetPkg.equals(activePopupPackage)) {
                activePopupPackage = "";
                activePopupTaskId = -1;
                activePopupComponent = null;
            }
            if (targetPkg.equals(activeMediaPackage)) {
                activeMediaPackage = "";
                activeMediaTaskId = -1;
                activeMediaComponent = null;
            }
            notifyViewerShellLayout();
        } catch (Exception e) {
            Log.e(TAG, "Error maximizing app " + packageName, e);
        }
    }

    /**
     * OEM GWM / BeanTechs / Autolink apps: always Display 0 fullscreen, never a
     * freeform slot. Same windowing path as {@link #maximizePopupApp}.
     */
    private void launchAppFullscreen(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty()) return;
            PackageManager pm = getPackageManager();
            Intent resolve = pm.getLaunchIntentForPackage(packageName);
            if (resolve == null || resolve.getComponent() == null) return;

            if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
            if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);

            if (packageName.equals(activePopupPackage)) {
                activePopupPackage = "";
                activePopupTaskId = -1;
                activePopupComponent = null;
            }
            if (packageName.equals(activeMediaPackage)) {
                activeMediaPackage = "";
                activeMediaTaskId = -1;
                activeMediaComponent = null;
            }

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(resolve.getComponent());
            intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            Bundle opts = buildWindowOptions(WINDOWING_MODE_FULLSCREEN, FULLSCREEN_BOUNDS);
            Log.w(TAG, "Fullscreen launch " + resolve.getComponent());
            startActivity(intent, opts);
            requestTaskBounds(packageName, FULLSCREEN_BOUNDS);
            notifyViewerShellLayout();
        } catch (Exception e) {
            Log.e(TAG, "Error launching fullscreen " + packageName, e);
        }
    }

    /**
     * {@code com.android.car.settings} trampolines into the connectivity page on
     * this ROM. Open the stock Settings root activity instead, fullscreen.
     */
    private void launchAndroidSettingsRoot() {
        try {
            if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
            if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(
                    ANDROID_SETTINGS_PACKAGE, ANDROID_SETTINGS_ACTIVITY));
            intent.setPackage(ANDROID_SETTINGS_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Bundle opts = buildWindowOptions(WINDOWING_MODE_FULLSCREEN, FULLSCREEN_BOUNDS);
            Log.w(TAG, "Fullscreen Android Settings root");
            startActivity(intent, opts);
            requestTaskBounds(ANDROID_SETTINGS_PACKAGE, FULLSCREEN_BOUNDS);
            notifyViewerShellLayout();
        } catch (Exception e) {
            Log.e(TAG, "Error launching Android Settings root", e);
            launchAppFullscreen(ANDROID_SETTINGS_PACKAGE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // LAYOUT_NO_LIMITS / LAYOUT_IN_SCREEN are applied only while a freeform
        // slot is open — see applyLauncherFocusPolicy. With focus they would hide
        // the MMI rail and header, which is why they stay off here.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Before enterImmersiveMode below: the stored mode decides its flags.
        uiMode = readUiModePref();
        enterImmersiveMode();

        webView = new WebView(this);
        WebView.setWebContentsDebuggingEnabled(true);
        // Paint the WebView with the background the web layer last settled on,
        // so the gap before the page's first paint matches the app instead of
        // flashing a fixed light grey (see AppLauncherBridge.saveShellBackground).
        final int shellBg = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .getInt("shellBg", DEFAULT_SHELL_BG);
        // TRANSPARENT + LAYER_TYPE_NONE so the boot splash <video> can hole-punch
        // its hardware overlay through the page. LAYER_TYPE_HARDWARE draws the
        // WebView into an opaque FBO that covers the overlay: you hear the clip
        // and see black. Restored to HARDWARE in revealLauncherStrip() once the
        // splash has handed off to the 3D scene.
        webView.setBackgroundColor(0x00000000);
        webView.setLayerType(View.LAYER_TYPE_NONE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // Three.js loaders fetch packaged GLB/Draco files from the same file origin.
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        // false, so the boot splash (<video id="hv-splash-video"> in index.html)
        // can autoplay. Left at the default true, WebView refuses play(), paints
        // its own full-screen tap-to-play button over the splash overlay, and the
        // viewer never gets past it. The clip is muted and is the only media the
        // page ever plays, so nothing else is affected.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBlockNetworkImage(false);
        settings.setBlockNetworkLoads(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.addJavascriptInterface(new TelemetryBridge(), "TelemetryBridge");
        webView.addJavascriptInterface(new AppLauncherBridge(), "AppLauncherBridge");
        webView.addJavascriptInterface(new MediaBridge(), "MediaBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!ASSET_HOST.equals(uri.getHost()) || !uri.getPath().startsWith(ASSET_PREFIX)) {
                    return super.shouldInterceptRequest(view, request);
                }
                String assetPath = uri.getPath().substring(ASSET_PREFIX.length());
                try {
                    InputStream stream = getAssets().open(assetPath);
                    String mime = mimeType(assetPath);
                    String encoding = needsUtf8(assetPath) ? "UTF-8" : null;
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    // THREE.GLTFLoader / fetch() require CORS even for same-origin
                    // intercepts on some WebView builds.
                    headers.put("Access-Control-Allow-Origin", "*");
                    // <video> will not start on a body with no Content-Length and no
                    // Range support — Chromium leaves the element paused and paints
                    // its full-screen tap-to-play overlay. The splash MP4 is <1 MB,
                    // so buffer it and advertise the size. GLBs stay streamed.
                    if ("video/mp4".equals(mime)) {
                        byte[] bytes = readAllBytes(stream);
                        stream.close();
                        headers.put("Content-Length", Integer.toString(bytes.length));
                        headers.put("Accept-Ranges", "none");
                        stream = new java.io.ByteArrayInputStream(bytes);
                    }
                    return new WebResourceResponse(
                            mime, encoding, 200, "OK", headers, stream);
                } catch (IOException error) {
                    Log.e(TAG, "Missing packaged asset: " + assetPath, error);
                    return null;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "Page finished: " + url);
                notifyViewerShellLayout();
                // Media session starts when JS calls MediaBridge.onViewerReady()
                // after the model loader clears (avoids stalling WebGL startup).
                // Fallback if that signal never arrives:
                mainHandler.postDelayed(() -> {
                    try {
                        mediaNowPlaying.start();
                        mediaNowPlaying.pushNow();
                    } catch (Throwable t) {
                        Log.w(TAG, "mediaNowPlaying fallback start failed", t);
                    }
                }, 20000);
                view.evaluateJavascript(
                        "JSON.stringify({ready:document.readyState,title:document.title," +
                                "body:document.body&&document.body.innerText.slice(0,160)," +
                                "scripts:document.scripts.length,three:typeof THREE})",
                        value -> Log.i(TAG, "Page state: " + value));
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.e(TAG, "JS " + message.messageLevel() + " " + message.sourceId()
                        + ":" + message.lineNumber() + " " + message.message());
                return true;
            }

            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (RuntimeException error) {
                    pendingFiles = null;
                    return false;
                }
                return true;
            }
        });

        rootLayout = new FrameLayout(this);
        // AppTheme derives from Theme.Material.Light, so the window and this
        // container both default to a light background and would show through
        // ahead of (and around) the WebView. Match the shell colour too.
        rootLayout.setBackgroundColor(shellBg);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(shellBg));
        rootLayout.setLayoutParams(new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT));
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setupBootHud(rootLayout);
        setupSplashSkip(rootLayout);
        setupSplashFade(rootLayout);

        setupNativeLauncherUI(rootLayout);

        mediaNowPlaying.attach(this, new MediaNowPlaying.Callback() {
            @Override
            public void onUpdate(org.json.JSONObject payload) {
                notifyMediaNowPlaying(payload);
            }

            @Override
            public void onPosition(long positionMs) {
                notifyMediaPosition(positionMs);
            }
        });
        // Defer session listen until after first paint so a broken MediaSession
        // path cannot stall WebView startup / model loading.

        // Registered before the first load so car events broadcast while the
        // viewer is still booting land in telemetryCache, where the page picks
        // them up via TelemetryBridge.getCarData once the scene is ready.
        try {
            IntentFilter filter = new IntentFilter(ACTION_VEHICLE_EVENT_CHANGED);
            filter.addAction(ACTION_CAR_DATA_UPDATE);
            registerReceiver(telemetryReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "Car telemetry receiver not registered", e);
        }
        scheduleTelemetrySnapshotRequests();
        try {
            registerReceiver(taskResolvedReceiver, new IntentFilter(ACTION_TASK_RESOLVED));
        } catch (Exception e) {
            Log.w(TAG, "Task id receiver not registered", e);
        }
        try {
            IntentFilter pkgFilter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            pkgFilter.addDataScheme("package");
            registerReceiver(packageRemovedReceiver, pkgFilter);
        } catch (Exception e) {
            Log.w(TAG, "Package removed receiver not registered", e);
        }

        setContentView(rootLayout);
        navRailReservePx();  // logs the reserve once, for slot-geometry debugging
        // The rail comes and goes with focus, which re-insets our window. Without
        // this the page keeps its old side band and the whole layout double-shifts.
        // Layout changes catch it; an insets listener alone does not, because the
        // window is re-framed without a fresh insets dispatch.
        rootLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (l == ol && t == ot && r == or_ && b == ob) return;
            mainHandler.post(this::notifyViewerShellLayout);
        });
        if (savedInstanceState == null) {
            // `--ez nosplash true` on the launch intent appends ?...&nosplash so a
            // cold start can be timed without the boot clip. See HavalSplash.
            String url = VIEWER_URL;
            if (getIntent() != null && getIntent().getBooleanExtra("nosplash", false)) {
                url = url + "&nosplash";
            }
            webView.loadUrl(url);
        } else {
            webView.restoreState(savedInstanceState);
        }

        ensureFreeformSettings();
        loadShellPrefs();
        registerOverlayTaskListener();
        maybeLaunchFreeformFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeLaunchFreeformFromIntent(intent);
    }

    /**
     * A launcher icon that can smear along X while it moves.
     *
     * There is no directional blur available here: RenderEffect.createBlurEffect
     * is API 31+ (this ships targetSdk 28 against a WebView-91-era head unit) and
     * BlurMaskFilter is isotropic, so it would fuzz the icon evenly instead of
     * trailing it. Drawing the view several times along the direction of travel
     * with falling alpha gives a real directional smear on any API, and costs a
     * handful of extra draws for well under a second at boot.
     *
     * `trailPx` is the length of the smear; the reveal drives it from the
     * animation's instantaneous speed, so it stretches out at the start and is
     * gone by the time the icon settles.
     */
    private static class MotionTrailLayout extends android.widget.LinearLayout {
        /** Ghost copies behind the icon. 6 is smooth without banding at this size. */
        private static final int SAMPLES = 6;
        private float trailPx;

        MotionTrailLayout(Context context) {
            super(context);
            // The smear reaches outside the icon's own bounds; the parent row sets
            // clipChildren false so it isn't cut off at the item edge either.
            setClipChildren(false);
            setClipToPadding(false);
        }

        void setTrailPx(float px) {
            if (px == trailPx) return;
            trailPx = px;
            invalidate();
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            if (trailPx <= 0.5f) {
                super.dispatchDraw(canvas);
                return;
            }
            // Back to front: the furthest ghost is the faintest.
            for (int i = SAMPLES; i >= 1; i--) {
                float f = i / (float) SAMPLES;
                int alpha = (int) (150f * (1f - f));
                if (alpha < 3) continue;
                int save = canvas.saveLayerAlpha(
                        -trailPx, 0, getWidth(), getHeight(), alpha);
                canvas.translate(-trailPx * f, 0);
                super.dispatchDraw(canvas);
                canvas.restoreToCount(save);
            }
            super.dispatchDraw(canvas);
        }
    }

    /**
     * Bring the launcher icons in once the boot splash has handed off: each one
     * slides in from the left with a motion smear and an ease-out, 200ms apart,
     * leftmost first. Idempotent — only the first call animates.
     */
    private void revealLauncherStrip() {
        endSplashOverlay();
        if (launcherRevealed) return;
        launcherRevealed = true;
        mainHandler.removeCallbacks(launcherRevealFallback);
        if (stripContainer != null) stripContainer.setVisibility(View.VISIBLE);
        if (launcherItems.isEmpty()) return;

        float density = getResources().getDisplayMetrics().density;
        final float travelPx = 120f * density;
        final float maxTrailPx = 90f * density;

        // Rightmost icon leads and the sequence walks back toward the left edge.
        // Every icon still travels left-to-right into its slot — it is the ORDER
        // that is reversed, so the icon with furthest to go sets off first.
        // Skip GONE duplicates (recents already occupy a slot on the left).
        final List<MotionTrailLayout> visible = new ArrayList<>();
        for (MotionTrailLayout item : launcherItems) {
            if (item != null && item.getVisibility() == View.VISIBLE) visible.add(item);
        }
        final int count = visible.size();
        for (int i = 0; i < count; i++) {
            final MotionTrailLayout item = visible.get(i);
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(520);
            anim.setStartDelay((count - 1 - i) * 200L);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
            anim.addUpdateListener(a -> {
                float e = (Float) a.getAnimatedValue();
                item.setTranslationX(-travelPx * (1f - e));
                item.setAlpha(Math.min(1f, e * 1.6f));
                // Speed under a decelerate curve falls off as the icon arrives, so
                // tying the smear to (1 - e) makes it fade out with the movement.
                item.setTrailPx(maxTrailPx * (1f - e));
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    item.setTranslationX(0f);
                    item.setAlpha(1f);
                    item.setTrailPx(0f);
                }
            });
            anim.start();
        }
    }

    /**
     * Video overlay is gone. Do NOT switch the WebView to LAYER_TYPE_HARDWARE
     * here: that reallocates the compositor FBO and drops the WebGL context
     * created under LAYER_TYPE_NONE, which is exactly the black screen after
     * the last splash frame disappears. NONE is what the 3D canvas was born
     * on; leave it there.
     */
    private void endSplashOverlay() {
        if (splashOverlayEnded) return;
        splashOverlayEnded = true;
        hideBootHud();
        hideSplashSkip();
        if (webView != null) {
            final int shellBg = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                    .getInt("shellBg", DEFAULT_SHELL_BG);
            webView.setBackgroundColor(shellBg);
        }
    }

    /**
     * Last-resort splash teardown from the native side. JS fadeOut is supposed
     * to remove #hv-splash; this covers the case where that never runs.
     */
    private void dropSplashFromShell() {
        endSplashOverlay();
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){"
                        + "try{if(window.HavalSplash&&window.HavalSplash.fadeOut)window.HavalSplash.fadeOut();}catch(e){}"
                        + "var e=document.getElementById('hv-splash');"
                        + "if(e){e.style.display='none';if(e.parentNode)e.parentNode.removeChild(e);}"
                        + "var v=document.getElementById('hv-splash-video');"
                        + "if(v){try{v.pause();}catch(e){}try{v.removeAttribute('src');v.src='';v.load();}catch(e){}"
                        + "if(v.parentNode)v.parentNode.removeChild(v);}"
                        + "var a=window.__app;"
                        + "if(!a)return;"
                        + "try{a._introWaitingForLoader=false;a._pendingIntro=false;}catch(e){}"
                        + "try{if(a._forceViewerLayout)a._forceViewerLayout();}catch(e){}"
                        + "try{if(a._setCarSceneVisible)a._setCarSceneVisible(true);}catch(e){}"
                        + "try{if(a._onResize)a._onResize();}catch(e){}"
                        + "try{if(a.requestRender)a.requestRender(30);}catch(e){}"
                        + "try{if(a.renderOnce)a.renderOnce();}catch(e){}"
                        + "try{if(a._startIntroAnimation&&!a._introPlayed)a._startIntroAnimation();}catch(e){}"
                        + "})()",
                null);
    }

    /**
     * Last clip frame, shown as a real View so it can opacity-fade. The HTML
     * <video> is hole-punched and ignores CSS opacity.
     */
    private void setupSplashFade(FrameLayout root) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF000000);
        iv.setVisibility(View.GONE);
        iv.setClickable(false);
        iv.setFocusable(false);
        root.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        splashFadeView = iv;
        new Thread(this::preloadSplashHoldFrame, "splash-frame").start();
    }

    private void preloadSplashHoldFrame() {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            File tmp = new File(getCacheDir(), "app-splash.mp4");
            if (!tmp.exists() || tmp.length() == 0) {
                try (InputStream in = getAssets().open("www/assets/app-splash.mp4");
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            retriever.setDataSource(tmp.getAbsolutePath());
            long us = 15_000_000L;
            String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                try {
                    us = Math.max(0L, Long.parseLong(dur) - 80L) * 1000L;
                } catch (NumberFormatException ignored) {}
            }
            Bitmap bmp = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
            if (bmp != null) splashHoldFrame = bmp;
        } catch (Exception e) {
            Log.w(TAG, "Could not extract splash hold frame", e);
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Cover the hole-punched video with the last clip frame, then fade that
     * still out over the already-drawn 3D car.
     */
    private void beginSplashFade(int durationMs) {
        if (splashFadeStarted) return;
        splashFadeStarted = true;
        hideBootHud();
        hideSplashSkip();
        if (splashFadeView == null) {
            endSplashOverlay();
            return;
        }
        if (splashHoldFrame != null) {
            splashFadeView.setImageBitmap(splashHoldFrame);
            splashFadeView.setBackgroundColor(0xFF000000);
        } else {
            splashFadeView.setImageDrawable(null);
            splashFadeView.setBackgroundColor(0xFF000000);
        }
        splashFadeView.setAlpha(1f);
        splashFadeView.setVisibility(View.VISIBLE);
        splashFadeView.bringToFront();
        int ms = Math.max(200, durationMs);
        splashFadeView.animate()
                .alpha(0f)
                .setDuration(ms)
                .setStartDelay(48)
                .withEndAction(() -> {
                    splashFadeView.setVisibility(View.GONE);
                    splashFadeView.setImageDrawable(null);
                    endSplashOverlay();
                })
                .start();
    }

    /**
     * Load-progress chip above the WebView. The splash <video> hole-punches
     * through in-page HTML, so the percentage would otherwise vanish for the
     * whole clip + hold. Hidden when the splash hands off to the car.
     */
    private void setupBootHud(FrameLayout root) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.START);
        int pad = Math.round(8 * d);
        box.setPadding(pad, pad, pad, pad);
        box.setClickable(false);
        box.setFocusable(false);
        box.setElevation(24f * d);

        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("LOADING MODEL");
        label.setTextColor(0xFFE8F1F8);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        label.setLetterSpacing(0.18f);
        label.setShadowLayer(8f, 0, 1, 0xFF000000);

        android.widget.TextView pct = new android.widget.TextView(this);
        pct.setText("…");
        pct.setTextColor(0xFFE8F1F8);
        pct.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        pct.setTypeface(android.graphics.Typeface.MONOSPACE);
        pct.setShadowLayer(8f, 0, 1, 0xFF000000);
        android.widget.LinearLayout.LayoutParams pctLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        pctLp.topMargin = Math.round(4 * d);
        pct.setLayoutParams(pctLp);
        bootHudPct = pct;

        FrameLayout bar = new FrameLayout(this);
        bootHudBarWidthPx = Math.round(140 * d);
        android.widget.LinearLayout.LayoutParams barLp = new android.widget.LinearLayout.LayoutParams(
                bootHudBarWidthPx, Math.round(2 * d));
        barLp.topMargin = Math.round(8 * d);
        barLp.gravity = android.view.Gravity.START;
        bar.setLayoutParams(barLp);
        bar.setBackgroundColor(0x2EFFFFFF);
        View fill = new View(this);
        fill.setBackgroundColor(0xFF4FD6E8);
        fill.setLayoutParams(new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        bar.addView(fill);
        bootHudFill = fill;

        box.addView(label);
        bootHudTitle = label;
        box.addView(pct);
        box.addView(bar);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        // Left pillar of the 1920×720 clip, below the MMI clock / inside the
        // black bar so it does not sit on the car or the status icons.
        lp.topMargin = Math.round(48 * d);
        lp.leftMargin = Math.round(132 * d);
        root.addView(box, lp);
        bootHud = box;
    }

    private void showBootHud(int pct, String text, String title) {
        if (bootHud == null) return;
        bootHud.setVisibility(View.VISIBLE);
        if (bootHudTitle != null && title != null) bootHudTitle.setText(title);
        if (bootHudPct != null) bootHudPct.setText(text != null ? text : (pct + "%"));
        if (bootHudFill != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bootHudFill.getLayoutParams();
            lp.width = Math.round(bootHudBarWidthPx * (Math.max(0, Math.min(100, pct)) / 100f));
            bootHudFill.setLayoutParams(lp);
        }
    }

    private void hideBootHud() {
        if (bootHud != null) bootHud.setVisibility(View.GONE);
    }

    /**
     * SKIP sits in native chrome because the splash &lt;video&gt; hole-punches
     * through the WebView — an HTML button on the clip would be covered. The
     * page still has a matching control for desktop / nosplash.
     */
    private void setupSplashSkip(FrameLayout root) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.TextView skip = new android.widget.TextView(this);
        skip.setText("SKIP");
        skip.setTextColor(0xE8FFFFFF);
        skip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        skip.setLetterSpacing(0.16f);
        skip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int padH = Math.round(16 * d);
        int padV = Math.round(8 * d);
        skip.setPadding(padH, padV, padH, padV);
        skip.setBackgroundColor(0x6B000000);
        skip.setElevation(24f * d);
        skip.setClickable(true);
        skip.setFocusable(true);
        skip.setOnClickListener(v -> {
            if (webView == null) return;
            webView.evaluateJavascript(
                    "(function(){try{if(window.HavalSplash&&window.HavalSplash.skip)window.HavalSplash.skip();}catch(e){}})()",
                    null);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        lp.leftMargin = Math.round(14 * d);
        lp.bottomMargin = chromeBottomGapPx();
        root.addView(skip, lp);
        splashSkipBtn = skip;
    }

    private void hideSplashSkip() {
        if (splashSkipBtn != null) splashSkipBtn.setVisibility(View.GONE);
    }

    private void setupNativeLauncherUI(FrameLayout rootLayout) {
        loadRecentApps();
        loadHiddenApps();
        float density = getResources().getDisplayMetrics().density;
        int iconSizePx = Math.round(52 * density);
        int itemWidthPx = Math.round(78 * density);
        int marginBottomPx = launcherBottomGapPx();
        int fadeLengthPx = Math.round(72 * density);
        int stripHeightPx = Math.round(110 * density);
        int iconBottomGapPx = Math.round(10 * density);

        // Bottom icon strip — full width (media column sits above the dock band).
        FrameLayout strip = new FrameLayout(this);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, stripHeightPx);
        containerParams.gravity = android.view.Gravity.BOTTOM;
        containerParams.bottomMargin = marginBottomPx;
        strip.setLayoutParams(containerParams);
        strip.setBackgroundColor(0x00000000);
        strip.setClipChildren(false);
        // Hidden until the boot splash hands off — the launcher is not part of the
        // opening shot. revealLauncherStrip() brings it back, driven from the page
        // (AppLauncherBridge.revealLauncher) so it lands with the car, not before.
        strip.setVisibility(View.INVISIBLE);
        stripContainer = strip;

        android.widget.HorizontalScrollView scrollView = new android.widget.HorizontalScrollView(this) {
            @Override
            protected float getLeftFadingEdgeStrength() {
                return 1.0f;
            }

            @Override
            protected float getRightFadingEdgeStrength() {
                return 1.0f;
            }
        };
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setHorizontalFadingEdgeEnabled(true);
        scrollView.setFadingEdgeLength(fadeLengthPx);
        scrollView.setBackgroundColor(0x00000000);
        scrollView.setClipToPadding(false);

        android.widget.LinearLayout iconsLayout = new android.widget.LinearLayout(this);
        iconsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        iconsLayout.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        iconsLayout.setPadding(Math.round(24 * density), 0, Math.round(24 * density), iconBottomGapPx);
        // Let the reveal's motion smear draw past each item's own edges.
        iconsLayout.setClipChildren(false);
        scrollView.setClipChildren(false);

        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        java.util.Map<String, ResolveInfo> byPkg = new java.util.HashMap<>();
        if (apps != null) {
            for (ResolveInfo info : apps) {
                if (info == null || info.activityInfo == null) continue;
                byPkg.put(info.activityInfo.packageName, info);
            }
        }

        int gapPx = Math.round(28 * density);
        projectionItem = makeDockItem(itemWidthPx, iconSizePx, density);
        projectionItem.setVisibility(View.GONE);
        iconsLayout.addView(projectionItem);
        launcherItems.add(projectionItem);

        projectionGap = makeDockGap(gapPx);
        projectionGap.setVisibility(View.GONE);
        iconsLayout.addView(projectionGap);

        int pinnedShown = 0;
        for (int i = 0; i < PINNED_PACKAGES.length; i++) {
            String pkg = PINNED_PACKAGES[i];
            pinnedItems[i] = makeDockItem(itemWidthPx, iconSizePx, density);
            pinnedItems[i].setVisibility(View.GONE);
            iconsLayout.addView(pinnedItems[i]);
            launcherItems.add(pinnedItems[i]);
            if (isUserHidden(pkg)) continue;
            ResolveInfo info = byPkg.get(pkg);
            if (info == null) continue;
            String labelStr = pinnedLabel(pkg, pm, info);
            bindDockItem(pinnedItems[i], iconForLauncherApp(pm, info), labelStr,
                    v -> launchAppForPackage(pkg, labelStr), pkg);
            pinnedShown++;
            Log.w(TAG, "Launcher pinned " + pkg + " | " + labelStr);
        }

        pinnedGap = makeDockGap(gapPx);
        pinnedGap.setVisibility(pinnedShown > 0 ? View.VISIBLE : View.GONE);
        iconsLayout.addView(pinnedGap);

        for (int i = 0; i < recentItems.length; i++) {
            recentItems[i] = makeDockItem(itemWidthPx, iconSizePx, density);
            recentItems[i].setVisibility(View.GONE);
            iconsLayout.addView(recentItems[i]);
            launcherItems.add(recentItems[i]);
        }

        recentsGap = makeDockGap(gapPx);
        recentsGap.setVisibility(View.GONE);
        iconsLayout.addView(recentsGap);

        seedRecentAppsFromSystem(apps);
        dockItemsByPackage.clear();

        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            if (skipInAppRow(pkg)) continue;

            String labelStr = launcherLabel(pm, info);
            Drawable iconDrawable = iconForLauncherApp(pm, info);
            MotionTrailLayout itemLayout = makeDockItem(itemWidthPx, iconSizePx, density);
            bindDockItem(itemLayout, iconDrawable, labelStr,
                    v -> launchAppForPackage(pkg, labelStr), pkg);
            iconsLayout.addView(itemLayout);
            launcherItems.add(itemLayout);
            dockItemsByPackage.put(pkg, itemLayout);
            Log.w(TAG, "Launcher shown " + pkg + " | " + labelStr
                    + (isGwmApp(pkg) ? " | gwm" : "")
                    + (CAR_SETTINGS_PACKAGE.equals(pkg) ? " | settings-root" : ""));
        }

        bindRecentSlots(pm, apps);

        projectionPresence = new ProjectionPresence(this, mainHandler, mediaNowPlaying);
        projectionPresence.start(kind -> bindProjectionSlot(kind));
        bindProjectionSlot(projectionPresence.current());

        scrollView.addView(iconsLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // When icons fit on screen, keep the row centered; when they overflow, allow scroll + edge fade.
        scrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int avail = scrollView.getWidth();
            if (avail <= 0) return;
            iconsLayout.setMinimumWidth(avail);
            iconsLayout.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        });

        strip.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // Icons live inside the same side band as the rest of the chrome.
        strip.setPadding(pageSideInsetPx(false), strip.getPaddingTop(),
                pageSideInsetPx(true), strip.getPaddingBottom());
        rootLayout.addView(strip);
        // 60s, not 30s: a cold start measured ~28s to the reveal on this unit
        // (15s clip + model load), and at 30s this backstop was beating the real
        // hand-off — the launcher popped in un-animated on every slow boot. This
        // is a "something is broken" net, so it must sit well clear of the worst
        // legitimate boot.
        mainHandler.postDelayed(launcherRevealFallback, 60000);

        // Invisible anchors matching freeform slots — used for clip-reveal launch placement.
        // Keep them behind the WebView and non-interactive so they never steal touches
        // from the media rail / orbit canvas.
        View anchor = new View(this);
        anchor.setBackgroundColor(0x00000000);
        anchor.setClickable(false);
        anchor.setFocusable(false);
        anchor.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams anchorLp = new FrameLayout.LayoutParams(
                LEFT_POPUP_BOUNDS.width(), LEFT_POPUP_BOUNDS.height());
        anchorLp.leftMargin = LEFT_POPUP_BOUNDS.left;
        anchorLp.topMargin = LEFT_POPUP_BOUNDS.top;
        rootLayout.addView(anchor, 0, anchorLp);
        launchAnchor = anchor;

        View mediaAnchor = new View(this);
        mediaAnchor.setBackgroundColor(0x00000000);
        mediaAnchor.setClickable(false);
        mediaAnchor.setFocusable(false);
        mediaAnchor.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams mediaAnchorLp = new FrameLayout.LayoutParams(
                RIGHT_APP_BOUNDS.width(), RIGHT_APP_BOUNDS.height());
        mediaAnchorLp.leftMargin = RIGHT_APP_BOUNDS.left;
        mediaAnchorLp.topMargin = RIGHT_APP_BOUNDS.top;
        rootLayout.addView(mediaAnchor, 0, mediaAnchorLp);
        mediaLaunchAnchor = mediaAnchor;
    }

    private MotionTrailLayout makeDockItem(int itemWidthPx, int iconSizePx, float density) {
        MotionTrailLayout item = new MotionTrailLayout(this);
        item.setOrientation(android.widget.LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER);
        item.setAlpha(0f);
        android.widget.LinearLayout.LayoutParams itemParams = new android.widget.LinearLayout.LayoutParams(
                itemWidthPx, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        itemParams.rightMargin = Math.round(12 * density);
        item.setLayoutParams(itemParams);
        item.setBackgroundColor(0x00000000);
        item.setClickable(true);
        item.setFocusable(true);

        android.widget.ImageView iconView = new android.widget.ImageView(this);
        iconView.setTag("icon");
        iconView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(iconSizePx, iconSizePx));

        android.widget.TextView labelView = new android.widget.TextView(this);
        android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Math.round(4 * density);
        labelView.setLayoutParams(labelParams);
        labelView.setTag("label");
        labelView.setTextSize(10f);
        labelView.setTextColor(0xFFFFFFFF);
        labelView.setGravity(android.view.Gravity.CENTER);
        labelView.setSingleLine(true);
        labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelView.setShadowLayer(4f, 0f, 2f, 0xFF000000);

        item.addView(iconView);
        item.addView(labelView);
        return item;
    }

    private View makeDockGap(int widthPx) {
        View gap = new View(this);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                widthPx, android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        gap.setLayoutParams(lp);
        gap.setClickable(false);
        gap.setFocusable(false);
        return gap;
    }

    private boolean isPinnedPackage(String packageName) {
        if (packageName == null) return false;
        for (String pinned : PINNED_PACKAGES) {
            if (pinned.equals(packageName)) return true;
        }
        return false;
    }

    private boolean isUserHidden(String packageName) {
        return packageName != null && hiddenPackages.contains(packageName);
    }

    /** Hidden from the scrolling app row (pinned apps live in their own slots). */
    private boolean skipInAppRow(String pkg) {
        return pkg.equals(getPackageName())
                || IGNORED_PACKAGES.contains(pkg)
                || isUserHidden(pkg)
                || isPinnedPackage(pkg)
                || ProjectionPresence.isProjectionPackage(pkg);
    }

    private boolean skipInRecents(String pkg) {
        return skipInAppRow(pkg);
    }

    private String pinnedLabel(String pkg, PackageManager pm, ResolveInfo info) {
        if ("com.beantechs.vehiclecenter".equals(pkg)) return "VehicleCenter";
        if ("com.beantechs.settings".equals(pkg)) return "Settings";
        if ("com.beantechs.energyassistant".equals(pkg)) return "Energy Assistant";
        if ("com.beantechs.launcher".equals(pkg)) return "Launcher";
        return launcherLabel(pm, info);
    }

    private boolean isGwmApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (packageName.equals(getPackageName())) return false;
        if (packageName.equals(HAVALSHISUKU_PACKAGE)) return false;
        if (ProjectionPresence.isProjectionPackage(packageName)) return false;
        String p = packageName.toLowerCase();
        return p.contains("beantech")
                || p.contains("autolink")
                || p.startsWith("com.gwm")
                || p.contains(".gwm.");
    }

    private String launcherLabel(PackageManager pm, ResolveInfo info) {
        if (info == null || info.activityInfo == null) return "";
        if ("com.beantechs.energyassistant".equalsIgnoreCase(info.activityInfo.packageName)) {
            return "Energy Assistant";
        }
        CharSequence label = info.loadLabel(pm);
        return label != null ? label.toString() : "";
    }

    private Drawable iconForLauncherApp(PackageManager pm, ResolveInfo info) {
        if (info == null || info.activityInfo == null) return null;
        String pkg = info.activityInfo.packageName;
        if ("com.beantechs.energyassistant".equalsIgnoreCase(pkg)) {
            try {
                Drawable energy = getDrawable(R.drawable.ic_energy_assistant);
                if (energy != null) return energy;
            } catch (Exception ignored) {}
        } else if (isGwmApp(pkg)) {
            try {
                Drawable gwm = getDrawable(R.drawable.ic_gwm);
                if (gwm != null) return gwm;
            } catch (Exception ignored) {}
        }
        return info.loadIcon(pm);
    }

    private void bindDockItem(MotionTrailLayout item, Drawable icon, String label,
            View.OnClickListener click) {
        bindDockItem(item, icon, label, click, null);
    }

    private void bindDockItem(MotionTrailLayout item, Drawable icon, String label,
            View.OnClickListener click, String editPackage) {
        if (item == null) return;
        android.widget.ImageView iv = (android.widget.ImageView) item.findViewWithTag("icon");
        android.widget.TextView tv = (android.widget.TextView) item.findViewWithTag("label");
        if (iv != null) iv.setImageDrawable(icon);
        if (tv != null) tv.setText(label != null ? label : "");
        item.setOnClickListener(click);
        item.setClickable(click != null);
        if (editPackage != null && !editPackage.isEmpty()) {
            item.setOnLongClickListener(v -> {
                showDockEditMenu(v, editPackage);
                return true;
            });
        } else {
            item.setOnLongClickListener(null);
        }
        item.setVisibility(View.VISIBLE);
        if (launcherRevealed) {
            item.setAlpha(1f);
            item.setTranslationX(0f);
            item.setTrailPx(0f);
        }
    }

    private void bindProjectionSlot(ProjectionPresence.Kind kind) {
        if (projectionItem == null) return;
        if (kind == null || kind == ProjectionPresence.Kind.NONE) {
            projectionItem.setVisibility(View.GONE);
            if (projectionGap != null) projectionGap.setVisibility(View.GONE);
            return;
        }
        Drawable icon = projectionPresence != null ? projectionPresence.iconFor(kind) : null;
        String label = projectionPresence != null ? projectionPresence.labelFor(kind) : "";
        bindDockItem(projectionItem, icon, label, v -> {
            if (projectionPresence != null) projectionPresence.launch(kind);
        });
        if (projectionGap != null) projectionGap.setVisibility(View.VISIBLE);
    }

    private void bindRecentSlots(PackageManager pm, List<ResolveInfo> apps) {
        java.util.Map<String, ResolveInfo> byPkg = new java.util.HashMap<>();
        if (apps != null) {
            for (ResolveInfo info : apps) {
                if (info == null || info.activityInfo == null) continue;
                byPkg.put(info.activityInfo.packageName, info);
            }
        }
        java.util.Set<String> shownRecents = new java.util.HashSet<>();
        int shown = 0;
        for (String pkg : recentPackages) {
            if (shown >= recentItems.length) break;
            if (skipInRecents(pkg)) continue;
            ResolveInfo info = byPkg.get(pkg);
            if (info == null) continue;
            String label = launcherLabel(pm, info);
            Drawable icon = iconForLauncherApp(pm, info);
            final String targetPkg = pkg;
            final String targetLabel = label;
            bindDockItem(recentItems[shown], icon, label,
                    v -> launchAppForPackage(targetPkg, targetLabel), targetPkg);
            shownRecents.add(pkg);
            shown++;
        }
        for (int i = shown; i < recentItems.length; i++) {
            if (recentItems[i] != null) recentItems[i].setVisibility(View.GONE);
        }
        if (recentsGap != null) recentsGap.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        hideRecentDuplicatesFromStrip(shownRecents);
    }

    private void loadHiddenApps() {
        hiddenPackages.clear();
        String raw = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE).getString("hidden_apps", "");
        if (raw == null || raw.isEmpty()) return;
        for (String pkg : raw.split(",")) {
            if (pkg == null) continue;
            pkg = pkg.trim();
            if (pkg.isEmpty()) continue;
            hiddenPackages.add(pkg);
        }
    }

    private void saveHiddenApps() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String pkg : hiddenPackages) {
            if (!first) sb.append(',');
            first = false;
            sb.append(pkg);
        }
        getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .edit()
                .putString("hidden_apps", sb.toString())
                .apply();
    }

    private boolean canUninstallPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(getPackageName()) || isPinnedPackage(pkg) || isGwmApp(pkg)) return false;
        try {
            android.content.pm.ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return (info.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void dismissDockEditMenu() {
        if (dockEditMenu == null) return;
        try {
            dockEditMenu.dismiss();
        } catch (Exception ignored) {}
        dockEditMenu = null;
    }

    private void showDockEditMenu(View anchor, String pkg) {
        if (anchor == null || pkg == null || pkg.isEmpty()) return;
        dismissDockEditMenu();
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xF2141820);
        box.setElevation(12f * d);
        int padH = Math.round(14 * d);
        int padV = Math.round(8 * d);
        box.setPadding(padH, padV, padH, padV);

        box.addView(makeDockMenuRow("Hide", () -> {
            dismissDockEditMenu();
            removeDockPackage(pkg, true);
        }));
        if (canUninstallPackage(pkg)) {
            box.addView(makeDockMenuRow("Uninstall", () -> {
                dismissDockEditMenu();
                try {
                    Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Uninstall failed for " + pkg, e);
                }
            }));
        }

        android.widget.PopupWindow popup = new android.widget.PopupWindow(
                box,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        popup.setOnDismissListener(() -> {
            if (dockEditMenu == popup) dockEditMenu = null;
        });
        dockEditMenu = popup;
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int xOff = (anchor.getWidth() - box.getMeasuredWidth()) / 2;
        int yOff = -(box.getMeasuredHeight() + anchor.getHeight() + Math.round(8 * d));
        popup.showAsDropDown(anchor, xOff, yOff);
    }

    private android.widget.TextView makeDockMenuRow(String title, Runnable action) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.TextView row = new android.widget.TextView(this);
        row.setText(title);
        row.setTextColor(0xFFFFFFFF);
        row.setTextSize(13f);
        row.setPadding(Math.round(6 * d), Math.round(8 * d), Math.round(6 * d), Math.round(8 * d));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void removeDockPackage(String pkg, boolean persistHide) {
        if (pkg == null || pkg.isEmpty()) return;
        if (persistHide) {
            hiddenPackages.add(pkg);
            saveHiddenApps();
        }
        recentPackages.remove(pkg);
        saveRecentApps();
        MotionTrailLayout row = dockItemsByPackage.get(pkg);
        if (row != null) row.setVisibility(View.GONE);
        int pinnedVisible = 0;
        for (int i = 0; i < PINNED_PACKAGES.length; i++) {
            if (pinnedItems[i] == null) continue;
            if (PINNED_PACKAGES[i].equals(pkg) || isUserHidden(PINNED_PACKAGES[i])) {
                pinnedItems[i].setVisibility(View.GONE);
            } else if (pinnedItems[i].getVisibility() == View.VISIBLE) {
                pinnedVisible++;
            }
        }
        if (pinnedGap != null) {
            pinnedGap.setVisibility(pinnedVisible > 0 ? View.VISIBLE : View.GONE);
        }
        refreshRecentSlots();
    }

    /** Recents already occupy the left slots — drop the same package from the main row. */
    private void hideRecentDuplicatesFromStrip(java.util.Set<String> shownRecents) {
        for (java.util.Map.Entry<String, MotionTrailLayout> e : dockItemsByPackage.entrySet()) {
            MotionTrailLayout item = e.getValue();
            if (item == null) continue;
            boolean hide = isUserHidden(e.getKey())
                    || (shownRecents != null && shownRecents.contains(e.getKey()));
            if (hide) {
                item.setVisibility(View.GONE);
                continue;
            }
            item.setVisibility(View.VISIBLE);
            if (launcherRevealed) {
                item.setAlpha(1f);
                item.setTranslationX(0f);
                item.setTrailPx(0f);
            }
        }
    }

    private void refreshRecentSlots() {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        bindRecentSlots(pm, pm.queryIntentActivities(mainIntent, 0));
    }

    private void rememberRecentApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        if (skipInRecents(packageName)) return;
        recentPackages.remove(packageName);
        recentPackages.add(0, packageName);
        while (recentPackages.size() > 12) {
            recentPackages.remove(recentPackages.size() - 1);
        }
        saveRecentApps();
        refreshRecentSlots();
    }

    private void loadRecentApps() {
        recentPackages.clear();
        String raw = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE).getString("recent_apps", "");
        if (raw == null || raw.isEmpty()) return;
        for (String pkg : raw.split(",")) {
            if (pkg == null) continue;
            pkg = pkg.trim();
            if (pkg.isEmpty() || recentPackages.contains(pkg)) continue;
            recentPackages.add(pkg);
        }
    }

    private void saveRecentApps() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recentPackages.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(recentPackages.get(i));
        }
        getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .edit()
                .putString("recent_apps", sb.toString())
                .apply();
    }

    private void seedRecentAppsFromSystem(List<ResolveInfo> launcherApps) {
        if (!recentPackages.isEmpty()) return;
        java.util.Set<String> launchable = new java.util.HashSet<>();
        if (launcherApps != null) {
            for (ResolveInfo info : launcherApps) {
                if (info != null && info.activityInfo != null) {
                    launchable.add(info.activityInfo.packageName);
                }
            }
        }
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            @SuppressWarnings("deprecation")
            List<android.app.ActivityManager.RecentTaskInfo> recent =
                    am.getRecentTasks(16, android.app.ActivityManager.RECENT_WITH_EXCLUDED);
            if (recent == null) return;
            for (android.app.ActivityManager.RecentTaskInfo task : recent) {
                android.content.ComponentName real = recentTaskRealActivity(task);
                String pkg = real != null ? real.getPackageName() : null;
                if (pkg == null || !launchable.contains(pkg)) continue;
                if (skipInRecents(pkg)) continue;
                if (recentPackages.contains(pkg)) continue;
                recentPackages.add(pkg);
                if (recentPackages.size() >= 3) break;
            }
            if (!recentPackages.isEmpty()) saveRecentApps();
        } catch (Exception ignored) {}
    }

    /** minSdk 23 has no InputStream.readAllBytes(). */
    private static byte[] readAllBytes(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String mimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".glb")) return "model/gltf-binary";
        // The boot splash clip. Served as octet-stream, Chromium's media stack
        // will not pick a decoder for it and <video> fires an error.
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".hdr")) return "application/octet-stream";
        return "application/octet-stream";
    }

    private static boolean needsUtf8(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".html")
                || lower.endsWith(".js")
                || lower.endsWith(".css")
                || lower.endsWith(".json")
                || lower.endsWith(".svg");
    }

    /**
     * How the viewer coexists with the MMI's bars. Switch at runtime:
     * {@code adb shell am start -n com.havalh6.viewer/.MainActivity --es ui_mode <mode>}
     * <ul>
     *   <li>{@code inset} (default) — header visible, page laid out below it. The
     *       rail hides while we hold focus, but the OEM forces it back when
     *       something else does (Android Auto, a focused freeform popup).</li>
     *   <li>{@code floating} — header visible but drawn <em>over</em> the page: we
     *       lay out fullscreen without hiding the status bar. The background runs
     *       under the header; chrome still starts below it.</li>
     *   <li>{@code full} — YouTube's fullscreen set. Both bars gone; beats the
     *       Android Auto case, loses the header.</li>
     * </ul>
     */
    private static final String UI_MODE_INSET = "inset";
    private static final String UI_MODE_FLOATING = "floating";
    private static final String UI_MODE_FULL = "full";
    /**
     * Default is {@link #UI_MODE_FLOATING}: it is the only mode where the viewer's
     * background runs under the MMI's bars, so the transparent scrims show the
     * scene instead of black strips. Persisted in {@link #PREFS_SHELL}.
     */
    private String uiMode = UI_MODE_FLOATING;

    private String readUiModePref() {
        String stored = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .getString("uiMode", UI_MODE_FLOATING);
        if (UI_MODE_INSET.equals(stored) || UI_MODE_FLOATING.equals(stored)
                || UI_MODE_FULL.equals(stored)) {
            return stored;
        }
        return UI_MODE_FLOATING;
    }

    /** True when the MMI header is still drawn (every mode except {@code full}). */
    private boolean statusBarShown() {
        return !UI_MODE_FULL.equals(uiMode);
    }

    /** True when our window is laid out over the bars rather than inside them. */
    private boolean laidOutFullBleed() {
        return !UI_MODE_INSET.equals(uiMode);
    }

    private void enterImmersiveMode() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        if (laidOutFullBleed()) {
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        }
        if (UI_MODE_FULL.equals(uiMode)) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
            if (hasOverlayWindow()) {
                applyLauncherFocusPolicy();
                // Focus came back to us, so our task was just raised over the slots.
                raiseOverlayTasksSoon();
            }
            syncOverlaySlots(false);
        }
    }

    /**
     * Best-effort always-on-top after a task exists. Do not startActivity here:
     * that replays the freeform enter animation (slide up from the bottom).
     */
    private void keepOverlayTasksOnTop() {
        if (chromeOnTop || !hasOverlayWindow()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastOverlayRaiseMs < 250) return;
        lastOverlayRaiseMs = now;
        pinOverlayAlwaysOnTop(activePopupPackage, activePopupTaskId, false);
        pinOverlayAlwaysOnTop(activeMediaPackage, activeMediaTaskId, true);
    }

    private void pinOverlayAlwaysOnTop(String packageName, int knownTaskId, boolean rightSlot) {
        if (packageName == null || packageName.isEmpty()) return;
        int taskId = knownTaskId >= 0 ? knownTaskId : findTaskIdForPackage(packageName);
        if (taskId < 0) {
            requestTaskId(packageName, rightSlot);
            return;
        }
        if (rightSlot) activeMediaTaskId = taskId;
        else activePopupTaskId = taskId;
        // Real always-on-top needs a signature permission the MMI will not grant;
        // fall back to re-raising the task ourselves.
        if (!setTaskAlwaysOnTop(taskId, true)) moveTaskToFrontNoAnim(taskId);
    }

    private boolean setTaskAlwaysOnTop(int taskId, boolean alwaysOnTop) {
        if (taskId < 0) return false;
        if (!taskAlwaysOnTopSupported) return false;
        try {
            // Android 9 (the MMI) has no setTaskAlwaysOnTop at all — the method
            // scan below finds nothing and latches the fallback on.
            Object service = systemActivityService();
            if (service == null) {
                taskAlwaysOnTopSupported = false;
                return false;
            }
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method cand : service.getClass().getMethods()) {
                if (!"setTaskAlwaysOnTop".equals(cand.getName())) continue;
                Class<?>[] p = cand.getParameterTypes();
                if (p.length == 2 && p[0] == int.class && p[1] == boolean.class) {
                    m = cand;
                    break;
                }
            }
            if (m != null) {
                m.invoke(service, taskId, alwaysOnTop);
                Log.w(TAG, "setTaskAlwaysOnTop task=" + taskId + " " + alwaysOnTop);
                return true;
            }
            taskAlwaysOnTopSupported = false;
            Log.w(TAG, "setTaskAlwaysOnTop unavailable; using moveTaskToFront instead");
        } catch (Exception e) {
            taskAlwaysOnTopSupported = false;
            Log.w(TAG, "ATM.setTaskAlwaysOnTop rejected; using moveTaskToFront instead", e);
        }
        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFiles == null) return;
        Uri[] result = resultCode == RESULT_OK
                ? WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                : null;
        pendingFiles.onReceiveValue(result);
        pendingFiles = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        webView.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        dismissDockEditMenu();
        // Telemetry stays registered across pause: opening a freeform app pauses
        // this activity, and the car keeps sending door/light/speed events that
        // the still-visible 3D scene behind it has to follow. Unregistered in
        // onDestroy.
        // Keep the 3D surface alive while a freeform slot has focus.
        if (!hasOverlayWindow()) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        enterImmersiveMode();
        mediaNowPlaying.start();
        notifyViewerShellLayout();
        keepOverlayTasksOnTop();
        syncOverlaySlots(false);
        mainHandler.postDelayed(() -> syncOverlaySlots(true), 500);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations() || isOverlayLaunching()) return;
        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        // Freeform on top keeps our window visible. A fullscreen app covering
        // the MMI hides it — that's when we close both slots.
        if (decor != null && decor.getWindowVisibility() == View.VISIBLE) return;
        dismissAllOverlays();
    }

    @Override
    protected void onDestroy() {
        stopOverlayWatchdog();
        unregisterOverlayTaskListener();
        mediaNowPlaying.stop();
        dismissDockEditMenu();
        if (projectionPresence != null) projectionPresence.stop();
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
        if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);
        if (raiseOverlayRunnable != null) mainHandler.removeCallbacks(raiseOverlayRunnable);
        try {
            unregisterReceiver(telemetryReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(taskResolvedReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(packageRemovedReceiver);
        } catch (Exception ignored) {}
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
