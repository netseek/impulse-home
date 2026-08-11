package com.havalh6.viewer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.webkit.JavascriptInterface;
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
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final String TAG = "H6Viewer";
    private static final String ACTION_CAR_DATA_UPDATE = "br.com.redesurftank.havalshisuku.CAR_DATA_UPDATE";
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

    private final BroadcastReceiver telemetryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_CAR_DATA_UPDATE.equals(intent.getAction())) return;
            String key = intent.getStringExtra("key");
            String value = intent.getStringExtra("value");
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

    public class TelemetryBridge {
        @JavascriptInterface
        public String getCarData(String key) {
            String val = telemetryCache.get(key);
            return val != null ? val : "";
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

    private static final java.util.Set<String> IGNORED_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList(
        "com.beantechs.hvac",
        "com.beantechs.vehiclecenter",
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
        "com.android.vending"
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
                    if (pkg.equals(getPackageName()) || IGNORED_PACKAGES.contains(pkg)) continue;

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
    }

    private String activePopupPackage = "";
    private int activePopupTaskId = -1;
    private ComponentName activePopupComponent;
    private String activeMediaPackage = "";
    private int activeMediaTaskId = -1;
    private ComponentName activeMediaComponent;
    private long lastOverlayRaiseMs;
    private long overlayLaunchUntilMs;
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
        Rect d = displayRect();
        int dock = dockReservePx();
        if (SHELL_APPS.equals(shellMode)) {
            int inset = Math.max(6, Math.round(d.width() * 0.004f));
            int gap = Math.max(6, Math.round(d.width() * 0.004f));
            int top = Math.max(inset, Math.round(d.height() * 0.055f));
            return new Rect(inset, top, d.width() / 2 - gap / 2, d.height() - dock);
        }
        if (SHELL_APP_CAR.equals(shellMode)) {
            return new Rect(
                    Math.round(d.width() * 0.016f),
                    Math.round(d.height() * 0.11f),
                    Math.round(d.width() * 0.54f),
                    d.height() - dock);
        }
        return new Rect(LEFT_POPUP_BOUNDS);
    }

    private Rect rightFreeformBounds() {
        Rect d = displayRect();
        int dock = dockReservePx();
        if (SHELL_APPS.equals(shellMode)) {
            int inset = Math.max(6, Math.round(d.width() * 0.004f));
            int gap = Math.max(6, Math.round(d.width() * 0.004f));
            int top = Math.max(inset, Math.round(d.height() * 0.055f));
            return new Rect(d.width() / 2 + gap / 2, top, d.width() - inset, d.height() - dock);
        }
        return new Rect(RIGHT_APP_BOUNDS);
    }

    private Rect displayRect() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    private int dockReservePx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((110f + 40f + 8f) * density);
    }

    private void updateSlotAnchors() {
        applyAnchorRect(launchAnchor, leftFreeformBounds());
        applyAnchorRect(mediaLaunchAnchor, rightFreeformBounds());
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

    private String boundsToCssJson(Rect bounds) {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) density = 1f;
        return "{l:" + (bounds.left / density)
                + ",t:" + (bounds.top / density)
                + ",r:" + (bounds.right / density)
                + ",b:" + (bounds.bottom / density) + "}";
    }

    private void launchAppForPackage(String packageName, String label) {
        if (packageName == null || packageName.isEmpty()) return;
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
            Log.i(TAG, "No APP slot available for launch (widgets-only)");
            return;
        }
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
        loadSlotUsesForMode();
    }

    private void saveShellPrefs() {
        android.content.SharedPreferences.Editor ed = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE).edit();
        if (!SHELL_APPS.equals(shellMode)) ed.putString("mode", shellMode);
        ed.putString("launchSide", launchSidePref);
        ed.putBoolean("nextLeft", nextLaunchLeft);
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
        leftSlotUse = prefs.getString("slot_" + shellMode + "_left", "app");
        rightSlotUse = prefs.getString("slot_" + shellMode + "_right", "app");
        if (!"widgets".equals(leftSlotUse)) leftSlotUse = "app";
        if (!"widgets".equals(rightSlotUse)) rightSlotUse = "app";
    }

    private boolean slotAllowsApp(String side) {
        if ("right".equals(side)) {
            if (SHELL_APP_CAR.equals(shellMode)) return false;
            return !"widgets".equals(rightSlotUse);
        }
        return !"widgets".equals(leftSlotUse);
    }

    private void applySlotUse(String side, String use) {
        if (!"widgets".equals(use)) use = "app";
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

            if (!want.isEmpty()) {
                nextLaunchLeft = !"left".equals(want);
                saveShellPrefs();
            }
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
            int hiddenId = findTaskIdViaActivityTaskManager(packageName);
            if (hiddenId >= 0) return hiddenId;
        } catch (Exception e) {
            Log.w(TAG, "findTaskIdForPackage failed", e);
        }
        return -1;
    }

    /** 1 visible, 0 gone, -1 ATM tasks filtered/unavailable. */
    private int overlayVisibleViaAtm(String packageName) {
        allowHiddenApis();
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object instance = null;
            try {
                instance = atmClass.getMethod("getInstance").invoke(null);
            } catch (Exception ignored) {}
            if (instance == null) return -1;
            java.lang.reflect.Method getTasks = null;
            for (java.lang.reflect.Method cand : instance.getClass().getMethods()) {
                if ("getTasks".equals(cand.getName()) && cand.getParameterTypes().length >= 1) {
                    getTasks = cand;
                    break;
                }
            }
            if (getTasks == null) return -1;
            Class<?>[] p = getTasks.getParameterTypes();
            Object result;
            if (p.length == 1) result = getTasks.invoke(instance, 32);
            else if (p.length == 2 && p[1] == boolean.class) result = getTasks.invoke(instance, 32, false);
            else if (p.length >= 3) result = getTasks.invoke(instance, 32, false, false);
            else return -1;
            if (!(result instanceof List)) return -1;
            boolean sawForeign = false;
            for (Object item : (List<?>) result) {
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
        } catch (Exception e) {
            return -1;
        }
    }

    private int findTaskIdViaActivityTaskManager(String packageName) {
        allowHiddenApis();
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object instance = null;
            try {
                instance = atmClass.getMethod("getInstance").invoke(null);
            } catch (Exception ignored) {}
            if (instance == null) return -1;
            java.lang.reflect.Method getTasks = null;
            for (java.lang.reflect.Method cand : instance.getClass().getMethods()) {
                if ("getTasks".equals(cand.getName()) && cand.getParameterTypes().length >= 1) {
                    getTasks = cand;
                    break;
                }
            }
            if (getTasks == null) return -1;
            Class<?>[] p = getTasks.getParameterTypes();
            Object result;
            if (p.length == 1) result = getTasks.invoke(instance, 32);
            else if (p.length == 2 && p[1] == boolean.class) result = getTasks.invoke(instance, 32, false);
            else if (p.length >= 3) result = getTasks.invoke(instance, 32, false, false);
            else return -1;
            if (!(result instanceof List)) return -1;
            for (Object item : (List<?>) result) {
                if (item instanceof android.app.ActivityManager.RunningTaskInfo) {
                    int id = taskIdIfPackage((android.app.ActivityManager.RunningTaskInfo) item, packageName);
                    if (id >= 0) return id;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ATM.getTasks failed", e);
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
                Log.i(TAG, "Pinned task " + taskId + " via AM.resizeTask(2) " + copy);
                return true;
            } catch (NoSuchMethodException ignored) {}
            try {
                java.lang.reflect.Method m = android.app.ActivityManager.class.getMethod(
                        "resizeTask", int.class, Rect.class, int.class);
                m.invoke(am, taskId, copy, 0);
                Log.i(TAG, "Pinned task " + taskId + " via AM.resizeTask(3) " + copy);
                return true;
            } catch (NoSuchMethodException ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "AM.resizeTask failed for task " + taskId, e);
        }

        // Android 10+: ActivityTaskManager.getService().resizeTask
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            java.lang.reflect.Method getService = atmClass.getDeclaredMethod("getService");
            getService.setAccessible(true);
            Object service = getService.invoke(null);
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
                Log.i(TAG, "Pinned task " + taskId + " via ATM.resizeTask " + copy);
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
    private void notifyViewerShellLayout() {
        if (webView == null) return;
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
        if (hasOverlayWindow()) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (flags == lp.flags) return;
        lp.flags = flags;
        getWindow().setAttributes(lp);
        Log.i(TAG, hasOverlayWindow()
                ? "Launcher NOT_FOCUSABLE while freeform overlay is open"
                : "Launcher focusable");
        if (hasOverlayWindow()) startOverlayWatchdog();
        else stopOverlayWatchdog();
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
        int atm = overlayVisibleViaAtm(packageName);
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
        if (activePopupPackage != null && !activePopupPackage.isEmpty()) {
            int state = overlayTaskState(activePopupPackage, activePopupTaskId);
            if (state == 0 || (assumeUnknownGone && state < 0)) {
                Log.i(TAG, "Left freeform gone/minimized: " + activePopupPackage + " state=" + state);
                clearLeftSlot();
            }
        }
        if (activeMediaPackage != null && !activeMediaPackage.isEmpty()) {
            int state = overlayTaskState(activeMediaPackage, activeMediaTaskId);
            if (state == 0 || (assumeUnknownGone && state < 0)) {
                Log.i(TAG, "Right freeform gone/minimized: " + activeMediaPackage + " state=" + state);
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
                Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
                java.lang.reflect.Method getService = atmClass.getDeclaredMethod("getService");
                getService.setAccessible(true);
                Object service = getService.invoke(null);
                service.getClass().getMethod("registerTaskStackListener", listenerClass)
                        .invoke(service, listener);
            }
            taskStackListener = listener;
            Log.i(TAG, "Registered TaskStackListener");
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
                Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
                java.lang.reflect.Method getService = atmClass.getDeclaredMethod("getService");
                getService.setAccessible(true);
                Object service = getService.invoke(null);
                service.getClass().getMethod("unregisterTaskStackListener", listenerClass)
                        .invoke(service, taskStackListener);
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
            Log.i(TAG, "Freeform launch " + resolve.getComponent()
                    + " bounds=" + leftFreeformBounds()
                    + " windowingMode=" + opts.getInt(KEY_LAUNCH_WINDOWING_MODE, -1));
            startActivity(intent, opts);
            schedulePinPopupBounds(packageName, resolve.getComponent(), leftFreeformBounds(), false);
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
            Log.i(TAG, "Media freeform launch " + resolve.getComponent()
                    + " bounds=" + rightFreeformBounds());
            startActivity(intent, opts);
            schedulePinPopupBounds(packageName, resolve.getComponent(), rightFreeformBounds(), true);
        } catch (Exception e) {
            Log.e(TAG, "Error launching app in right slot " + packageName, e);
        }
    }

    private void maybeLaunchFreeformFromIntent(Intent intent) {
        if (intent == null) return;
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
            Log.i(TAG, "Removed task " + taskId);
            return;
        } catch (Exception e) {
            Log.w(TAG, "ActivityManager.removeTask failed", e);
        }
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            java.lang.reflect.Method getService = atmClass.getDeclaredMethod("getService");
            getService.setAccessible(true);
            Object service = getService.invoke(null);
            java.lang.reflect.Method remove = service.getClass().getMethod("removeTask", int.class);
            remove.invoke(service, taskId);
            Log.i(TAG, "ATM removed task " + taskId);
        } catch (Exception e) {
            Log.w(TAG, "ATM.removeTask failed for " + taskId, e);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        enterImmersiveMode();

        webView = new WebView(this);
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setBackgroundColor(0xffe7e7e7);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
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
        settings.setMediaPlaybackRequiresUserGesture(true);
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
        rootLayout.setLayoutParams(new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT));
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

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

        setContentView(rootLayout);
        if (savedInstanceState == null) webView.loadUrl(VIEWER_URL);
        else webView.restoreState(savedInstanceState);

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

    private void setupNativeLauncherUI(FrameLayout rootLayout) {
        float density = getResources().getDisplayMetrics().density;
        int iconSizePx = Math.round(52 * density);
        int itemWidthPx = Math.round(78 * density);
        int marginBottomPx = Math.round(40 * density);
        int fadeLengthPx = Math.round(72 * density);
        int stripHeightPx = Math.round(110 * density);

        // Bottom icon strip — full width (media column sits above the dock band).
        FrameLayout strip = new FrameLayout(this);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, stripHeightPx);
        containerParams.gravity = android.view.Gravity.BOTTOM;
        containerParams.bottomMargin = marginBottomPx;
        strip.setLayoutParams(containerParams);
        strip.setBackgroundColor(0x00000000);
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
        iconsLayout.setGravity(android.view.Gravity.CENTER);
        iconsLayout.setPadding(Math.round(24 * density), 4, Math.round(24 * density), 4);

        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);

        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || IGNORED_PACKAGES.contains(pkg)) continue;

            String labelStr = info.loadLabel(pm).toString();
            Drawable iconDrawable = info.loadIcon(pm);

            android.widget.LinearLayout itemLayout = new android.widget.LinearLayout(this);
            itemLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            itemLayout.setGravity(android.view.Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams itemParams = new android.widget.LinearLayout.LayoutParams(
                    itemWidthPx, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            itemParams.rightMargin = Math.round(12 * density);
            itemLayout.setLayoutParams(itemParams);
            itemLayout.setBackgroundColor(0x00000000);
            itemLayout.setClickable(true);
            itemLayout.setFocusable(true);

            android.widget.ImageView iconView = new android.widget.ImageView(this);
            iconView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(iconSizePx, iconSizePx));
            iconView.setImageDrawable(iconDrawable);

            android.widget.TextView labelView = new android.widget.TextView(this);
            android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = Math.round(4 * density);
            labelView.setLayoutParams(labelParams);
            labelView.setText(labelStr);
            labelView.setTextSize(10f);
            labelView.setTextColor(0xFFFFFFFF);
            labelView.setGravity(android.view.Gravity.CENTER);
            labelView.setSingleLine(true);
            labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labelView.setShadowLayer(4f, 0f, 2f, 0xFF000000);

            itemLayout.addView(iconView);
            itemLayout.addView(labelView);

            final String targetPkg = pkg;
            final String targetLabel = labelStr;
            itemLayout.setOnClickListener(v -> launchAppForPackage(targetPkg, targetLabel));

            iconsLayout.addView(itemLayout);
        }

        scrollView.addView(iconsLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // When icons fit on screen, keep the row centered; when they overflow, allow scroll + edge fade.
        scrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int avail = scrollView.getWidth();
            if (avail <= 0) return;
            iconsLayout.setMinimumWidth(avail);
            iconsLayout.setGravity(android.view.Gravity.CENTER);
        });

        strip.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        rootLayout.addView(strip);

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

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
            if (hasOverlayWindow()) applyLauncherFocusPolicy();
            syncOverlaySlots(false);
        }
    }

    /**
     * Best-effort always-on-top after a task exists. Do not startActivity here:
     * that replays the freeform enter animation (slide up from the bottom).
     */
    private void keepOverlayTasksOnTop() {
        if (!hasOverlayWindow()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastOverlayRaiseMs < 250) return;
        lastOverlayRaiseMs = now;
        pinOverlayAlwaysOnTop(activePopupPackage, activePopupTaskId, false);
        pinOverlayAlwaysOnTop(activeMediaPackage, activeMediaTaskId, true);
    }

    private void pinOverlayAlwaysOnTop(String packageName, int knownTaskId, boolean rightSlot) {
        if (packageName == null || packageName.isEmpty()) return;
        int taskId = knownTaskId >= 0 ? knownTaskId : findTaskIdForPackage(packageName);
        if (taskId < 0) return;
        if (rightSlot) activeMediaTaskId = taskId;
        else activePopupTaskId = taskId;
        setTaskAlwaysOnTop(taskId, true);
    }

    private void setTaskAlwaysOnTop(int taskId, boolean alwaysOnTop) {
        if (taskId < 0) return;
        allowHiddenApis();
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            java.lang.reflect.Method getService = atmClass.getDeclaredMethod("getService");
            getService.setAccessible(true);
            Object service = getService.invoke(null);
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
                Log.i(TAG, "setTaskAlwaysOnTop task=" + taskId + " " + alwaysOnTop);
            }
        } catch (Exception e) {
            Log.w(TAG, "ATM.setTaskAlwaysOnTop failed for " + taskId, e);
        }
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
        try {
            unregisterReceiver(telemetryReceiver);
        } catch (Exception ignored) {}
        // Keep the 3D surface alive while a freeform slot has focus.
        if (!hasOverlayWindow()) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            IntentFilter filter = new IntentFilter(ACTION_CAR_DATA_UPDATE);
            registerReceiver(telemetryReceiver, filter);
        } catch (Exception ignored) {}
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
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
        if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);
        try {
            unregisterReceiver(telemetryReceiver);
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
