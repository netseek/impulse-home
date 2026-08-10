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
    private View popupControls;
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
            launchAppInLeftSlot(packageName);
        }

        @JavascriptInterface
        public void closeApp(String packageName) {
            closePopupApp(packageName);
        }

        @JavascriptInterface
        public void maximizeApp(String packageName) {
            maximizePopupApp(packageName);
        }
    }

    private String activePopupPackage = "";
    private int activePopupTaskId = -1;

    /**
     * Left freeform slot over our fullscreen launcher (not split-screen).
     * Right side reserved for future media UI.
     * Bounds are screen pixels for the Haval ~1920×720 panel.
     * <p>
     * Emulator note: stock AVDs ship with freeform off. Enable once:
     * {@code adb shell settings put global enable_freeform_support 1}
     * {@code adb shell settings put global force_resizable_activities 1}
     */
    private static final Rect LEFT_POPUP_BOUNDS = new Rect(135, 60, 640, 480);
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
        // Clip-reveal from the fixed left-slot anchor so the system prefers that screen region
        // (helps when an app remembers a previous freeform position).
        if (windowingMode == WINDOWING_MODE_FREEFORM && launchAnchor != null
                && launchAnchor.getWidth() > 0 && launchAnchor.getHeight() > 0) {
            options = ActivityOptions.makeClipRevealAnimation(
                    launchAnchor, 0, 0, launchAnchor.getWidth(), launchAnchor.getHeight());
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
        return bundle;
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
            if (tasks == null) return -1;
            for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null) continue;
                android.content.ComponentName top = task.topActivity;
                android.content.ComponentName base = task.baseActivity;
                if ((top != null && packageName.equals(top.getPackageName()))
                        || (base != null && packageName.equals(base.getPackageName()))) {
                    return task.id;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getRunningTasks failed", e);
        }
        return -1;
    }

    /** Best-effort pin of freeform task to {@link #LEFT_POPUP_BOUNDS}. */
    private boolean resizeTaskToBounds(int taskId, Rect bounds) {
        if (taskId < 0 || bounds == null) return false;
        allowHiddenApis();
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
            if (resize == null) return false;
            Class<?>[] p = resize.getParameterTypes();
            if (p.length == 2) {
                resize.invoke(service, taskId, new Rect(bounds));
            } else if (p.length >= 3 && p[2] == int.class) {
                resize.invoke(service, taskId, new Rect(bounds), 0);
            } else {
                return false;
            }
            Log.i(TAG, "Pinned task " + taskId + " to " + bounds);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "resizeTask failed for task " + taskId, e);
            return false;
        }
    }

    private void schedulePinPopupBounds(String packageName) {
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
        final int[] attempts = {0};
        pinBoundsRunnable = new Runnable() {
            @Override
            public void run() {
                if (packageName == null || !packageName.equals(activePopupPackage)) return;
                int taskId = findTaskIdForPackage(packageName);
                if (taskId >= 0) {
                    activePopupTaskId = taskId;
                    if (resizeTaskToBounds(taskId, LEFT_POPUP_BOUNDS)) return;
                }
                attempts[0]++;
                if (attempts[0] < 8) mainHandler.postDelayed(this, 250);
            }
        };
        mainHandler.postDelayed(pinBoundsRunnable, 200);
    }

    private void setPopupControlsVisible(boolean visible) {
        if (popupControls != null) {
            popupControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void launchAppInLeftSlot(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty()) return;
            PackageManager pm = getPackageManager();
            Intent resolve = pm.getLaunchIntentForPackage(packageName);
            if (resolve == null || resolve.getComponent() == null) return;

            ensureFreeformSettings();

            // Close the previous freeform window when switching apps.
            if (activePopupPackage != null && !activePopupPackage.isEmpty()
                    && !activePopupPackage.equals(packageName)) {
                dismissPopup(activePopupPackage);
            } else if (activePopupPackage != null && activePopupPackage.equals(packageName)) {
                dismissPopup(packageName);
            } else {
                forceStopPackage(packageName);
            }

            activePopupPackage = packageName;
            activePopupTaskId = -1;
            setPopupControlsVisible(true);

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(resolve.getComponent());
            intent.setPackage(packageName);
            // Never use FLAG_ACTIVITY_LAUNCH_ADJACENT — forces split-screen on tablets/A12L+.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);

            Bundle opts = buildWindowOptions(WINDOWING_MODE_FREEFORM, LEFT_POPUP_BOUNDS);
            Log.i(TAG, "Freeform launch " + resolve.getComponent()
                    + " bounds=" + LEFT_POPUP_BOUNDS
                    + " windowingMode=" + opts.getInt(KEY_LAUNCH_WINDOWING_MODE, -1));
            startActivity(intent, opts);
            schedulePinPopupBounds(packageName);
        } catch (Exception e) {
            Log.e(TAG, "Error launching app in left slot " + packageName, e);
        }
    }

    private void maybeLaunchFreeformFromIntent(Intent intent) {
        if (intent == null) return;
        String pkg = intent.getStringExtra(EXTRA_LAUNCH_FREEFORM);
        if (pkg != null && !pkg.isEmpty()) {
            intent.removeExtra(EXTRA_LAUNCH_FREEFORM);
            launchAppInLeftSlot(pkg);
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

        if (targetPkg.equals(activePopupPackage)) {
            activePopupPackage = "";
            activePopupTaskId = -1;
            setPopupControlsVisible(false);
        }
    }

    private void closePopupApp(String packageName) {
        try {
            dismissPopup(packageName);
        } catch (Exception e) {
            Log.e(TAG, "Error closing app " + packageName, e);
        }
    }

    private void maximizePopupApp(String packageName) {
        try {
            String targetPkg = (packageName != null && !packageName.isEmpty())
                    ? packageName : activePopupPackage;
            if (targetPkg == null || targetPkg.isEmpty()) return;
            Intent resolve = getPackageManager().getLaunchIntentForPackage(targetPkg);
            if (resolve == null || resolve.getComponent() == null) return;

            if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);

            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(resolve.getComponent());
            intent.setPackage(targetPkg);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            Bundle opts = buildWindowOptions(WINDOWING_MODE_FULLSCREEN, FULLSCREEN_BOUNDS);
            startActivity(intent, opts);

            // If we know the task, also force-resize to fullscreen.
            int taskId = activePopupTaskId >= 0 ? activePopupTaskId : findTaskIdForPackage(targetPkg);
            if (taskId >= 0) resizeTaskToBounds(taskId, FULLSCREEN_BOUNDS);

            activePopupPackage = "";
            activePopupTaskId = -1;
            setPopupControlsVisible(false);
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

        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT));
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setupNativeLauncherUI(rootLayout);

        setContentView(rootLayout);
        if (savedInstanceState == null) webView.loadUrl(VIEWER_URL);
        else webView.restoreState(savedInstanceState);

        ensureFreeformSettings();
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

        // Full-width bottom icon strip (right side reserved later for media management)
        FrameLayout stripContainer = new FrameLayout(this);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, stripHeightPx);
        containerParams.gravity = android.view.Gravity.BOTTOM;
        containerParams.bottomMargin = marginBottomPx;
        stripContainer.setLayoutParams(containerParams);
        stripContainer.setBackgroundColor(0x00000000);

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
            itemLayout.setOnClickListener(v -> launchAppInLeftSlot(targetPkg));

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

        stripContainer.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        rootLayout.addView(stripContainer);

        // Maximize / Close controls for the active left freeform popup.
        android.widget.LinearLayout controls = new android.widget.LinearLayout(this);
        controls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controls.setPadding(Math.round(8 * density), Math.round(6 * density),
                Math.round(8 * density), Math.round(6 * density));
        controls.setBackgroundColor(0xCC10151C);
        controls.setVisibility(View.GONE);

        android.widget.Button btnMax = new android.widget.Button(this);
        btnMax.setText("Maximize");
        btnMax.setTextSize(11f);
        btnMax.setAllCaps(false);
        btnMax.setTextColor(0xFF4FD6E8);
        btnMax.setBackgroundColor(0x334FD6E8);
        btnMax.setPadding(Math.round(14 * density), Math.round(6 * density),
                Math.round(14 * density), Math.round(6 * density));
        btnMax.setOnClickListener(v -> maximizePopupApp(null));

        android.widget.Button btnClose = new android.widget.Button(this);
        btnClose.setText("Close");
        btnClose.setTextSize(11f);
        btnClose.setAllCaps(false);
        btnClose.setTextColor(0xFFFFFFFF);
        btnClose.setBackgroundColor(0x55FF5555);
        btnClose.setPadding(Math.round(14 * density), Math.round(6 * density),
                Math.round(14 * density), Math.round(6 * density));
        android.widget.LinearLayout.LayoutParams closeLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = Math.round(8 * density);
        btnClose.setLayoutParams(closeLp);
        btnClose.setOnClickListener(v -> closePopupApp(null));

        controls.addView(btnMax);
        controls.addView(btnClose);

        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        // Sit just above the freeform slot, aligned to its right edge.
        controlsLp.leftMargin = Math.max(0, LEFT_POPUP_BOUNDS.right - Math.round(220 * density));
        controlsLp.topMargin = Math.max(8, LEFT_POPUP_BOUNDS.top - Math.round(48 * density));
        rootLayout.addView(controls, controlsLp);
        popupControls = controls;

        // Invisible anchor matching the freeform slot — used for clip-reveal launch placement.
        View anchor = new View(this);
        anchor.setBackgroundColor(0x00000000);
        anchor.setClickable(false);
        anchor.setFocusable(false);
        FrameLayout.LayoutParams anchorLp = new FrameLayout.LayoutParams(
                LEFT_POPUP_BOUNDS.width(), LEFT_POPUP_BOUNDS.height());
        anchorLp.leftMargin = LEFT_POPUP_BOUNDS.left;
        anchorLp.topMargin = LEFT_POPUP_BOUNDS.top;
        rootLayout.addView(anchor, anchorLp);
        launchAnchor = anchor;
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
        if (hasFocus) enterImmersiveMode();
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
        webView.onPause();
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
    }

    @Override
    protected void onDestroy() {
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
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
