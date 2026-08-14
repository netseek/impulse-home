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

    private Rect clampSlot(Rect r, Rect usable) {
        int top = Math.max(r.top, usable.top + chromeReservePx());
        int bottom = Math.min(r.bottom, usable.bottom - dockReservePx());
        if (bottom - top < 200) bottom = Math.min(usable.bottom, top + 200);
        return new Rect(
                Math.max(r.left, usable.left),
                top,
                Math.min(r.right, usable.right),
                bottom);
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
        // Slot rects are display-relative; the anchors live inside our window,
        // which the OEM bars have pushed in by the inset origin.
        Rect usable = usableDisplayRect();
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

    private String boundsToCssJson(Rect bounds) {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) density = 1f;
        // Slot rects are display-relative, but the page's origin is our window,
        // which the OEM bars inset. Sending raw display coords pushed every widget
        // board right and down by the inset and ran its bottom into the app dock.
        Rect r = toWindowRect(bounds, usableDisplayRect());
        return "{l:" + (r.left / density)
                + ",t:" + (r.top / density)
                + ",r:" + (r.right / density)
                + ",b:" + (r.bottom / density) + "}";
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
        if (hasOverlayWindow() && !chromeOnTop) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (flags == lp.flags) return;
        lp.flags = flags;
        getWindow().setAttributes(lp);
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
        if (ev != null && hasOverlayWindow()) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                // A tap on our own top chrome may be the layout button, whose
                // WebView click lands ~300 ms later and calls setChromeOnTop().
                // Give that click time to cancel the raise, or the popup would be
                // back on top before the menu it opened has rendered.
                raiseOverlayTasksSoon(ev.getY() <= chromeReservePx() ? 700 : 0);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * The ROM can reorder tasks well after the tap (and again when its animation
     * settles), so retry instead of raising once.
     */
    private static final long[] RAISE_DELAYS_MS = {60, 250, 600};

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
        for (long delay : RAISE_DELAYS_MS) {
            mainHandler.postDelayed(raiseOverlayRunnable, delay + leadInMs);
        }
    }

    private void raiseOverlayTasksNow() {
        if (chromeOnTop || !hasOverlayWindow()) return;
        raiseOverlayTask(activePopupPackage, activePopupTaskId, activePopupComponent, false);
        raiseOverlayTask(activeMediaPackage, activeMediaTaskId, activeMediaComponent, true);
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
            // Let the task exist before asking Impulse to look it up.
            mainHandler.postDelayed(() -> requestTaskId(packageName, false), 700);
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
        // No LAYOUT_NO_LIMITS / LAYOUT_IN_SCREEN: those let the window extend under
        // the MMI's own bars, which is what hid the left nav rail and top header.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        // +5px lifts the strip slightly above the OEM climate dock.
        int marginBottomPx = Math.round(40 * density) + 5;
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
                // Not immersive: the MMI's status header and left nav bar stay on
                // screen, so the viewer lives inside the remaining app area.
                // LAYOUT_STABLE keeps getRootWindowInsets() steady for slot maths.
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
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
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
        if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);
        if (raiseOverlayRunnable != null) mainHandler.removeCallbacks(raiseOverlayRunnable);
        try {
            unregisterReceiver(telemetryReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(taskResolvedReceiver);
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
