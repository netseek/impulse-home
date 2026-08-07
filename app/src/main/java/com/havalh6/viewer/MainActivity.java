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

        private String activePopupPackage = "";

        @JavascriptInterface
        public void launchAppInPopup(String packageName) {
            launchAppInPopup(packageName, "left");
        }

        @JavascriptInterface
        public void launchAppInPopup(String packageName, String slot) {
            try {
                if (packageName != null && !packageName.isEmpty()) {
                    activePopupPackage = packageName;
                    Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (intent == null) return;

                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                    ActivityOptions options = ActivityOptions.makeBasic();

                    try {
                        java.lang.reflect.Method method = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                        method.invoke(options, 5); // 5 = WINDOWING_MODE_FREEFORM
                    } catch (Exception ignored) {}

                    try {
                        java.lang.reflect.Method methodOnTop = ActivityOptions.class.getMethod("setAlwaysOnTop", boolean.class);
                        methodOnTop.invoke(options, true);
                    } catch (Exception ignored) {}

                    // Freeform bounds: "right" -> (1275, 60) to (1780, 480) | "left" -> (135, 60) to (640, 480)
                    if ("right".equalsIgnoreCase(slot)) {
                        options.setLaunchBounds(new Rect(1275, 60, 1780, 480));
                    } else {
                        options.setLaunchBounds(new Rect(135, 60, 640, 480));
                    }
                    startActivity(intent, options.toBundle());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error launching app in popup " + packageName, e);
            }
        }

        @JavascriptInterface
        public void closeApp(String packageName) {
            try {
                String targetPkg = (packageName != null && !packageName.isEmpty()) ? packageName : activePopupPackage;
                if (targetPkg != null && !targetPkg.isEmpty()) {
                    android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) am.killBackgroundProcesses(targetPkg);
                    Runtime.getRuntime().exec("am force-stop " + targetPkg);
                    activePopupPackage = "";
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing app " + packageName, e);
            }
        }

        @JavascriptInterface
        public void maximizeApp(String packageName) {
            try {
                String targetPkg = (packageName != null && !packageName.isEmpty()) ? packageName : activePopupPackage;
                if (targetPkg != null && !targetPkg.isEmpty()) {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(targetPkg);
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        ActivityOptions options = ActivityOptions.makeBasic();
                        try {
                            java.lang.reflect.Method method = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                            method.invoke(options, 1); // 1 = WINDOWING_MODE_FULLSCREEN
                        } catch (Exception ignored) {}
                        options.setLaunchBounds(new Rect(0, 0, 1920, 720));
                        startActivity(intent, options.toBundle());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error maximizing app " + packageName, e);
            }
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
                    return new WebResourceResponse(mimeType(assetPath), null, stream);
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
    }

    private String activeNativeSlot = "left";

    private void setupNativeLauncherUI(FrameLayout rootLayout) {
        float density = getResources().getDisplayMetrics().density;
        int iconSizePx = Math.round(52 * density);
        int itemWidthPx = Math.round(78 * density);
        int marginLeftPx = Math.round(135 * density);
        int marginBottomPx = Math.round(40 * density);

        // Main container for native launcher at left: 135px, bottom: 40px
        android.widget.LinearLayout launcherContainer = new android.widget.LinearLayout(this);
        launcherContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                Math.round(505 * density), FrameLayout.LayoutParams.WRAP_CONTENT);
        containerParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.LEFT;
        containerParams.leftMargin = marginLeftPx;
        containerParams.bottomMargin = marginBottomPx;
        launcherContainer.setLayoutParams(containerParams);
        launcherContainer.setBackgroundColor(0x00000000); // 100% transparent - NO card background

        // Horizontal scroll view for floating icons
        android.widget.HorizontalScrollView scrollView = new android.widget.HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setBackgroundColor(0x00000000); // 100% transparent - NO card

        android.widget.LinearLayout iconsLayout = new android.widget.LinearLayout(this);
        iconsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        iconsLayout.setPadding(4, 4, 4, 4);

        // Load filtered apps
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);

        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || IGNORED_PACKAGES.contains(pkg)) continue;

            String labelStr = info.loadLabel(pm).toString();
            Drawable iconDrawable = info.loadIcon(pm);

            // Single App Item Container (Icons Only - 100% transparent background)
            android.widget.LinearLayout itemLayout = new android.widget.LinearLayout(this);
            itemLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            itemLayout.setGravity(android.view.Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams itemParams = new android.widget.LinearLayout.LayoutParams(
                    itemWidthPx, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            itemParams.rightMargin = Math.round(12 * density);
            itemLayout.setLayoutParams(itemParams);
            itemLayout.setBackgroundColor(0x00000000); // Transparent - NO icon card background

            // App Icon ImageView (Floating 52dp x 52dp)
            android.widget.ImageView iconView = new android.widget.ImageView(this);
            android.widget.LinearLayout.LayoutParams iconParams = new android.widget.LinearLayout.LayoutParams(iconSizePx, iconSizePx);
            iconView.setLayoutParams(iconParams);
            iconView.setImageDrawable(iconDrawable);

            // App Name TextView below icon
            android.widget.TextView labelView = new android.widget.TextView(this);
            android.widget.LinearLayout.LayoutParams labelParams = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = Math.round(4 * density);
            labelView.setLayoutParams(labelParams);
            labelView.setText(labelStr);
            labelView.setTextSize(10f);
            labelView.setTextColor(0xFFFFFFFF);
            labelView.setGravity(android.view.Gravity.CENTER);
            labelView.setSingleLine(true);
            labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labelView.setShadowLayer(4f, 0f, 2f, 0xFF000000); // High contrast text shadow

            itemLayout.addView(iconView);
            itemLayout.addView(labelView);

            final String targetPkg = pkg;
            itemLayout.setOnClickListener(v -> {
                AppLauncherBridge bridge = new AppLauncherBridge();
                bridge.launchAppInPopup(targetPkg, activeNativeSlot);
            });

            iconsLayout.addView(itemLayout);
        }

        scrollView.addView(iconsLayout);

        // Target Slot Selector Buttons Container (LEFT / RIGHT)
        android.widget.LinearLayout selectorsLayout = new android.widget.LinearLayout(this);
        selectorsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        selectorsLayout.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams selectorContainerParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        selectorContainerParams.topMargin = Math.round(6 * density);
        selectorsLayout.setLayoutParams(selectorContainerParams);

        android.widget.Button btnLeft = new android.widget.Button(this);
        btnLeft.setText("◧ LEFT");
        btnLeft.setTextSize(10f);
        btnLeft.setTextColor(0xFF4FD6E8);
        btnLeft.setBackgroundColor(0x444FD6E8);

        android.widget.Button btnRight = new android.widget.Button(this);
        btnRight.setText("RIGHT ◨");
        btnRight.setTextSize(10f);
        btnRight.setTextColor(0xBBFFFFFF);
        btnRight.setBackgroundColor(0x22FFFFFF);

        android.widget.LinearLayout.LayoutParams btnParamsL = new android.widget.LinearLayout.LayoutParams(Math.round(88 * density), Math.round(34 * density));
        btnParamsL.rightMargin = Math.round(12 * density);
        btnLeft.setLayoutParams(btnParamsL);

        android.widget.LinearLayout.LayoutParams btnParamsR = new android.widget.LinearLayout.LayoutParams(Math.round(88 * density), Math.round(34 * density));
        btnRight.setLayoutParams(btnParamsR);

        btnLeft.setOnClickListener(v -> {
            activeNativeSlot = "left";
            btnLeft.setTextColor(0xFF4FD6E8);
            btnLeft.setBackgroundColor(0x444FD6E8);
            btnRight.setTextColor(0xBBFFFFFF);
            btnRight.setBackgroundColor(0x22FFFFFF);
        });

        btnRight.setOnClickListener(v -> {
            activeNativeSlot = "right";
            btnRight.setTextColor(0xFF4FD6E8);
            btnRight.setBackgroundColor(0x444FD6E8);
            btnLeft.setTextColor(0xBBFFFFFF);
            btnLeft.setBackgroundColor(0x22FFFFFF);
        });

        selectorsLayout.addView(btnLeft);
        selectorsLayout.addView(btnRight);

        launcherContainer.addView(scrollView);
        launcherContainer.addView(selectorsLayout);

        rootLayout.addView(launcherContainer);
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
        return "application/octet-stream";
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
