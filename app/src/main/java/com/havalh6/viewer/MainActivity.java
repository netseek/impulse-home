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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import android.app.ActivityOptions;
import android.widget.FrameLayout;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.content.ComponentName;
import android.util.Base64;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

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

    /** Commands exposed to data-driven bottom cards. Keep this deliberately narrow. */
    /** MEDIA rail tile geometry. The artwork is square and bleeds to three edges. */
    private static final int MEDIA_CARD_W_DP = 380;
    private static final int MEDIA_CARD_H_DP = 124;
    /** The card's own corner radius, from makeFrostLayer. */
    private static final float MEDIA_CARD_RADIUS_DP = 16f;
    /** Inset for the placeholder glyph when the source published no artwork. */
    private static final int MEDIA_ART_GLYPH_PAD_DP = 32;
    private static final int MEDIA_BTN_DP = 40;
    /** Source-chip glyph (MEDIA's now-playing app, NAVIGATION's guiding app). */
    private static final float SOURCE_CHIP_ICON_DP = 30f;
    private static final int MEDIA_BTN_PRIMARY_DP = 48;

    private static final java.util.Set<String> BOTTOM_CARD_ACTIONS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "ac", "addWidget", "openDesktopStudio", "openClockSettings", "openClockCardSettings", "previousDesktop", "nextDesktop",
                    "toggleDockMode", "showLauncher", "showCards", "cycleWidgetTheme",
                    "toggleCenterFill", "configureWallpaper", "closePanel",
                    // Navigation-only commands implemented by the web shell. They do not
                    // invoke vehicle APIs from Android.
                    "openClimate", "openConsumption", "openNavigation", "openPower", "openRange",
                    "openTires", "openVehicleStatus", "openDriving", "openDrivingOnePedal",
                    "openMedia", "openMediaApp",
                    // Retained for native shells installed before the three mode
                    // tiles were unified into one Driving controls card.
                    "cycleDriveMode", "cyclePowerMode", "cycleRegenMode",
                    "openRoofControls"
            ));

    /** Names shared with Impulse's API gate. */
    private static final class ImpulseApi {
        static final String EXTRA_CALLER = "caller";
    }

    private android.app.PendingIntent apiCallerToken;
    /** Viewer → Impulse: write an allowlisted vehicle setting (MODES widget). */
    private static final String ACTION_UPDATE_CAR_DATA =
            "br.com.redesurftank.havalshisuku.ACTION_UPDATE_CAR_DATA";
    /** Viewer → Impulse: body commands (windows, sunroof, curtain, doors). */
    private static final String ACTION_VEHICLE_COMMAND =
            "br.com.redesurftank.havalshisuku.ACTION_VEHICLE_COMMAND";
    /** Vehicle commands that carry no value extra. */
    private static final java.util.Set<String> VEHICLE_COMMANDS_WITHOUT_VALUE =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "toggle_doors_all",
                    "toggle_door_fl",
                    "toggle_door_fr",
                    "toggle_door_rl",
                    "toggle_door_rr",
                    "toggle_windows",
                    "toggle_window_fl",
                    "toggle_window_fr",
                    "toggle_window_rl",
                    "toggle_window_rr",
                    "toggle_trunk",
                    "toggle_sunroof",
                    "toggle_curtain",
                    "open_windows",
                    "close_windows",
                    "open_sunroof",
                    "close_sunroof",
                    "open_curtain",
                    "close_curtain"
                    // Software mirror fold is a no-op on this MMI (see CLAUDE.md).
                    // Keep the names for when OEM virtual-SW fold actually actuates:
                    // "fold_mirrors", "unfold_mirrors", "toggle_mirrors"
            ));
    /** Vehicle commands that require a 0-100 opening level. */
    private static final java.util.Set<String> VEHICLE_LEVEL_COMMANDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "set_sunroof_level",
                    "set_curtain_level"
            ));
    /**
     * Mirror of Impulse's {@code CarDataWriteReceiver.WRITABLE_CAR_KEYS}. A key
     * missing here is rejected before the broadcast is even sent, so this list
     * being the shorter of the two is silently a feature gate: drive/EV/steer/
     * regen/ESP worked and everything else looked like "the bridge is broken".
     * Keep the two in sync -- Impulse's copy is the authority, and it rejects
     * anything not on its own list regardless of what we send.
     */
    private static final java.util.Set<String> WRITABLE_CAR_KEYS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "car.drive_setting.drive_mode",
                    "car.drive_setting.steering_wheel_assist_mode",
                    "car.drive_setting.esp_enable",
                    "car.ev_setting.power_model_config",
                    "car.ev_setting.power_reserve_config",
                    "car.ev_setting.charge_soc_target_config",
                    "car.ev_setting.energy_recovery_level",
                    "car.ev.setting.pedal_control_enable",
                    "car.hvac.power_mode",
                    "car.hvac.fan_speed",
                    "car.hvac.driver_temperature",
                    "car.hvac.cycle_mode",
                    "car.hvac.auto_enable",
                    "car.hvac.anion_enable"
            ));
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    /**
     * Bump whenever the packaged WebView bundle changes. Android System WebView
     * can retain an appassets response across a same-version debug reinstall,
     * otherwise leaving the native shell paired with a previous index.html.
     */
    private static final String VIEWER_ASSET_REVISION = "vehicle-console-v47-aa-tbt-gmaps";
    private static final String VIEWER_URL =
            "https://" + ASSET_HOST + ASSET_PREFIX + "www/index.html?android&assets="
                    + VIEWER_ASSET_REVISION;

    /** Demo telemetry is an emulator concern; a real head unit must fail visibly, never fabricate. */
    private static boolean isProbablyEmulator() {
        String fingerprint = String.valueOf(android.os.Build.FINGERPRINT).toLowerCase(java.util.Locale.US);
        String model = String.valueOf(android.os.Build.MODEL).toLowerCase(java.util.Locale.US);
        String product = String.valueOf(android.os.Build.PRODUCT).toLowerCase(java.util.Locale.US);
        return fingerprint.contains("generic") || fingerprint.contains("emulator")
                || fingerprint.contains("unknown") || model.contains("sdk_gphone")
                || model.contains("emulator") || model.contains("android sdk built for")
                || product.contains("sdk") || product.contains("emulator") || product.contains("simulator");
    }

    private static String viewerUrl() {
        return VIEWER_URL + (isProbablyEmulator() ? "&demo=1" : "&demo=0");
    }

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
            if (pendingProjectionKind != null && pendingProjectionPackages.contains(pkg)) {
                Log.w(TAG, "Impulse resolved projection " + pkg + " -> task " + taskId);
                mainHandler.post(() -> completeProjectionRaise(taskId, pkg));
                return;
            }
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

    /** Keep both the native rail and the WebView clock on the device clock. */
    private final BroadcastReceiver clockEnvironmentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (!Intent.ACTION_TIME_TICK.equals(action)
                    && !Intent.ACTION_TIME_CHANGED.equals(action)
                    && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                    && !Intent.ACTION_LOCALE_CHANGED.equals(action)) return;
            mainHandler.post(() -> {
                for (QuickClockCardView clock : quickClockCards.values()) clock.refreshNow();
                if (webView != null) {
                    webView.evaluateJavascript(
                            "window.dispatchEvent(new Event('h6-clock-environment'))", null);
                }
            });
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

    /**
     * Returns a canonical decimal vehicle level, or {@code null} when {@code value}
     * is not an unsigned base-10 integer in the supported 0-100 range.
     */
    private static String normalizeVehicleLevel(String value) {
        if (value == null || value.isEmpty()) return null;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') return null;
        }
        try {
            int level = Integer.parseInt(value);
            return level >= 0 && level <= 100 ? Integer.toString(level) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
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

        @JavascriptInterface
        public void invokeVehicleCommand(String command, String value) {
            if (command == null || (!VEHICLE_COMMANDS_WITHOUT_VALUE.contains(command)
                    && !VEHICLE_LEVEL_COMMANDS.contains(command))) {
                Log.w(TAG, "Blocked unsupported vehicle command: " + command);
                return;
            }
            final String safeCommand = command;
            final String safeValue;
            if (VEHICLE_COMMANDS_WITHOUT_VALUE.contains(safeCommand)) {
                if (value != null && !value.isEmpty()) {
                    Log.w(TAG, "Blocked vehicle command value for " + safeCommand
                            + ": command requires an empty value");
                    return;
                }
                safeValue = "";
            } else {
                safeValue = normalizeVehicleLevel(value);
                if (safeValue == null) {
                    Log.w(TAG, "Blocked vehicle command value for " + safeCommand
                            + ": expected a base-10 integer from 0 through 100");
                    return;
                }
            }
            mainHandler.post(() -> {
                try {
                    Intent request = new Intent(ACTION_VEHICLE_COMMAND);
                    request.setPackage(IMPULSE_PACKAGE);
                    request.putExtra(ImpulseApi.EXTRA_CALLER, apiCallerToken());
                    request.putExtra("command", safeCommand);
                    if (!safeValue.isEmpty()) request.putExtra("value", safeValue);
                    sendBroadcast(request);
                } catch (Exception e) {
                    Log.w(TAG, "invokeVehicleCommand failed for " + safeCommand
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
                // Here rather than at WebView setup: a permission dialog raised
                // during onCreate lands on top of the boot splash clip, which
                // holds for ~18-22 s. This fires once the car is on screen.
                ensurePlaceLocationPermission();
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
    /** Extra scrolling-row GWM stubs on emulator when OEM packages are absent. */
    private static final String[][] EMULATOR_EXTRA_GWM_STUBS = {
        {"com.beantechs.fake.mediacenter", "Media Center"},
        {"com.beantechs.fake.navigation", "Navegação GWM"},
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
        // Duplicate "Configurações" row — the pinned com.beantechs.settings
        // ("Configurações do Sistema") is the entry point we keep.
        "com.android.car.settings",
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
        public String getClockEnvironment() {
            JSONObject environment = new JSONObject();
            try {
                java.util.Locale locale = getResources().getConfiguration().locale;
                environment.put("locale", locale == null ? "en-US" : locale.toLanguageTag());
                environment.put("timeZone", java.util.TimeZone.getDefault().getID());
                environment.put("system24", android.text.format.DateFormat.is24HourFormat(MainActivity.this));
            } catch (Exception e) {
                Log.w(TAG, "getClockEnvironment failed", e);
            }
            return environment.toString();
        }

        /**
         * Receives the canonical SVG face after the WebView has rasterized it.
         * The sequence prevents an older asynchronous canvas conversion from
         * repainting a newer face; a fresh WebView revision resets that order.
         */
        @JavascriptInterface
        public void updateClockCardFace(String revision, int sequence, String dataUrl) {
            if (dataUrl == null || dataUrl.length() > 1200000) return;
            final Bitmap bitmap = decodeDataUrlBitmap(dataUrl);
            if (bitmap == null) return;
            final String safeRevision = revision == null ? "" : revision;
            mainHandler.post(() -> applyQuickClockFace(safeRevision, sequence, bitmap));
        }

        @JavascriptInterface
        public String getInstalledApps() {
            JSONArray appsArray = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
                java.util.Set<String> seen = new java.util.HashSet<>();

                if (apps != null) {
                    for (ResolveInfo info : apps) {
                        if (info == null || info.activityInfo == null) continue;
                        String pkg = info.activityInfo.packageName;
                        if (skipInAppsCatalog(pkg) || !seen.add(pkg)) continue;
                        appsArray.put(appsCatalogEntry(pm, info, pkg));
                    }
                }
                if (isEmulatorDevice()) {
                    for (String[] stub : EMULATOR_EXTRA_GWM_STUBS) {
                        String pkg = stub[0];
                        if (skipInAppsCatalog(pkg) || !seen.add(pkg)) continue;
                        appsArray.put(appsCatalogStubEntry(pkg, stub[1]));
                    }
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

        /**
         * Raise Android Auto / CarPlay without going through MediaCenter's
         * MAIN activity (that path skips the current track).
         */
        @JavascriptInterface
        public void launchProjection(String kind) {
            mainHandler.post(() -> {
                if ("AA".equalsIgnoreCase(kind) || "ANDROID_AUTO".equalsIgnoreCase(kind)) {
                    MainActivity.this.launchProjection(ProjectionPresence.Kind.ANDROID_AUTO);
                } else if ("CP".equalsIgnoreCase(kind) || "CARPLAY".equalsIgnoreCase(kind)) {
                    MainActivity.this.launchProjection(ProjectionPresence.Kind.CARPLAY);
                }
            });
        }

        /** Package icon as a data URL; empty when the package is not installed. */
        @JavascriptInterface
        public String getAppIcon(String packageName) {
            Bitmap bmp = iconBitmapForPackage(packageName);
            if (bmp == null) return "";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
            return "data:image/png;base64,"
                    + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
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

        /**
         * Saved shell layout, readable SYNCHRONOUSLY from page scripts.
         *
         * notifyViewerShellLayout() is an evaluateJavascript push that cannot
         * land before onPageFinished, and it postpones itself again until
         * rootLayout is measured. The viewer's React state is built long before
         * either, so it opened in the built-in default and visibly re-laid out
         * when the push finally arrived. This lets it start in the right one.
         */
        @JavascriptInterface
        public String getShellLayout() {
            JSONObject o = new JSONObject();
            try {
                o.put("mode", shellMode);
                o.put("splitRatio", splitRatio);
                o.put("launchSide", launchSidePref);
            } catch (JSONException e) {
                Log.w(TAG, "getShellLayout failed", e);
            }
            return o.toString();
        }

        @JavascriptInterface
        public void setLaunchSide(String side) {
            mainHandler.post(() -> applyLaunchSide(side));
        }

        /** Force freeform into a specific APP+APP / slot side (dual-pane launchers). */
        @JavascriptInterface
        public void launchAppInSlot(String packageName, String side) {
            mainHandler.post(() -> launchAppInSlotNative(packageName, side));
        }

        @JavascriptInterface
        public void setSplitRatio(String ratio) {
            mainHandler.post(() -> applySplitRatio(ratio));
        }

        @JavascriptInterface
        public void swapApps() {
            mainHandler.post(MainActivity.this::swapSlotApps);
        }

        /** Persist APP+APP mode + ratio + current left/right packages as boot default. */
        @JavascriptInterface
        public void saveAppsOnlyDefault() {
            mainHandler.post(MainActivity.this::saveAppsOnlyDefaultNow);
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

        /** A thumbnail of the desktop on screen; answered via __app.onDesktopSnapshot. */
        @JavascriptInterface
        public void captureDesktopSnapshot(String token, int width, int height) {
            mainHandler.post(() -> captureDesktopSnapshotNow(token, width, height));
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
         * Boot progress while the splash (or its held frame) is up. Progress is
         * 0–100; text/title are ignored — the UI is a 2px blue line above Impulse.
         * At 100 / PROCESSING the line stays full blue until hideBootProgress.
         */
        @JavascriptInterface
        public void setBootProgress(int progress, String text, String title) {
            final int pct = Math.max(0, Math.min(100, progress));
            runOnUiThread(() -> showBootHud(pct));
        }

        @JavascriptInterface
        public void hideBootProgress() {
            runOnUiThread(() -> hideBootHud());
        }

        /**
         * Viewer → shell: {@code "center"} swaps the bottom line for a subtle
         * spinner in the car gap (skip / intro ended while GLB still loading).
         * Any other value restores the bottom line.
         */
        @JavascriptInterface
        public void placeBootProgress(String where) {
            final String place = where;
            runOnUiThread(() -> layoutBootHud(place));
        }

        /**
         * Viewer → shell: live labels for the config strip (MODEL trim, PERF
         * tier, paint swatch, day/night). JSON object with keys model, perf,
         * paint, time, fps, xray.
         */
        @JavascriptInterface
        public void updateDockIndicators(String json) {
            final String raw = json;
            runOnUiThread(() -> applyDockIndicators(raw));
        }

        /** Viewer → shell: center gap fill ({@code car} | {@code wallpaper}). */
        @JavascriptInterface
        public void updateCenterFill(String json) {
            final String raw = json;
            runOnUiThread(() -> applyCenterFillIndicator(raw));
        }

        /**
         * Viewer → shell: CSS box of the desktop switcher, in WebView client
         * pixels. Native places a SYSTEM_ALERT_WINDOW proxy on that display
         * rect so taps in the MMI StatusBar (y=0..60) reach the WebView.
         */
        @JavascriptInterface
        public void reportDesktopSwitcherHit(float left, float top, float width,
                float height, boolean visible) {
            mainHandler.post(() -> updateDesktopSwitcherHitProxy(
                    left, top, width, height, visible));
        }

        /**
         * Fetch Bing daily-wallpaper JSON from native code. WebView fetch() to
         * bing.com is blocked on some builds; this uses the same HTTPS endpoint
         * as Windows Spotlight (HPImageArchive).
         */
        @JavascriptInterface
        public String fetchJson(String url) {
            if (url == null) return "";
            String u = url.trim();
            if (!u.startsWith("https://www.bing.com/") && !u.startsWith("https://bing.com/")) {
                return "";
            }
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; HavalH6Viewer/1.0)");
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return "";
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                in.close();
                return out.toString(StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                Log.w(TAG, "fetchJson failed", e);
                return "";
            } finally {
                if (conn != null) conn.disconnect();
            }
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
    /** Visual preview of the 60dp area reserved for Impulse's persistent bar. */
    private View impulseReserveBand;
    private View stripRow;
    /** Mode drawer (1×1 collapsed / 1×4 expanded) leading the launcher strip. */
    private android.widget.LinearLayout modeDrawer;
    private View modeCollapsedBtn;
    private View modeAppsBtn;
    private View modeLayoutBtn;
    private View modeConfigBtn;
    /** Direct Launcher/Cards switch in the expanded drawer. */
    private View modeSurfaceBtn;
    private android.widget.ImageView modeCollapsedIcon;
    private View appsScrollView;
    /** Alternate low-profile bottom surface; mutually exclusive with appsScrollView. */
    private View cardsScrollView;
    private View layoutContentRow;
    private View configContentScroll;
    private View contentHost;
    private View layoutAddWidgetChip;
    private View layoutCenterFillChip;
    private android.widget.ImageView layoutCenterFillIcon;
    private android.widget.TextView layoutCenterFillLabel;
    private android.widget.ImageView layoutCenterFillGear;
    private View layoutThemeChip;
    private android.widget.TextView layoutThemeLabel;
    private android.widget.TextView layoutTipLabel;
    private View layoutLauncherSurfaceChip;
    private View layoutCardsSurfaceChip;
    private android.widget.TextView activeDesktopTitle;
    private android.widget.TextView activeDesktopMeta;
    private android.widget.LinearLayout quickCardsRow;
    private final List<View> quickCardViews = new ArrayList<>();
    private final List<BottomCardDescriptor> bottomCards = new ArrayList<>();
    private boolean bottomCardsConfigured;
    private int bottomCardLimit;
    private android.widget.TextView quickClimateValue;
    private android.widget.TextView quickConsumptionValue;
    /** Value fields in web-configured cards, keyed by their stable card id. */
    private final java.util.Map<String, android.widget.TextView> quickCardValues =
            new java.util.HashMap<>();
    private final java.util.Map<String, android.widget.TextView> quickCardDetails =
            new java.util.HashMap<>();
    private final java.util.Map<String, QuickCardGraphicView> quickCardGraphics =
            new java.util.HashMap<>();
    /** Clock cards are self-scheduling: minute ticks do not rebuild the rail. */
    private final java.util.Map<String, QuickClockCardView> quickClockCards =
            new java.util.HashMap<>();
    /** Latest canonical WebView clock face, shared by every rebuilt rail card. */
    private Bitmap quickClockFaceBitmap;
    private String quickClockFaceRevision = "";
    private int quickClockFaceSequence = -1;
    /** Accent DEMO markers in web-configured card headers, keyed by card id. */
    private final java.util.Map<String, android.widget.TextView> quickCardDemoBadges =
            new java.util.HashMap<>();
    /** Card root by id, so a mode change can repaint the wash without a rebuild. */
    private final java.util.Map<String, View> quickCardHosts = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> lastDrivingWash = new java.util.HashMap<>();
    private android.widget.ImageView quickMediaArt;
    private boolean quickMediaHasArt;
    private android.widget.TextView quickMediaTitle;
    private android.widget.TextView quickMediaArtist;
    private android.widget.ImageView quickMediaPlayPause;
    private final java.util.List<android.widget.ImageView> quickMediaButtons = new java.util.ArrayList<>();
    private boolean quickMediaAvailable;
    private android.widget.ImageView quickMediaAppIcon;
    private View quickMediaAppRow;
    private SourceChip quickMediaChip;
    /** Source chips owned by a bottom card's header, keyed by card id. */
    private final java.util.Map<String, SourceChip> quickCardSourceChips =
            new java.util.HashMap<>();
    private boolean quickMediaCanLaunch;
    private QuickMediaBarsView quickMediaBars;
    /** Session-0 Visualizer probe for the MEDIA rail bars; null until first play. */
    private MediaAudioVisualizer mediaAudioViz;
    /** Log the missing RECORD_AUDIO grant once, not on every play/pause. */
    private boolean mediaVizPermissionLogged;
    private static final int REQ_PLACE_LOCATION = 7102;
    /** onViewerReady fires again on post-boot loads; ask for location only once. */
    private boolean placeLocationAsked;
    /**
     * The last now-playing payload, replayed after the rail is rebuilt.
     *
     * populateQuickCardsRow drops every media view and builds fresh ones, and
     * an accent change rebuilds the whole rail — so without this the card
     * reverts to "Nothing playing" with its transport greyed out and stays
     * that way until the player happens to publish an update.
     */
    private JSONObject lastMediaPayload;
    private String quickMediaPackage = "";
    private boolean quickMediaPlaying;
    private String dockSurfaceMode = DOCK_SURFACE_LAUNCHER;
    private boolean desktopStudioOpen;
    private String activeDesktopName = "Desktop";
    private int activeDesktopIndex;
    private int desktopCount = 1;
    // Muted cyan keeps the frost surfaces readable without the pale blue cast
    // that made the selected launcher plate look disconnected from the theme.
    private int dockAccentColor = 0xFF2AA7B7;
    /** {@code car} or {@code wallpaper} — mirrored from the viewer. */
    private String centerFillMode = "car";
    /** Config-strip tool glyphs keyed by cmd (camera, model, …) for live updates. */
    private final java.util.Map<String, android.widget.ImageView> dockToolIcons =
            new java.util.HashMap<>();
    private int dockToolGlyphPx;
    /** Mask source for the x-ray / powertrain dock icon (assets/icons/xray-engine.png). */
    private Bitmap dockXrayGlyphSrc;
    private String dockModelLabel = "PHEV34";
    private String dockPerfLabel = "OFF";
    private String dockPaintHex = "#F4F5F7";
    private String dockTimeMode = "auto";
    private String dockWidgetThemeMode = "dark";
    private String dockWidgetThemeEffective = "dark";
    private boolean dockUiLight = false;
    private boolean dockFpsOn = false;
    private boolean dockXrayOn = false;

    /**
     * One row of a card's quick menu. `command` is minted by the web side and
     * only replayed here — this class never composes one.
     */
    private static final class QuickMenuRow {
        final String label;
        final String command;
        final boolean selected;

        QuickMenuRow(String label, String command, boolean selected) {
            this.label = label;
            this.command = command;
            this.selected = selected;
        }
    }

    private static final class BottomCardDescriptor {
        final String id;
        final String title;
        final String value;
        final String action;
        final String primary;
        final String secondary;
        final String metricA;
        final String metricB;
        final int progress;
        /** Power-only topology key and independent SOC validity. */
        final String powerVariant;
        final boolean socKnown;
        final double powerSoc;
        /** Dynamic Tires semantics; deliberately excluded from structural comparison. */
        final String state;
        final String[] wheelStates;
        String clockFace = "panorama";
        String clockFormat = "24h";
        /**
         * Four "/"-separated corner pressures, FL/FR/RL/RR, already formatted
         * and unit-converted by the page. Empty when the card carries none.
         * <p>
         * The Status card needs its own field because {@link #tireReadings} used
         * to recover pressures by splitting {@link #primary} and {@link #metricA}
         * on the separator -- which works for the Tires card, whose payload puts
         * "2.5 / 2.5" there, and silently fails for Status, whose payload puts
         * summary prose in the same slots. That is why the Status card read
         * "unavailable" on all four corners while TPMS was live.
         */
        String tirePressures = "";
        String tireTemperatures = "";
        String tirePressureUnit = "";
        /** ENERGY card: the last seven days' distance, 0-100 each, oldest first. */
        int[] energyBars = new int[0];
        /** Status opening order: FL, FR, RL, RR, tailgate. */
        final String[] openingStates;
        /** Status restraint order: driver, front passenger, rear left/centre/right. */
        final String[] seatBeltStates;
        final int sunroofLevel;
        final int curtainLevel;
        final boolean demo;
        /**
         * Command for a tap on the graphic alone, empty when the icon is not a
         * control. The card body always runs {@link #action}; this is the quick
         * change that saves opening the popup for a one-step adjustment.
         */
        final String iconAction;
        /** Command for a long press anywhere on the card; empty when unused. */
        final String longAction;
        /** Flattened glyph path (absolute M/L/C/Z on a 24x24 grid); may be empty. */
        final String glyph;
        /** Short code drawn in place of a glyph (AWD's "4x4"); may be empty. */
        final String glyphText;
        /** Navigation / media source package; used to stamp the live app icon. */
        String appPackage = "";
        /** TBT remaining distance / duration / arrival; empty when not guiding. */
        String navRemaining = "";
        String navDuration = "";
        String navEta = "";
        /** Idle-only glance: city (below the neighbourhood in primary) and a
         * tiny "start navigation" hint. Both empty while a turn is showing. */
        String navIdleCity = "";
        String navIdleAction = "";
        final String clockHourFormat;
        final String dialMarks;
        final String splitPlates;
        final String dateSpineFormat;
        final String dateWording;
        /** Quick-menu rows; empty for a card whose body opens something directly. */
        final java.util.List<QuickMenuRow> menu;
        /** Rows for the ⋯ shown on this card in rail edit mode; empty when it has none. */
        java.util.List<QuickMenuRow> editMenu = java.util.Collections.emptyList();

        BottomCardDescriptor(String id, String title, String value, String action,
                String primary, String secondary, String metricA, String metricB, int progress,
                String state, String powerVariant, boolean socKnown, double powerSoc, String[] wheelStates, String[] openingStates,
                String[] seatBeltStates, int sunroofLevel, int curtainLevel, boolean demo,
                String iconAction, String longAction, String glyph, String glyphText,
                String clockFace, String clockHourFormat, String dialMarks, String splitPlates,
                String dateSpineFormat, String dateWording, java.util.List<QuickMenuRow> menu) {
            this.id = id;
            this.title = title;
            this.value = value;
            this.action = action;
            this.primary = primary;
            this.secondary = secondary;
            this.metricA = metricA;
            this.metricB = metricB;
            this.progress = Math.max(0, Math.min(100, progress));
            this.state = state;
            this.powerVariant = powerVariant == null ? "phev19" : powerVariant;
            this.socKnown = socKnown;
            this.powerSoc = powerSoc;
            this.wheelStates = wheelStates;
            this.openingStates = openingStates;
            this.seatBeltStates = seatBeltStates;
            this.sunroofLevel = Math.max(0, Math.min(100, sunroofLevel));
            this.curtainLevel = Math.max(0, Math.min(100, curtainLevel));
            this.demo = demo;
            this.iconAction = iconAction == null ? "" : iconAction;
            this.longAction = longAction == null ? "" : longAction;
            this.glyph = glyph == null ? "" : glyph;
            this.glyphText = glyphText == null ? "" : glyphText;
            this.clockFace = clockFace == null ? "panorama" : clockFace;
            this.clockFormat = "12".equals(clockHourFormat) ? "12h" : ("24".equals(clockHourFormat) ? "24h" : "system");
            this.clockHourFormat = clockHourFormat == null ? "system" : clockHourFormat;
            this.dialMarks = dialMarks == null ? "index" : dialMarks;
            this.splitPlates = splitPlates == null ? "frost" : splitPlates;
            this.dateSpineFormat = dateSpineFormat == null ? "month-name" : dateSpineFormat;
            this.dateWording = dateWording == null ? "short" : dateWording;
            this.menu = menu == null ? java.util.Collections.emptyList() : menu;
        }
    }

    /**
     * Self-contained native bottom-card clock. It owns one minute-boundary
     * callback and one cached snapshot; the WebView is never asked to re-render
     * its 3D scene merely because time advanced.
     */
    private final class QuickClockCardView extends View {
        private BottomCardDescriptor descriptor;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                        | android.graphics.Paint.FILTER_BITMAP_FLAG);
        private ClockSnapshot snapshot;
        private Bitmap renderedFace;
        private final Runnable minuteTick = new Runnable() {
            @Override public void run() {
                refreshNow();
                scheduleMinuteTick();
            }
        };

        QuickClockCardView(Context context, BottomCardDescriptor descriptor) {
            super(context);
            this.descriptor = descriptor;
            setWillNotDraw(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            refreshNow();
        }

        void setDescriptor(BottomCardDescriptor next) {
            descriptor = next;
            refreshNow();
        }

        void setRenderedFace(Bitmap next) {
            renderedFace = next;
            invalidate();
        }

        void refreshNow() {
            snapshot = ClockSnapshot.from(descriptor, getContext());
            invalidate();
        }

        private void scheduleMinuteTick() {
            mainHandler.removeCallbacks(minuteTick);
            long delay = 60000L - (System.currentTimeMillis() % 60000L) + 20L;
            mainHandler.postDelayed(minuteTick, delay);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            refreshNow();
            scheduleMinuteTick();
        }

        @Override protected void onDetachedFromWindow() {
            mainHandler.removeCallbacks(minuteTick);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0f || h <= 0f) return;
            if (renderedFace != null) {
                canvas.drawBitmap(renderedFace, null,
                        new android.graphics.RectF(0f, 0f, w, h), paint);
                return;
            }
            if (snapshot == null || descriptor == null) return;
            int strong = dockLabelColor();
            int muted = dockUiLight ? 0x99536171 : 0xB8B6C2CE;
            int accent = dockAccentColor;
            if ("meridian".equals(descriptor.clockFace)) drawMeridian(canvas, w, h, strong, muted, accent);
            else if ("split".equals(descriptor.clockFace)) drawSplit(canvas, w, h, strong, muted, accent);
            else if ("date-spine".equals(descriptor.clockFace)) drawDateSpine(canvas, w, h, strong, muted, accent);
            else drawPanorama(canvas, w, h, strong, muted, accent);
        }

        private void type(int color, float px, android.graphics.Paint.Align align, boolean medium) {
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(px);
            paint.setTextAlign(align);
            paint.setTypeface(android.graphics.Typeface.create(medium ? "sans-serif-medium" : "sans-serif",
                    android.graphics.Typeface.NORMAL));
        }

        private float fit(String value, float intended, float available) {
            float size = intended;
            while (size > 10f) {
                paint.setTextSize(size);
                if (paint.measureText(value) <= available) return size;
                size -= 1f;
            }
            return 10f;
        }

        private void drawPanorama(android.graphics.Canvas c, float w, float h, int strong, int muted, int accent) {
            // Mirror the rail Web Component: digital time above, small dial at
            // lower left, and the weekday/date occupying the lower right.
            type(strong, fit(snapshot.time, h * .48f, w * .78f), android.graphics.Paint.Align.LEFT, true);
            c.drawText(snapshot.time, w * .04f, h * .38f, paint);
            if (!snapshot.period.isEmpty()) {
                type(muted, h * .16f, android.graphics.Paint.Align.RIGHT, true);
                c.drawText(snapshot.period, w * .96f, h * .48f, paint);
            }
            type(muted, h * .12f, android.graphics.Paint.Align.LEFT, false);
            float dialR = Math.min(24f, h * .28f);
            drawDial(c, dialR + 8f, h - dialR - 4f, dialR, strong, muted, accent);
            c.drawText(snapshot.weekday, dialR * 2f + 20f, h * .78f, paint);
            c.drawText(snapshot.dateShort, dialR * 2f + 20f, h * .96f, paint);
        }

        private void drawMeridian(android.graphics.Canvas c, float w, float h, int strong, int muted, int accent) {
            float dialR = Math.min(h * .43f, w * .25f);
            drawDial(c, dialR + 8f, h * .50f, dialR, strong, muted, accent);
            float stackLeft = Math.max(dialR * 2f + 22f, w * .58f);
            float stackRight = w - 8f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(withAlpha(muted, 0x70));
            c.drawRect(stackLeft, h * .50f - .5f, stackRight, h * .50f + .5f, paint);
            float stackCenter = (stackLeft + stackRight) * .5f;
            type(strong, fit(snapshot.hour, h * .42f, stackRight - stackLeft), android.graphics.Paint.Align.CENTER, true);
            c.drawText(snapshot.hour, stackCenter, h * .43f, paint);
            type(strong, fit(snapshot.minute, h * .42f, stackRight - stackLeft), android.graphics.Paint.Align.CENTER, true);
            c.drawText(snapshot.minute, stackCenter, h * .82f, paint);
            // The Web Component places a small colon over the divider. Keep it
            // as a real glyph rather than relying on the line to imply time.
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(dockUiLight ? 0xFFE1E5EA : 0xFF0E141B);
            c.drawRect(stackCenter - h * .10f, h * .46f, stackCenter + h * .10f, h * .58f, paint);
            type(muted, h * .20f, android.graphics.Paint.Align.CENTER, false);
            c.drawText(":", stackCenter, h * .58f, paint);
            type(muted, h * .11f, android.graphics.Paint.Align.RIGHT, false);
            if (!snapshot.period.isEmpty()) c.drawText(snapshot.period, stackRight, h * .97f, paint);
        }

        private void drawSplit(android.graphics.Canvas c, float w, float h, int strong, int muted, int accent) {
            float gap = Math.max(18f, w * .075f), plateW = (w * .82f - gap) / 2f, plateH = h * .57f, x = w * .09f, y = h * .12f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor("flat".equals(descriptor.splitPlates) ? (dockUiLight ? 0x12536171 : 0x18FFFFFF) : (dockUiLight ? 0x21536171 : 0x28FFFFFF));
            c.drawRoundRect(new android.graphics.RectF(x, y, x + plateW, y + plateH), h * .09f, h * .09f, paint);
            c.drawRoundRect(new android.graphics.RectF(x + plateW + gap, y, x + plateW * 2f + gap, y + plateH), h * .09f, h * .09f, paint);
            type(strong, fit(snapshot.hour, plateH * .70f, plateW * .78f), android.graphics.Paint.Align.CENTER, true);
            c.drawText(snapshot.hour, x + plateW * .5f, y + plateH * .74f, paint);
            c.drawText(snapshot.minute, x + plateW + gap + plateW * .5f, y + plateH * .74f, paint);
            type(muted, plateH * .34f, android.graphics.Paint.Align.CENTER, false);
            c.drawText(":", x + plateW + gap * .5f, y + plateH * .63f, paint);
            type(muted, h * .12f, android.graphics.Paint.Align.CENTER, false);
            c.drawText(snapshot.weekday + " · " + snapshot.dateShort + (snapshot.period.isEmpty() ? "" : " · " + snapshot.period), w * .5f, h * .90f, paint);
        }

        private void drawDateSpine(android.graphics.Canvas c, float w, float h, int strong, int muted, int accent) {
            float band = w * .29f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(withAlpha(accent, dockUiLight ? 0x24 : 0x32));
            c.drawRoundRect(new android.graphics.RectF(0f, 0f, band, h), h * .10f, h * .10f, paint);
            type(strong, h * .17f, android.graphics.Paint.Align.CENTER, true);
            c.drawText(snapshot.spineTop, band * .5f, h * .33f, paint);
            type(strong, h * .29f, android.graphics.Paint.Align.CENTER, true);
            c.drawText(snapshot.day, band * .5f, h * .64f, paint);
            type(muted, h * .10f, android.graphics.Paint.Align.CENTER, false);
            c.drawText(snapshot.weekday, band * .5f, h * .84f, paint);
            paint.setColor(accent);
            c.drawRect(band * .40f, h * .94f, band * .60f, h * .98f, paint);
            type(strong, fit(snapshot.time, h * .52f, w - band - w * .12f), android.graphics.Paint.Align.LEFT, true);
            c.drawText(snapshot.time, band + w * .06f, h * .60f, paint);
            if (!snapshot.period.isEmpty()) {
                type(muted, h * .12f, android.graphics.Paint.Align.LEFT, true);
                c.drawText(snapshot.period, band + w * .07f, h * .80f, paint);
            }
        }

        private void drawDial(android.graphics.Canvas c, float cx, float cy, float r, int strong, int muted, int accent) {
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, r * .04f));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(muted);
            c.drawCircle(cx, cy, r, paint);
            if (!"plain".equals(descriptor.dialMarks)) {
                for (int i = 0; i < 12; i++) {
                    double a = Math.toRadians(i * 30d - 90d);
                    float outer = r * .88f, inner = r * (i % 3 == 0 ? .70f : .78f);
                    c.drawLine(cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                            cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer, paint);
                }
            }
            double minute = Math.toRadians(snapshot.minuteAngle - 90f);
            double hour = Math.toRadians(snapshot.hourAngle - 90f);
            paint.setColor(accent); paint.setStrokeWidth(Math.max(1.8f, r * .055f));
            c.drawLine(cx, cy, cx + (float) Math.cos(minute) * r * .70f, cy + (float) Math.sin(minute) * r * .70f, paint);
            paint.setColor(strong); paint.setStrokeWidth(Math.max(2.4f, r * .075f));
            c.drawLine(cx, cy, cx + (float) Math.cos(hour) * r * .48f, cy + (float) Math.sin(hour) * r * .48f, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(accent); c.drawCircle(cx, cy, Math.max(2f, r * .08f), paint);
        }
    }

    private static final class ClockSnapshot {
        final String time, hour, minute, period, weekday, dateShort, day, spineTop;
        final float minuteAngle, hourAngle;
        ClockSnapshot(String time, String hour, String minute, String period, String weekday,
                String dateShort, String day, String spineTop, float minuteAngle, float hourAngle) {
            this.time = time; this.hour = hour; this.minute = minute; this.period = period;
            this.weekday = weekday; this.dateShort = dateShort; this.day = day; this.spineTop = spineTop;
            this.minuteAngle = minuteAngle; this.hourAngle = hourAngle;
        }
        static ClockSnapshot from(BottomCardDescriptor d, Context context) {
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.util.Locale locale = context.getResources().getConfiguration().locale;
            int minute = now.get(java.util.Calendar.MINUTE);
            int hour24 = now.get(java.util.Calendar.HOUR_OF_DAY);
            boolean use24 = "24".equals(d.clockHourFormat) || ("system".equals(d.clockHourFormat)
                    && android.text.format.DateFormat.is24HourFormat(context));
            int shownHour = use24 ? hour24 : (hour24 % 12 == 0 ? 12 : hour24 % 12);
            String hour = String.format(java.util.Locale.US, "%02d", shownHour);
            String mins = String.format(java.util.Locale.US, "%02d", minute);
            String period = use24 ? "" : new java.text.SimpleDateFormat("a", locale).format(now.getTime());
            String weekday = new java.text.SimpleDateFormat("EEE", locale).format(now.getTime());
            String date = new java.text.SimpleDateFormat("dd MMM", locale).format(now.getTime());
            String day = String.format(java.util.Locale.US, "%02d", now.get(java.util.Calendar.DAY_OF_MONTH));
            String spine = "numeric".equals(d.dateSpineFormat)
                    ? new java.text.SimpleDateFormat("MM", locale).format(now.getTime())
                    : new java.text.SimpleDateFormat("MMM", locale).format(now.getTime()).toUpperCase(locale);
            return new ClockSnapshot(hour + ":" + mins, hour, mins, period, weekday, date, day, spine,
                    minute * 6f, (hour24 % 12) * 30f + minute * .5f);
        }
    }

    /** Compact, data-driven illustrations used by the CoffeeOS-style rail cards. */
    /**
     * Ambient bars behind the MEDIA tile's copy.
     *
     * <p>Prefer {@link MediaAudioVisualizer} (session 0 waveform) when it
     * reports live energy. Otherwise fall back to the synthetic playing
     * indicator — session 0 is often silent on OEM builds, and Android 9 has
     * no AudioPlaybackCapture. Bars stay faint and behind the copy either way;
     * they are not presented as a calibrated meter.
     */
    private final class QuickMediaBarsView extends View {
        private static final int BARS = MediaAudioVisualizer.BARS;
        /** 15 Hz, not 60: this redraws on a panel that is already short of frames. */
        private static final long FRAME_MS = 66;

        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bar = new android.graphics.RectF();
        private final float[] level = new float[BARS];
        private final float[] liveTarget = new float[BARS];
        private boolean playing;
        private boolean visible = true;
        private long startedAt;
        private int tint;

        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (!visible || !isAttachedToWindow()) return;
                boolean live = mediaAudioViz != null && mediaAudioViz.hasLiveAudio();
                if (!playing && !live) return;
                invalidate();
                // Tick while the payload says playing OR session 0 still has
                // energy — AA often reports paused while the mix is audible.
                postDelayed(this, FRAME_MS);
            }
        };

        void ensureTicking() {
            if (!visible || !isAttachedToWindow()) return;
            removeCallbacks(frame);
            post(frame);
        }

        QuickMediaBarsView(Context context) {
            super(context);
            setWillNotDraw(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            retint();
        }

        void retint() {
            tint = dockAccentColor;
            invalidate();
        }

        /**
         * The rail is hidden whenever the dock is in launcher mode, and the tile
         * can be scrolled off its row. Neither stops isAttachedToWindow() being
         * true, so without this the bars would keep redrawing off screen for as
         * long as something was playing. The Visualizer probe is separate — it
         * tracks playback even while the rail is hidden.
         */
        @Override
        public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            if (visible == isVisible) return;
            visible = isVisible;
            removeCallbacks(frame);
            if (visible) ensureTicking();
        }

        void setPlaying(boolean next) {
            if (playing != next) {
                playing = next;
                if (playing) startedAt = android.os.SystemClock.uptimeMillis();
            }
            removeCallbacks(frame);
            // Probe follows audible music — not only the now-playing flag — so
            // Android Auto's stale paused bit does not block the session-0 test.
            syncMediaVisualizerWanted(mediaAudioWanted());
            if (visible) ensureTicking();
            else invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(frame);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float d = getResources().getDisplayMetrics().density;

            float barW = 3f * d;
            float gap = 5f * d;
            float span = BARS * barW + (BARS - 1) * gap;
            float right = w - 9f * d;
            float left = right - span;
            if (left < 0f) return;
            float floor = h - 10f * d;
            float ceiling = h * 0.24f;
            float travel = floor - ceiling;
            if (travel <= 0f) return;

            // Prefer live FFT whenever session 0 has energy — not only when the
            // now-playing payload says playing. AA/MediaCenter often publish
            // paused while music is still audible, which used to flip the row
            // back to the synthetic sine fallback.
            boolean live = mediaAudioViz != null
                    && mediaAudioViz.copyLiveLevels(liveTarget);
            float seconds = playing
                    ? (android.os.SystemClock.uptimeMillis() - startedAt) / 1000f : 0f;
            for (int i = 0; i < BARS; i++) {
                float target;
                if (live) {
                    target = liveTarget[i];
                } else if (!playing) {
                    target = 0.10f;
                } else {
                    // Three incommensurate rates per bar, so the row never reads
                    // as one sine wave marching across it.
                    double a = Math.sin(seconds * 2.7 + i * 0.9);
                    double b = Math.sin(seconds * 1.3 + i * 2.1);
                    double c = Math.sin(seconds * 4.1 + i * 0.4);
                    target = (float) (0.46 + 0.26 * a + 0.18 * b + 0.10 * c);
                }
                // Synthetic keeps a visible floor; live must be allowed to drop
                // or calm passages read as a solid wall of bars.
                float floorLevel = live ? 0.02f : 0.06f;
                if (target < floorLevel) target = floorLevel;
                if (target > 1f) target = 1f;
                // Ease toward the target so a pause settles instead of snapping.
                float ease = live ? 0.38f : (!playing ? 0.22f : 0.55f);
                level[i] += (target - level[i]) * ease;

                float x = left + i * (barW + gap);
                float top = floor - travel * level[i];
                bar.set(x, top, x + barW, floor);
                // Faint at the leading edge, a little stronger at the trailing
                // one, so the field fades out under the title instead of
                // competing with it.
                float lead = (float) i / (BARS - 1);
                int alpha = Math.round(0x10 + 0x2A * lead);
                paint.setColor(withAlpha(tint, alpha));
                canvas.drawRoundRect(bar, barW * 0.5f, barW * 0.5f, paint);
            }
            if (!playing && !live) {
                boolean settled = true;
                for (int i = 0; i < BARS; i++) {
                    if (Math.abs(level[i] - 0.10f) > 0.01f) { settled = false; break; }
                }
                if (!settled) postDelayed(frame, FRAME_MS);
            }
        }
    }

    private void syncMediaVisualizerWanted(boolean want) {
        if (!want) {
            if (mediaAudioViz != null) mediaAudioViz.setWanted(false);
            return;
        }
        ensureMediaVisualizer();
    }

    /** Playing flag from now-playing, or any STREAM_MUSIC activity (AA often lies). */
    private boolean mediaAudioWanted() {
        if (quickMediaPlaying) return true;
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private final Runnable mediaVizPoll = new Runnable() {
        @Override
        public void run() {
            boolean want = mediaAudioWanted();
            syncMediaVisualizerWanted(want);
            mainHandler.postDelayed(this, 1000);
        }
    };

    private void ensureMediaVisualizer() {
        // Never prompt. The bars are decoration, and a system dialog over the
        // panel the first time something plays is worse than synthetic bars.
        // Deploy grants RECORD_AUDIO over adb (Grant-CarMediaAccess); without
        // that grant the rail keeps its synthetic bars. The 1 s poll re-checks,
        // so a grant made while the app runs is picked up.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!mediaVizPermissionLogged) {
                mediaVizPermissionLogged = true;
                Log.w(MediaAudioVisualizer.TAG, "no RECORD_AUDIO grant — synthetic bars only");
            }
            return;
        }
        startMediaVisualizerIfNeeded();
    }

    private void startMediaVisualizerIfNeeded() {
        if (mediaAudioViz == null) {
            mediaAudioViz = new MediaAudioVisualizer();
            mediaAudioViz.setOnUpdate(() -> {
                if (quickMediaBars != null) quickMediaBars.ensureTicking();
            });
        }
        mediaAudioViz.setWanted(true);
    }

    /**
     * Ask for location, once, for the navigation card's idle city line.
     *
     * targetSdk is 28, so ACCESS_*_LOCATION is a RUNTIME grant — declaring it
     * in the manifest is not enough. It was declared and never requested, so
     * PlaceGlance returned on its first line on every 45 s tick and the card
     * sat at an em dash forever. Measured on the car 2026-09-11: the only
     * runtime permission the viewer held was RECORD_AUDIO.
     *
     * A denial is not retried — the glance is a nicety, and re-prompting on
     * every launch would be worse than the em dash.
     */
    private void ensurePlaceLocationPermission() {
        if (placeLocationAsked) return;
        placeLocationAsked = true;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
            }, REQ_PLACE_LOCATION);
        } catch (Throwable t) {
            Log.w(TAG, "location permission request failed", t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PLACE_LOCATION) {
            boolean granted = false;
            for (int result : grantResults) {
                if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) granted = true;
            }
            Log.w(TAG, "PlaceGlance location permission " + (granted ? "granted" : "denied"));
            // The worker is already ticking; poke it so the city does not wait
            // out the remainder of the current 45 s gap.
            if (granted && placeGlance != null) placeGlance.pokeNow();
            return;
        }
    }

    private final class QuickCardGraphicView extends View {
        private BottomCardDescriptor descriptor;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                        | android.graphics.Paint.FILTER_BITMAP_FLAG
                        | android.graphics.Paint.DITHER_FLAG);
        private final android.graphics.RectF oval = new android.graphics.RectF();
        private Bitmap tiresTopViewBitmap;
        private boolean tiresTopViewDecodeAttempted;
        private float statusLift;
        private long statusLiftLastMs;
        private final java.util.Map<String, Bitmap> powerGhostBitmaps =
                new java.util.HashMap<>();
        private final java.util.Map<String, Bitmap> statusVehicleBitmaps =
                new java.util.HashMap<>();

        QuickCardGraphicView(Context context, BottomCardDescriptor descriptor) {
            super(context);
            this.descriptor = descriptor;
            setWillNotDraw(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setDescriptor(BottomCardDescriptor next) {
            descriptor = next;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (descriptor == null) return;
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            int accent = dockAccentColor;
            int muted = dockUiLight ? 0x55323C48 : 0x66FFFFFF;
            int strong = dockUiLight ? 0xCC25303B : 0xE6FFFFFF;
            switch (descriptor.id) {
                case "range": drawRing(canvas, w, h, accent, muted, true); break;
                case "status": drawVehicleStatus(canvas, w, h, accent, muted, strong); break;
                case "climate": drawClimate(canvas, w, h, accent, muted); break;
                case "consumption": drawEnergy(canvas, w, h, accent, muted, strong); break;
                case "navigation": drawNavigation(canvas, w, h, accent, muted, strong); break;
                case "tires": drawTires(canvas, w, h, accent, muted, strong); break;
                case "power": drawPower(canvas, w, h, accent, muted, strong); break;
                case "clock": drawClock(canvas, w, h, accent, muted, strong); break;
                case "desktops": drawDesktops(canvas, w, h, accent, muted); break;
                case "driveMode": drawDriveMode(canvas, w, h, accent, muted, strong); break;
                case "powerMode": drawPowerMode(canvas, w, h, accent, muted, strong); break;
                case "regen": drawRegen(canvas, w, h, accent, muted, strong); break;
                case "roof": drawRoof(canvas, w, h, accent, muted, strong); break;
                default: drawRing(canvas, w, h, accent, muted, false); break;
            }
        }

        private void stroke(int color, float width) {
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setColor(color);
        }

        private void fill(int color) {
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(color);
        }

        private void drawRing(android.graphics.Canvas c, float w, float h,
                int accent, int muted, boolean bolt) {
            float size = Math.min(w, h) * 0.72f;
            float left = (w - size) * 0.5f, top = (h - size) * 0.5f;
            oval.set(left, top, left + size, top + size);
            stroke(muted, Math.max(3f, size * 0.105f));
            c.drawArc(oval, -90f, 360f, false, paint);
            stroke(accent, Math.max(3f, size * 0.105f));
            c.drawArc(oval, -90f, 360f * descriptor.progress / 100f, false, paint);
            if (bolt) {
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(w * .53f, h * .25f);
                p.lineTo(w * .37f, h * .53f);
                p.lineTo(w * .50f, h * .53f);
                p.lineTo(w * .43f, h * .76f);
                p.lineTo(w * .65f, h * .43f);
                p.lineTo(w * .51f, h * .43f);
                p.close();
                fill(accent);
                c.drawPath(p, paint);
            }
        }

        private void drawGauge(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            float pad = w * .13f;
            oval.set(pad, h * .25f, w - pad, h * .93f);
            stroke(muted, Math.max(3f, w * .065f));
            c.drawArc(oval, 190f, 160f, false, paint);
            stroke(accent, Math.max(3f, w * .065f));
            c.drawArc(oval, 190f, 160f * descriptor.progress / 100f, false, paint);
            float a = (float) Math.toRadians(190f + 160f * descriptor.progress / 100f);
            float cx = w * .5f, cy = h * .60f, r = w * .24f;
            stroke(strong, Math.max(2f, w * .035f));
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * r,
                    cy + (float) Math.sin(a) * r, paint);
            fill(accent);
            c.drawCircle(cx, cy, Math.max(3f, w * .055f), paint);
        }

        /**
         * Status deliberately shares the realistic top-view raster with Tires;
         * opening strokes are placed beside their physical door/tailgate edge,
         * never as generic dashboard dots.
         */
        private void drawVehicleStatus(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            Bitmap base = getStatusVehicleBitmap("base.png");
            // The raster reserves transparent margins for the open-door states. Let the
            // destination extend slightly past the view vertically (transparent pixels only).
            // The vehicle is the card's visual anchor, with TPMS values flanking it.
            // Visible pixels occupy x=30..464 and y=14..654 of the 494x675 source canvas.
            float imageH = h * 1.34f;
            float imageW = base == null ? w * .25f
                    : imageH * base.getWidth() / Math.max(1f, base.getHeight()) * 1.08f;
            imageW = Math.min(imageW, w * .42f);
            float left = (w - imageW) * .5f;
            float top = -h * .019f;
            String[] openings = descriptor.openingStates == null ? new String[0]
                    : descriptor.openingStates;
            // The raster overflows the card at the bottom, which is where the
            // open tailgate sits. Lift the car so the open trunk is on screen.
            // Eased over ~320 ms each way instead of snapping.
            float liftTarget = openings.length > 4 && "open".equals(openings[4]) ? 1f : 0f;
            long nowMs = android.os.SystemClock.uptimeMillis();
            float stepMs = statusLiftLastMs == 0L ? 0f : Math.min(64f, nowMs - statusLiftLastMs);
            statusLiftLastMs = nowMs;
            float delta = liftTarget - statusLift;
            if (Math.abs(delta) > .001f) {
                float move = stepMs / 320f;
                statusLift += Math.signum(delta) * Math.min(Math.abs(delta), move);
                postInvalidateOnAnimation();
            } else {
                statusLift = liftTarget;
                statusLiftLastMs = 0L;
            }
            float eased = statusLift * statusLift * (3f - 2f * statusLift);
            top -= (imageH - h * 1.02f) * eased;
            android.graphics.RectF vehicleRect = new android.graphics.RectF(
                    left, top, left + imageW, top + imageH);
            if (base != null) {
                paint.setAlpha(dockUiLight ? 238 : 255);
                c.drawBitmap(base, null, vehicleRect, paint);
                paint.setAlpha(255);
            } else {
                c.save();
                c.translate(left - w * .37f, 0f);
                drawStatusVehicleFallback(c, w, h, muted, strong);
                c.restore();
            }
            // Darken only the matching door footprint before drawing an open panel.
            // The repository's doorless base retains bright pillar/rocker pixels which
            // otherwise resemble a second, closed door at compact card size.
            String[] cavityAssets = {"door-fl-cavity-v1.png", "door-fr-cavity-v1.png",
                    "door-rl-cavity-v1.png", "door-rr-cavity-v1.png"};
            for (int i = 0; i < cavityAssets.length; i++) {
                String opening = i < openings.length ? openings[i] : "unknown";
                if (!"open".equals(opening)) continue;
                Bitmap layer = getStatusVehicleBitmap(cavityAssets[i]);
                if (layer != null) c.drawBitmap(layer, null, vehicleRect, paint);
            }
            String[] closedAssets = {"door-fl-closed-v4.png", "door-fr-closed-v4.png",
                    "door-rl-closed-v4.png", "door-rr-closed-v4.png"};
            for (int i = 0; i < closedAssets.length; i++) {
                String opening = i < openings.length ? openings[i] : "unknown";
                if ("open".equals(opening)) continue;
                Bitmap layer = getStatusVehicleBitmap(closedAssets[i]);
                if (layer != null) c.drawBitmap(layer, null, vehicleRect, paint);
            }
            String[] assets = {"door-fl-open-v3.png", "door-fr-open-v3.png",
                    "door-rl-open-v3.png", "door-rr-open-v3.png", "tailgate-open-v2.png"};
            android.graphics.ColorMatrix vividRed = new android.graphics.ColorMatrix();
            vividRed.setSaturation(1.55f);
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(vividRed));
            for (int i = 0; i < 5; i++) {
                String opening = i < openings.length ? openings[i] : "unknown";
                if (!"open".equals(opening)) continue;
                Bitmap layer = getStatusVehicleBitmap(assets[i]);
                if (layer != null) c.drawBitmap(layer, null, vehicleRect, paint);
            }
            paint.setColorFilter(null);
            drawStatusRoof(c, vehicleRect);
            drawStatusSeatBelts(c, vehicleRect);

            // Status and Tires share TPMS health. Large values stay completely
            // outside the vehicle so the door, roof and restraint art remains clear.
            float sideGap = Math.max(10f, w * .048f);
            float[] tireXs = {vehicleRect.left - sideGap, vehicleRect.right + sideGap,
                    vehicleRect.left - sideGap, vehicleRect.right + sideGap};
            // Vertically anchored to the VIEW, not to vehicleRect. The raster is
            // deliberately taller than the card -- its transparent margins carry
            // the open-door states -- so a readout keyed to it rides that
            // overflow off the bottom: at imageH 1.34x the lower baseline
            // computed to 83.4 in an 81px view. X still tracks the vehicle,
            // which is the whole point of flanking it.
            float[] tireYs = {h * .24f, h * .24f, h * .70f, h * .70f};
            String[] pressureReadings = tireReadings(descriptor);
            String[] temps = descriptor.tireTemperatures.split("\\s*/\\s*", -1);
            String unit = descriptor.tirePressureUnit;
            android.graphics.Typeface valueFace = android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.BOLD);
            android.graphics.Typeface unitFace = android.graphics.Typeface.create("sans-serif",
                    android.graphics.Typeface.NORMAL);
            android.graphics.Typeface tempFace = android.graphics.Typeface.create("sans-serif-light",
                    android.graphics.Typeface.NORMAL);
            float valueSize = Math.max(16f, Math.min(w, h) * .245f);
            float smallSize = Math.max(9f, valueSize * .5f);
            for (int i = 0; i < 4; i++) {
                String wheelState = descriptor.wheelStates != null
                        && i < descriptor.wheelStates.length
                        ? descriptor.wheelStates[i] : "unavailable";
                boolean leftSide = i == 0 || i == 2;
                String value = pressureReadings[i];
                boolean hasValue = !"unavailable".equals(value) && !"—".equals(value);
                String unitText = hasValue && !unit.isEmpty() ? " " + unit : "";
                paint.setTypeface(unitFace);
                paint.setTextSize(smallSize);
                float unitW = paint.measureText(unitText);
                paint.setTypeface(valueFace);
                paint.setTextSize(valueSize);
                float valueW = paint.measureText(value);
                // Left-side readouts end at the car; right-side ones start there.
                float x = leftSide ? tireXs[i] - valueW - unitW : tireXs[i];
                float baseline = tireYs[i] + valueSize * .34f;
                paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                fill(tireSignalColor(wheelState, muted));
                c.drawText(value, x, baseline, paint);
                if (!unitText.isEmpty()) {
                    paint.setTypeface(unitFace);
                    paint.setTextSize(smallSize);
                    fill(withAlpha(muted, 0xC8));
                    c.drawText(unitText, x + valueW, baseline, paint);
                }
                String temp = i < temps.length ? temps[i].trim() : "";
                if (!temp.isEmpty()) {
                    paint.setTypeface(tempFace);
                    paint.setTextSize(smallSize * 1.1f);
                    paint.setTextAlign(leftSide
                            ? android.graphics.Paint.Align.RIGHT : android.graphics.Paint.Align.LEFT);
                    fill(0xFF8F949B);
                    c.drawText(temp, tireXs[i], baseline + smallSize * 1.35f, paint);
                }
            }
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        private void drawStatusRoof(android.graphics.Canvas c, android.graphics.RectF vehicleRect) {
            float left = vehicleRect.left + vehicleRect.width() * .405f;
            float right = vehicleRect.left + vehicleRect.width() * .595f;
            float top = vehicleRect.top + vehicleRect.height() * .405f;
            float bottom = vehicleRect.top + vehicleRect.height() * .665f;
            float radius = Math.max(2f, vehicleRect.width() * .035f);
            fill(0xA90A1116);
            c.drawRoundRect(left, top, right, bottom, radius, radius, paint);

            // The opaque shade retreats towards the rear as it opens; the dark,
            // semi-transparent glass remains visible above the interior.
            float shadeVisible = 1f - descriptor.curtainLevel / 100f;
            if (shadeVisible > .01f) {
                float shadeBottom = top + (bottom - top) * shadeVisible;
                fill(0xE2A3AAAC);
                c.drawRoundRect(left + 1f, top + 1f, right - 1f, shadeBottom,
                        radius, radius, paint);
            }
            float glassShift = (bottom - top) * .43f * descriptor.sunroofLevel / 100f;
            fill(0x6B05090C);
            c.drawRoundRect(left + 1f, top + 1f + glassShift, right - 1f,
                    top + (bottom - top) * .51f + glassShift, radius, radius, paint);
            stroke(0xCC05080A, Math.max(1f, vehicleRect.width() * .012f));
            c.drawRoundRect(left, top, right, bottom, radius, radius, paint);
            c.drawLine(left, top + (bottom - top) * .52f, right,
                    top + (bottom - top) * .52f, paint);
        }

        private void drawStatusSeatBelts(android.graphics.Canvas c,
                android.graphics.RectF vehicleRect) {
            String[] states = descriptor.seatBeltStates == null
                    ? new String[0] : descriptor.seatBeltStates;
            float[] xs = {.425f, .575f, .405f, .5f, .595f};
            float[] ys = {.49f, .49f, .60f, .60f, .60f};
            for (int i = 0; i < 5 && i < states.length; i++) {
                if (!"unfastened".equals(states[i])) continue;
                float x = vehicleRect.left + vehicleRect.width() * xs[i];
                float y = vehicleRect.top + vehicleRect.height() * ys[i];
                float radius = Math.max(2.7f, vehicleRect.width() * .04f);
                fill(0xFFE51F35);
                c.drawCircle(x, y, radius, paint);
                stroke(0xFFFFFFFF, Math.max(1f, radius * .30f));
                c.drawLine(x - radius * .35f, y - radius * .45f,
                        x + radius * .35f, y + radius * .45f, paint);
            }
        }

        private String ellipsizeStatusText(String value, float maxWidth) {
            if (value == null || value.isEmpty() || paint.measureText(value) <= maxWidth) {
                return value == null ? "" : value;
            }
            String ellipsis = "…";
            int end = value.length();
            while (end > 0 && paint.measureText(value.substring(0, end) + ellipsis) > maxWidth) {
                end--;
            }
            return value.substring(0, end) + ellipsis;
        }

        private Bitmap getStatusVehicleBitmap(String filename) {
            if (statusVehicleBitmaps.containsKey(filename)) return statusVehicleBitmaps.get(filename);
            Bitmap bitmap = null;
            try (InputStream stream = getAssets().open(
                    "www/assets/ui/vehicle-status/" + filename)) {
                bitmap = BitmapFactory.decodeStream(stream);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Optional status vehicle asset unavailable: " + filename, error);
            }
            statusVehicleBitmaps.put(filename, bitmap);
            return bitmap;
        }

        private boolean hasOpenStatusOpening(String[] openings) {
            for (String opening : openings) if ("open".equals(opening)) return true;
            return false;
        }

        private void drawStatusVehicleFallback(android.graphics.Canvas c, float w, float h,
                int muted, int strong) {
            android.graphics.Path body = new android.graphics.Path();
            body.moveTo(w * .45f, h * .08f); body.quadTo(w * .50f, h * .03f, w * .55f, h * .08f);
            body.lineTo(w * .63f, h * .25f); body.lineTo(w * .63f, h * .76f);
            body.quadTo(w * .60f, h * .86f, w * .55f, h * .88f); body.lineTo(w * .45f, h * .88f);
            body.quadTo(w * .40f, h * .86f, w * .37f, h * .76f); body.lineTo(w * .37f, h * .25f); body.close();
            fill(withAlpha(muted, 0x30)); c.drawPath(body, paint);
            stroke(strong, Math.max(1.5f, w * .018f)); c.drawPath(body, paint);
        }

        private void drawClimate(android.graphics.Canvas c, float w, float h,
                int accent, int muted) {
            float cx = w * .48f, cy = h * .52f;
            stroke(muted, Math.max(2f, w * .035f));
            c.drawCircle(cx, cy, w * .12f, paint);
            for (int i = 0; i < 8; i++) {
                float a = (float) Math.toRadians(i * 45f);
                float r1 = w * .21f, r2 = w * .31f;
                c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                        cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, paint);
            }
            fill(withAlpha(accent, 0x44));
            c.drawCircle(cx, cy, w * .20f, paint);
            fill(accent);
            c.drawCircle(cx, cy, w * .09f, paint);
            for (int i = 0; i < 3; i++) {
                float a = (float) Math.toRadians(i * 120f - 90f);
                oval.set(cx + (float) Math.cos(a) * w * .10f - w * .07f,
                        cy + (float) Math.sin(a) * w * .10f - h * .035f,
                        cx + (float) Math.cos(a) * w * .10f + w * .07f,
                        cy + (float) Math.sin(a) * w * .10f + h * .035f);
                c.drawOval(oval, paint);
            }
        }

        /**
         * ENERGY rail card graphic: the last seven days' distance as bars (today
         * in the accent) and the trip's EV share as a rule underneath.
         *
         * Numbers stay in the card's own text column (primary / secondary /
         * metricA · metricB), which every visual card already has beside its
         * graphic. Measured on the emulator 2026-09-13: drawing the figure here
         * as well duplicated it into this narrow slot and truncated both copies.
         */
        private void drawEnergy(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            int[] bars = descriptor.energyBars;
            int n = bars == null ? 0 : bars.length;
            float left = w * .10f, right = w * .90f, top = h * .14f, bottom = h * .74f;
            stroke(muted, Math.max(1f, w * .012f));
            c.drawLine(left, bottom, right, bottom, paint);
            if (n > 0) {
                float gap = Math.max(1.5f, w * .03f);
                float bw = Math.max(1f, ((right - left) - gap * (n - 1)) / n);
                for (int i = 0; i < n; i++) {
                    float ratio = Math.max(0f, Math.min(1f, bars[i] / 100f));
                    float l = left + i * (bw + gap);
                    float t = bottom - Math.max(h * .025f, (bottom - top) * ratio);
                    fill(i == n - 1 ? accent : withAlpha(strong, ratio > 0f ? 0x66 : 0x22));
                    c.drawRoundRect(l, t, l + bw, bottom, bw * .3f, bw * .3f, paint);
                }
            }
            float railY = h * .88f;
            float railW = Math.max(2f, h * .035f);
            stroke(withAlpha(strong, 0x2A), railW);
            c.drawLine(left, railY, right, railY, paint);
            if (descriptor.progress > 0) {
                stroke(accent, railW);
                c.drawLine(left, railY, left + (right - left) * descriptor.progress / 100f, railY, paint);
            }
        }

        private void drawNavigation(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            String state = descriptor.state == null ? "" : descriptor.state;
            boolean idle = "idle".equals(state) || "unavailable".equals(state) || state.isEmpty();
            boolean showTurn = !idle && (("destination".equals(state) || "exit".equals(state)
                    || tbtIconRes(state) != 0)
                    || (descriptor.glyph != null && !descriptor.glyph.isEmpty()));
            String street = descriptor.primary == null ? "" : descriptor.primary;
            String maneuver = descriptor.metricA == null ? "" : descriptor.metricA;
            String remaining = descriptor.navRemaining == null ? "" : descriptor.navRemaining;
            String duration = descriptor.navDuration == null ? "" : descriptor.navDuration;
            String eta = descriptor.navEta == null ? "" : descriptor.navEta;
            float col = Math.min(w * .30f, h * .95f);
            if (showTurn) {
                float glyph = Math.min(col * .78f, h * .58f);
                if (!drawTbtIcon(c, state, col * .5f, h * .36f, glyph, accent)
                        && descriptor.glyph != null && !descriptor.glyph.isEmpty()) {
                    drawGlyphPath(c, descriptor.glyph, col * .5f, h * .36f, glyph,
                            accent, Math.max(2.4f, glyph * .08f));
                }
                if (!maneuver.isEmpty()) {
                    paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                    paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                            android.graphics.Typeface.NORMAL));
                    paint.setTextSize(Math.max(13f, h * .20f));
                    fill(strong);
                    c.drawText(maneuver, col * .5f, h * .88f, paint);
                }
            } else {
                int routeColor = "unavailable".equals(state) ? muted : accent;
                float doodleW = col * .92f, doodleH = h * .78f;
                float ox = col * .04f, oy = h * .12f;
                android.graphics.Path route = new android.graphics.Path();
                route.moveTo(ox + doodleW * .12f, oy + doodleH * .88f);
                route.cubicTo(ox + doodleW * .18f, oy + doodleH * .48f,
                        ox + doodleW * .48f, oy + doodleH * .66f,
                        ox + doodleW * .52f, oy + doodleH * .32f);
                route.cubicTo(ox + doodleW * .55f, oy + doodleH * .12f,
                        ox + doodleW * .78f, oy + doodleH * .14f,
                        ox + doodleW * .92f, oy + doodleH * .14f);
                stroke(muted, Math.max(3.5f, w * .018f));
                c.drawPath(route, paint);
                stroke(routeColor, Math.max(1.8f, w * .01f));
                c.drawPath(route, paint);
                fill(routeColor);
                c.drawCircle(ox + doodleW * .12f, oy + doodleH * .88f, Math.max(3f, w * .012f), paint);
            }
            float textX = col + w * .035f;
            float textMax = w - textX - w * .03f;
            if (textMax > 8f) {
                paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.NORMAL));
                if (!street.isEmpty()) {
                    paint.setTextSize(Math.max(16f, h * .26f));
                    fill(strong);
                    c.drawText(ellipsizeNav(street, textMax), textX, h * .32f, paint);
                }
                if (showTurn) {
                    drawNavMetricRow(c, textX, h * .46f, textMax, h,
                            remaining, duration, eta, accent, muted, strong);
                } else {
                    // Idle glance: city under the neighbourhood (street/primary,
                    // above) and a tiny "start navigation" hint under that. The
                    // metric row is not drawn here, so this space is free.
                    String idleCity = descriptor.navIdleCity == null ? "" : descriptor.navIdleCity;
                    String idleAction = descriptor.navIdleAction == null ? "" : descriptor.navIdleAction;
                    if (!idleCity.isEmpty()) {
                        paint.setTextSize(Math.max(12f, h * .17f));
                        fill(muted);
                        c.drawText(ellipsizeNav(idleCity, textMax), textX, h * .54f, paint);
                    }
                    if (!idleAction.isEmpty()) {
                        paint.setTextSize(Math.max(10f, h * .13f));
                        fill(muted);
                        c.drawText(ellipsizeNav(idleAction, textMax), textX, h * .76f, paint);
                    }
                }
            }
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        private void drawNavMetricRow(android.graphics.Canvas c, float x, float top,
                float maxW, float h, String remaining, String duration, String eta,
                int accent, int muted, int strong) {
            java.util.List<String[]> items = new java.util.ArrayList<>();
            if (remaining != null && !remaining.isEmpty()) {
                items.add(new String[] { remaining, "REMAINING" });
            }
            if (duration != null && !duration.isEmpty()) {
                items.add(new String[] { duration, "TIME" });
            }
            if (eta != null && !eta.isEmpty()) {
                items.add(new String[] { eta, "ETA" });
            }
            if (items.isEmpty() || maxW <= 0f) return;
            android.graphics.Typeface medium = android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL);
            float valueSize = Math.max(15f, h * .28f);
            float labelSize = Math.max(9f, h * .13f);
            float gap = Math.max(10f, h * .08f);
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            paint.setTypeface(medium);
            float[] widths = measureNavMetricWidths(items, valueSize, labelSize);
            float total = sumNavMetricWidths(widths, gap);
            if (total > maxW) {
                for (int i = items.size() - 1; i >= 0; i--) {
                    if ("TIME".equals(items.get(i)[1])) {
                        items.remove(i);
                        break;
                    }
                }
                widths = measureNavMetricWidths(items, valueSize, labelSize);
                total = sumNavMetricWidths(widths, gap);
            }
            while (total > maxW && valueSize > 11f) {
                valueSize -= 1f;
                widths = measureNavMetricWidths(items, valueSize, labelSize);
                total = sumNavMetricWidths(widths, gap);
            }
            float valueY = top + h * .22f;
            float labelY = top + h * .40f;
            // ETA sits a little further right than the other columns, so the
            // clock reads as its own figure rather than part of the distance.
            float etaNudge = Math.max(8f, h * .08f);
            if (total + etaNudge > maxW) etaNudge = Math.max(0f, maxW - total);
            float cx = x;
            for (int i = 0; i < items.size(); i++) {
                float colW = widths[i];
                boolean isEta = "ETA".equals(items.get(i)[1]);
                if (isEta && i > 0) cx += etaNudge;
                paint.setTypeface(medium);
                paint.setTextSize(valueSize);
                fill(isEta ? accent : strong);
                c.drawText(ellipsizeNav(items.get(i)[0], colW), cx, valueY, paint);
                paint.setTextSize(labelSize);
                fill(muted);
                c.drawText(items.get(i)[1], cx, labelY, paint);
                cx += colW + gap;
            }
        }

        private float[] measureNavMetricWidths(java.util.List<String[]> items,
                float valueSize, float labelSize) {
            float[] widths = new float[items.size()];
            for (int i = 0; i < items.size(); i++) {
                paint.setTextSize(valueSize);
                float valueW = paint.measureText(items.get(i)[0]);
                paint.setTextSize(labelSize);
                widths[i] = Math.max(valueW, paint.measureText(items.get(i)[1]));
            }
            return widths;
        }

        private float sumNavMetricWidths(float[] widths, float gap) {
            float total = 0f;
            for (int i = 0; i < widths.length; i++) {
                total += widths[i];
                if (i > 0) total += gap;
            }
            return total;
        }

        private int tbtIconRes(String state) {
            if (state == null) return 0;
            switch (state) {
                case "turn_right": return R.drawable.ic_tbt_turn_right;
                case "turn_left": return R.drawable.ic_tbt_turn_left;
                case "straight": return R.drawable.ic_tbt_straight;
                case "uturn": return R.drawable.ic_tbt_uturn;
                case "roundabout": return R.drawable.ic_tbt_roundabout;
                case "fork": return R.drawable.ic_tbt_fork;
                case "merge": return R.drawable.ic_tbt_merge;
                default: return 0;
            }
        }

        private boolean drawTbtIcon(android.graphics.Canvas c, String state,
                float cx, float cy, float size, int accent) {
            int res = tbtIconRes(state);
            if (res == 0) return false;
            android.graphics.drawable.Drawable icon = getContext().getDrawable(res);
            if (icon == null) return false;
            icon = icon.mutate();
            icon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            int s = Math.max(12, Math.round(size));
            int left = Math.round(cx - s / 2f);
            int top = Math.round(cy - s / 2f);
            icon.setBounds(left, top, left + s, top + s);
            icon.draw(c);
            icon.setColorFilter(null);
            return true;
        }

        private String ellipsizeNav(String text, float maxWidth) {
            if (text == null || text.isEmpty() || maxWidth <= 0f) return "";
            if (paint.measureText(text) <= maxWidth) return text;
            String ellipsis = "…";
            int n = text.length();
            while (n > 0 && paint.measureText(text.substring(0, n) + ellipsis) > maxWidth) n--;
            return n <= 0 ? ellipsis : text.substring(0, n) + ellipsis;
        }

        private void drawTires(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            Bitmap topView = getTiresTopViewBitmap();
            if (topView != null) {
                drawTiresRaster(c, w, h, topView, accent, muted, strong);
                return;
            }
            drawTiresFallback(c, w, h, accent, muted, strong);
        }

        /** Approved chassis atlas and independently animated ten-segment SOC overlay. */
        private void drawPower(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            String variant = sanitizePowerVariant(descriptor.powerVariant);
            Bitmap car = getPowerChassisBitmap(variant);
            String state = sanitizePowerState(descriptor.state);
            String token = sanitizePowerDirections(descriptor.glyphText);
            boolean known = !"stale".equals(state) && !"unavailable".equals(state) && !token.isEmpty();
            int front=0,rear=0,ice=0;
            if(known){String[] parts=token.split(",");front=Integer.parseInt(parts[0]);rear=Integer.parseInt(parts[1]);ice=Integer.parseInt(parts[2]);}
            boolean awd="phev34".equals(variant),hev="hev2".equals(variant);
            if(!awd)rear=0;
            int cropX=awd?398:hev?774:20;
            float fit=Math.min(w/770f,h/350f)*.96f;
            int saved=c.save();
            c.translate(w*.5f,h*.5f);c.scale(fit,fit);c.rotate(90f);c.translate(-175f,-385f);
            if(car!=null){
                oval.set(0,0,350,770);fill(0xFFFFFFFF);paint.setFilterBitmap(true);
                c.drawBitmap(car,new android.graphics.Rect(cropX,158,cropX+350,928),oval,paint);
                paint.setFilterBitmap(false);
            }
            boolean incoming=front<0||rear<0,outgoing=front>0||rear>0;
            boolean charging=known&&(("charge".equals(state)&&!hev)||incoming&&!outgoing);
            boolean discharging=known&&outgoing&&!incoming&&("ev".equals(state)||"hybrid".equals(state));
            float px=awd?118:hev?127:121,py=awd?371:hev?538:375;
            float pw=hev?91:115,ph=awd?134:hev?90:130;
            float bx=awd?123:hev?132:126,by=hev?567:409,bw=hev?81:105,bh=awd?49:hev?22:45;
            fill(0xFF626B71);c.drawRoundRect(px,py,px+pw,py+ph,4,4,paint);
            stroke(0xFF9AA1A5,1);c.drawRoundRect(px,py,px+pw,py+ph,4,4,paint);
            boolean pulse=drawPowerBatteryCells(c,bx,by,bw,bh,descriptor.socKnown,descriptor.powerSoc,charging,discharging);
            float[][] frontPath=awd?new float[][]{{177,248},{177,221},{177,173}}
                    :hev?new float[][]{{172,534},{111,514},{111,246},{177,216},{177,173}}
                    :new float[][]{{178,340},{178,252},{178,173}};
            drawPowerTopRoute(c,frontPath,front,accent);
            drawPowerTopRoute(c,new float[][]{{177,173},{57,144}},front,accent);
            drawPowerTopRoute(c,new float[][]{{177,173},{289,144}},front,accent);
            if(awd){
                drawPowerTopRoute(c,new float[][]{{177,567},{177,594},{177,640}},rear,accent);
                drawPowerTopRoute(c,new float[][]{{177,640},{57,654}},rear,accent);
                drawPowerTopRoute(c,new float[][]{{177,640},{289,654}},rear,accent);
            }
            if(ice==1){fill(0x28FFB65C);c.drawRoundRect(137,61,217,131,4,4,paint);}
            fill(0xFFF2F5F7);paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);paint.setTextSize(hev?19:23);
            c.drawText(descriptor.socKnown?Math.round(descriptor.powerSoc)+"%":"—",bx+bw/2,by-9,paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);paint.setTextSize(9);
            c.drawText("HIGH VOLTAGE",bx+bw/2,by+bh+15,paint);
            c.drawText("BATTERY",bx+bw/2,by+bh+27,paint);
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            c.restoreToCount(saved);
            // Only this small view repaints; no WebView telemetry or 3D render loop.
            // View invalidation resumes naturally after becoming visible again.
            if(pulse&&powerMotionEnabled()&&getGlobalVisibleRect(powerVisibleRect))postInvalidateDelayed(33);
        }

        private final android.graphics.Rect powerVisibleRect=new android.graphics.Rect();

        private boolean powerMotionEnabled(){
            if(!isShown()||getWindowVisibility()!=View.VISIBLE||!hasWindowFocus())return false;
            try{return android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;}
            catch(RuntimeException ignored){return true;}
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus){
            super.onWindowFocusChanged(hasFocus);
            if(hasFocus&&descriptor!=null&&"power".equals(descriptor.id))invalidate();
        }

        private boolean drawPowerBatteryCells(android.graphics.Canvas c,float x,float y,float w,float h,
                boolean socKnown,double soc,boolean charging,boolean discharging){
            double total=socKnown?Math.max(0,Math.min(100,soc))/10:0;
            int last=(int)Math.ceil(total)-1;
            boolean pulse=last>=0&&(discharging||charging&&soc<100);
            boolean animate=pulse&&powerMotionEnabled();
            double seconds=android.os.SystemClock.uptimeMillis()/1000.0;
            float gap=2.5f,cw=(w-gap*9)/10;
            for(int i=0;i<10;i++){
                float cx=x+i*(cw+gap),level=(float)Math.max(0,Math.min(1,total-i));
                fill(0xFF141D22);c.drawRoundRect(cx,y,cx+cw,y+h,4,4,paint);
                stroke(0xFF929A9F,1.3f);c.drawRoundRect(cx,y,cx+cw,y+h,4,4,paint);
                if(animate&&level>0&&(discharging||i==last)){
                    double delay=discharging?(last-i)*.14:0;
                    double phase=((seconds-delay)%1.8+1.8)%1.8/1.8;
                    level*=(float)(1-Math.abs(2*phase-1));
                }
                fill(withAlpha(discharging?0xFF22C9FF:0xFF65EC55,Math.round(level*255)));
                c.drawRoundRect(cx+1,y+1.5f,cx+cw-1,y+h-1.5f,4,4,paint);
            }
            return pulse;
        }

        private void drawPowerTopRoute(android.graphics.Canvas c, float[][] points,
                int direction, int accent) {
            if(direction==0||points==null||points.length<2)return;
            int color=direction>0?0xFF22C9FF:0xFF53ED91;
            android.graphics.Path p=new android.graphics.Path();
            int start=direction>0?0:points.length-1,step=direction>0?1:-1;
            p.moveTo(points[start][0],points[start][1]);
            for(int i=start+step;i>=0&&i<points.length;i+=step)p.lineTo(points[i][0],points[i][1]);
            stroke(withAlpha(color,0x28),48f);c.drawPath(p,paint);
            stroke(withAlpha(color,0x78),34f);c.drawPath(p,paint);
            stroke(color,7f);c.drawPath(p,paint);
            for(int i=start;i+step>=0&&i+step<points.length;i+=step){
                float[] a=points[i],b=points[i+step];
                drawPowerChevron(c,(a[0]+b[0])/2f,(a[1]+b[1])/2f,b[0]-a[0],b[1]-a[1],color);
            }
        }

        private void drawPowerChevron(android.graphics.Canvas c,float x,float y,float dx,float dy,int color){
            float len=(float)Math.hypot(dx,dy);if(len<1f)return;
            float ux=dx/len,uy=dy/len,backX=x-ux*13f,backY=y-uy*13f;
            stroke(color,7f);
            c.drawLine(backX-uy*10f,backY+ux*10f,x,y,paint);
            c.drawLine(backX+uy*10f,backY-ux*10f,x,y,paint);
        }

        /** Cache successful and failed top-down chassis loads once per variant. */
        private Bitmap getPowerChassisBitmap(String variant) {
            String key = "approved-atlas";
            if (powerGhostBitmaps.containsKey(key)) return powerGhostBitmaps.get(key);
            Bitmap bitmap = null;
            try (InputStream stream = getAssets().open(
                    "www/assets/power/graphics/approved-chassis-atlas.png")) {
                bitmap = BitmapFactory.decodeStream(stream);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Optional Power top-down chassis asset unavailable", error);
            }
            powerGhostBitmaps.put(key, bitmap);
            return bitmap;
        }

        /** Lazy and failure-tolerant: a missing optional raster never blanks the card. */
        private Bitmap getTiresTopViewBitmap() {
            if (tiresTopViewDecodeAttempted) return tiresTopViewBitmap;
            tiresTopViewDecodeAttempted = true;
            try (InputStream stream = getAssets().open("www/assets/ui/tires-top-view-v1.png")) {
                tiresTopViewBitmap = BitmapFactory.decodeStream(stream);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Optional Tires top-view asset unavailable; using vector fallback", error);
                tiresTopViewBitmap = null;
            }
            return tiresTopViewBitmap;
        }

        private void drawTiresRaster(android.graphics.Canvas c, float w, float h, Bitmap topView,
                int accent, int muted, int strong) {
            float imageH = h * .88f;
            float imageW = imageH * topView.getWidth() / Math.max(1f, topView.getHeight());
            imageW = Math.min(imageW, w * .30f);
            float left = (w - imageW) * .5f;
            float top = (h - imageH) * .5f;
            android.graphics.RectF destination = new android.graphics.RectF(
                    left, top, left + imageW, top + imageH);

            paint.setAlpha(dockUiLight ? 238 : 255);
            c.drawBitmap(topView, null, destination, paint);
            paint.setAlpha(255);
            drawTireReadouts(c, w, h, accent, muted);
        }

        private void drawTiresFallback(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            // A compact top view: the tapered body reads as a vehicle even at
            // launcher-card size. Readings stay outside the body by each corner.
            android.graphics.Path body = new android.graphics.Path();
            body.moveTo(w * .43f, h * .08f);
            body.quadTo(w * .50f, h * .03f, w * .57f, h * .08f);
            body.lineTo(w * .66f, h * .20f);
            body.lineTo(w * .69f, h * .76f);
            body.quadTo(w * .66f, h * .91f, w * .56f, h * .95f);
            body.lineTo(w * .44f, h * .95f);
            body.quadTo(w * .34f, h * .91f, w * .31f, h * .76f);
            body.lineTo(w * .34f, h * .20f);
            body.close();
            fill(withAlpha(muted, 0x20));
            c.drawPath(body, paint);
            stroke(strong, Math.max(2f, w * .026f));
            c.drawPath(body, paint);

            fill(withAlpha(accent, 0x28));
            c.drawRoundRect(w * .39f, h * .22f, w * .61f, h * .67f,
                    w * .075f, w * .075f, paint);
            stroke(muted, Math.max(1.5f, w * .018f));
            c.drawRoundRect(w * .39f, h * .22f, w * .61f, h * .67f,
                    w * .075f, w * .075f, paint);
            c.drawLine(w * .39f, h * .43f, w * .61f, h * .43f, paint);
            c.drawLine(w * .38f, h * .76f, w * .62f, h * .76f, paint);

            drawTireReadouts(c, w, h, accent, muted);
        }

        /** Compact composition: vehicle centered, pressure values beside each wheel. */
        private void drawTireReadouts(android.graphics.Canvas c, float w, float h,
                int accent, int muted) {
            String[] readings = tireReadings(descriptor);
            float[] xs = {w * .29f, w * .71f, w * .29f, w * .71f};
            float[] valueYs = {h * .31f, h * .31f, h * .79f, h * .79f};
            for (int i = 0; i < 4; i++) {
                String wheelState = descriptor.wheelStates != null
                        && i < descriptor.wheelStates.length
                        ? descriptor.wheelStates[i] : "unavailable";
                int signalColor = tireSignalColor(wheelState, muted);
                boolean left = i == 0 || i == 2;
                paint.setTextAlign(left
                        ? android.graphics.Paint.Align.RIGHT : android.graphics.Paint.Align.LEFT);
                paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.BOLD));
                paint.setTextSize(Math.max(15f, Math.min(w, h) * .205f));
                fill(signalColor);
                c.drawText(readings[i], xs[i], valueYs[i], paint);
            }
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            paint.setTextSize(Math.max(7f, Math.min(w, h) * .085f));
            fill(withAlpha(muted, 0xC8));
            String unit = descriptor.metricB.isEmpty() ? "" : " · " + descriptor.metricB;
            c.drawText(descriptor.state.toUpperCase(java.util.Locale.US) + unit,
                    w * .5f, h * .98f, paint);
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        private int tireSignalColor(String wheelState, int muted) {
            if ("warning".equals(wheelState)) return 0xFFFFB342;
            if ("unavailable".equals(wheelState) || "unavailable".equals(descriptor.state)) {
                return withAlpha(muted, 0xA8);
            }
            // Demo is provenance, not a health state: normal readings use the
            // same primary text color as live readings.
            if ("demo".equals(descriptor.state)) return dockLabelColor();
            if ("stale".equals(descriptor.state)) return 0xFFE3A642;
            return dockLabelColor();
        }

        private void drawClock(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            java.util.Calendar now = java.util.Calendar.getInstance();
            int minute = now.get(java.util.Calendar.MINUTE);
            int hour24 = now.get(java.util.Calendar.HOUR_OF_DAY);
            boolean twelve = "12h".equals(descriptor.clockFormat)
                    || ("system".equals(descriptor.clockFormat)
                    && !android.text.format.DateFormat.is24HourFormat(MainActivity.this));
            int hour = twelve ? (hour24 % 12 == 0 ? 12 : hour24 % 12) : hour24;
            String hh = String.format(java.util.Locale.US, "%02d", hour);
            String mm = String.format(java.util.Locale.US, "%02d", minute);
            String day = String.format(java.util.Locale.US, "%02d", now.get(java.util.Calendar.DAY_OF_MONTH));
            String month = new java.text.DateFormatSymbols().getShortMonths()[now.get(java.util.Calendar.MONTH)].toUpperCase(java.util.Locale.US);
            String date = day + " " + month;
            String face = descriptor.clockFace == null ? "panorama" : descriptor.clockFace;
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            if ("meridian".equals(face)) {
                float cx = w * .33f, cy = h * .50f, r = Math.min(w, h) * .38f;
                stroke(muted, Math.max(2f, w * .018f));
                c.drawCircle(cx, cy, r, paint);
                for (int i = 0; i < 12; i++) {
                    double a = Math.toRadians(i * 30 - 90);
                    c.drawLine(cx + (float)Math.cos(a) * r * .84f, cy + (float)Math.sin(a) * r * .84f,
                            cx + (float)Math.cos(a) * r * .96f, cy + (float)Math.sin(a) * r * .96f, paint);
                }
                float ma = (float)Math.toRadians(minute * 6 - 90);
                float ha = (float)Math.toRadians((hour24 % 12 + minute / 60f) * 30 - 90);
                stroke(accent, Math.max(2f, w * .028f));
                c.drawLine(cx, cy, cx + (float)Math.cos(ma) * r * .78f, cy + (float)Math.sin(ma) * r * .78f, paint);
                stroke(strong, Math.max(3f, w * .038f));
                c.drawLine(cx, cy, cx + (float)Math.cos(ha) * r * .55f, cy + (float)Math.sin(ha) * r * .55f, paint);
                fill(accent); c.drawCircle(cx, cy, Math.max(3f, w * .035f), paint);
                paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                paint.setTextSize(Math.max(18f, h * .22f)); fill(strong); c.drawText(hh, w * .57f, h * .43f, paint);
                paint.setTextSize(Math.max(18f, h * .22f)); fill(accent); c.drawText(mm, w * .57f, h * .68f, paint);
                paint.setTextSize(Math.max(7f, h * .08f)); fill(muted); c.drawText(date, w * .57f, h * .90f, paint);
            } else if ("split".equals(face)) {
                float gap = w * .045f, boxW = (w - gap) * .5f;
                fill(withAlpha(muted, 0x22)); c.drawRoundRect(0, h * .12f, boxW, h * .84f, 10f, 10f, paint);
                c.drawRoundRect(boxW + gap, h * .12f, w, h * .84f, 10f, 10f, paint);
                paint.setTextAlign(android.graphics.Paint.Align.CENTER); paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
                paint.setTextSize(Math.max(28f, h * .48f)); fill(strong); c.drawText(hh, boxW * .5f, h * .62f, paint); c.drawText(mm, boxW + gap + boxW * .5f, h * .62f, paint);
                paint.setTextSize(Math.max(7f, h * .08f)); fill(muted); c.drawText(date, w * .5f, h * .94f, paint);
            } else if ("date-spine".equals(face)) {
                float spine = w * .24f;
                fill(withAlpha(accent, 0x28)); c.drawRoundRect(0, 0, spine, h, 9f, 9f, paint);
                paint.setTextAlign(android.graphics.Paint.Align.CENTER); paint.setTextSize(Math.max(10f, h * .13f)); fill(accent); c.drawText(day, spine * .5f, h * .45f, paint);
                paint.setTextSize(Math.max(7f, h * .08f)); fill(muted); c.drawText(month, spine * .5f, h * .64f, paint);
                paint.setTextAlign(android.graphics.Paint.Align.LEFT); paint.setTextSize(Math.max(26f, h * .43f)); fill(strong); c.drawText(hh + ":" + mm, spine + w * .07f, h * .60f, paint);
                paint.setTextSize(Math.max(7f, h * .08f)); fill(muted); c.drawText(twelve ? (hour24 >= 12 ? "PM" : "AM") : "LOCAL TIME", spine + w * .07f, h * .83f, paint);
            } else {
                paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                paint.setTextSize(Math.max(34f, h * .58f)); fill(strong); c.drawText(hh + ":" + mm, w * .02f, h * .62f, paint);
                paint.setTextSize(Math.max(8f, h * .10f)); fill(accent); c.drawText(date + (twelve ? (hour24 >= 12 ? "  PM" : "  AM") : "  LOCAL"), w * .03f, h * .90f, paint);
                stroke(accent, Math.max(2f, w * .012f)); c.drawLine(w * .02f, h * .72f, w * .98f, h * .72f, paint);
            }
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        /**
         * The three driving rail cards draw the SELECTED mode's own glyph, not a
         * generic gauge — the same identity the DRIVING widget and popup use, so
         * one mode reads as one thing everywhere. `descriptor.state` carries which
         * mode; an unknown value falls back to the neutral steering wheel rather
         * than picking a mode we were not told about.
         *
         * Each glyph is drawn into a unit box and scaled once, so adding a mode is
         * a path, not a new set of magic ratios.
         */
        private void drawDriveMode(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            float size = Math.min(w, h) * .68f;
            String mode = descriptor.state == null ? "" : descriptor.state;
            boolean known = !"unknown".equals(mode) && !mode.isEmpty();
            if (!descriptor.glyphText.isEmpty()) {
                drawCodeBadge(c, descriptor.glyphText, w * .5f, h * .44f,
                        w * .60f, h * .40f, known ? accent : muted, known);
            } else {
                drawGlyphPath(c, descriptor.glyph, w * .5f, h * .44f, size,
                        known ? accent : muted, Math.max(2f, size * .085f));
            }
            drawStepDots(c, w, h, 3, descriptor.progress, accent, muted);
        }

        /**
         * Power mode is a hybrid split, so draw it as one: a battery that fills
         * with the electric share and a fuel drop that fades as it stops being
         * used. EV empties the drop entirely, HEV shows both.
         */
        private void drawPowerMode(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            String mode = descriptor.state == null ? "" : descriptor.state;
            boolean known = "hev".equals(mode) || "evp".equals(mode) || "ev".equals(mode);
            String code = known ? mode.toUpperCase(java.util.Locale.US) : "--";
            drawCodeBadge(c, code, w * .5f, h * .46f, w * .74f, h * .46f,
                    known ? accent : muted, known);
            drawStepDots(c, w, h, 3, descriptor.progress, accent, muted);
        }

        /**
         * A short code in a rounded box — the POWER card's HEV / EVP / EV, and
         * AWD's 4x4. Some modes have no mark that survives 52px, and type is
         * always legible where line art is not.
         */
        private void drawCodeBadge(android.graphics.Canvas c, String code,
                float cx, float cy, float boxW, float boxH, int color, boolean known) {
            oval.set(cx - boxW * .5f, cy - boxH * .5f, cx + boxW * .5f, cy + boxH * .5f);
            fill(withAlpha(color, known ? 0x22 : 0x14));
            c.drawRoundRect(oval, boxH * .30f, boxH * .30f, paint);
            stroke(color, Math.max(1.5f, boxH * .042f));
            c.drawRoundRect(oval, boxH * .30f, boxH * .30f, paint);

            // Size to the box rather than to a constant: EVP is three glyphs
            // where EV is two, and a fixed size clips one or floats the other.
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(color);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setLetterSpacing(.06f);
            float textSize = boxH * .62f;
            paint.setTextSize(textSize);
            float maxWidth = boxW * .78f;
            float measured = paint.measureText(code);
            if (measured > maxWidth) paint.setTextSize(textSize * maxWidth / measured);
            android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
            c.drawText(code, cx, cy - (fm.ascent + fm.descent) * .5f, paint);
            paint.setLetterSpacing(0f);
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
        }

        /**
         * Recovery is an ordinal level, so it is three rising bars and nothing
         * else. One-pedal replaces the level rather than extending it, so it
         * lights every bar and is named on the card's value line instead of
         * being faked as a fourth step.
         */
        private void drawRegen(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            String mode = descriptor.state == null ? "" : descriptor.state;
            boolean onePedal = "onepedal".equals(mode);
            int level = onePedal ? 3 : ("level1".equals(mode) ? 1
                    : ("level2".equals(mode) ? 2 : ("level3".equals(mode) ? 3 : 0)));
            float bw = w * .17f, gap = w * .10f;
            float base = h * .70f;
            float left = w * .5f - (bw * 3f + gap * 2f) * .5f;
            float tall = h * .46f;
            for (int i = 0; i < 3; i++) {
                float bh = tall * (.40f + i * .30f);
                float x = left + i * (bw + gap);
                fill(i < level ? accent : muted);
                c.drawRoundRect(x, base - bh, x + bw, base, bw * .32f, bw * .32f, paint);
            }
            if (onePedal) {
                // A tie under the bars: one control now covers all three levels.
                stroke(accent, Math.max(1.5f, w * .026f));
                c.drawLine(left, base + h * .10f, left + bw * 3f + gap * 2f, base + h * .10f, paint);
            }
            drawStepDots(c, w, h, 3, descriptor.progress, accent, muted);
        }

        /**
         * Position within a fixed set of options. `progress` arrives as
         * (index+1)/count, so recovering the index keeps the dots honest for any
         * option count without a second payload field.
         */
        private void drawStepDots(android.graphics.Canvas c, float w, float h,
                int count, int progress, int accent, int muted) {
            if (count <= 1) return;
            int index = progress <= 0 ? -1 : Math.round(count * progress / 100f) - 1;
            float r = Math.max(1.5f, w * .022f);
            float gap = r * 3.1f;
            float y = h * .90f;
            float left = w * .5f - (count - 1) * gap * .5f;
            for (int i = 0; i < count; i++) {
                fill(i == index ? accent : muted);
                c.drawCircle(left + i * gap, y, i == index ? r * 1.35f : r, paint);
            }
        }

        /**
         * Draw a flattened glyph path, centred at (cx, cy) and scaled from its
         * 24x24 authoring grid to `size`.
         *
         * The command subset is guaranteed by scripts/build-drive-mode-glyphs.mjs
         * (absolute M/L/C/Z, one space after every letter), which is what keeps
         * this a tokenless split rather than an SVG parser on the head unit.
         */
        private void drawGlyphPath(android.graphics.Canvas c, String data,
                float cx, float cy, float size, int color, float width) {
            if (data == null || data.isEmpty()) return;
            android.graphics.Path path = new android.graphics.Path();
            String[] parts = data.split(" ");
            float[] n = new float[6];
            int i = 0;
            try {
                while (i < parts.length) {
                    String token = parts[i++];
                    if (token.isEmpty()) continue;
                    char command = token.charAt(0);
                    if (command == 'Z') { path.close(); continue; }
                    int count = command == 'C' ? 6 : 2;
                    if (i + count > parts.length) return;
                    for (int k = 0; k < count; k++) n[k] = Float.parseFloat(parts[i++]);
                    if (command == 'M') path.moveTo(n[0], n[1]);
                    else if (command == 'L') path.lineTo(n[0], n[1]);
                    else if (command == 'C') path.cubicTo(n[0], n[1], n[2], n[3], n[4], n[5]);
                    else return;
                }
            } catch (NumberFormatException error) {
                return;   // A malformed glyph draws nothing; it never crashes the rail.
            }
            android.graphics.Matrix m = new android.graphics.Matrix();
            float scale = size / 24f;
            m.setScale(scale, scale);
            m.postTranslate(cx - size * .5f, cy - size * .5f);
            path.transform(m);
            stroke(color, width);
            c.drawPath(path, paint);
        }

        private void drawRoof(android.graphics.Canvas c, float w, float h,
                int accent, int muted, int strong) {
            android.graphics.Path shell = new android.graphics.Path();
            shell.moveTo(w * .42f, h * .07f);
            shell.quadTo(w * .50f, h * .02f, w * .58f, h * .07f);
            shell.lineTo(w * .68f, h * .22f);
            shell.lineTo(w * .66f, h * .82f);
            shell.quadTo(w * .62f, h * .94f, w * .50f, h * .96f);
            shell.quadTo(w * .38f, h * .94f, w * .34f, h * .82f);
            shell.lineTo(w * .32f, h * .22f);
            shell.close();
            fill(withAlpha(muted, 0x1D));
            c.drawPath(shell, paint);
            stroke(strong, Math.max(2f, w * .025f));
            c.drawPath(shell, paint);

            float glassL = w * .39f, glassR = w * .61f;
            float glassT = h * .20f, glassB = h * .76f;
            fill(withAlpha(accent, 0x38));
            c.drawRoundRect(glassL, glassT, glassR, glassB,
                    w * .07f, w * .07f, paint);
            stroke(accent, Math.max(2f, w * .025f));
            c.drawRoundRect(glassL, glassT, glassR, glassB,
                    w * .07f, w * .07f, paint);
            stroke(muted, Math.max(1.5f, w * .018f));
            c.drawLine(glassL, h * .47f, glassR, h * .47f, paint);
            c.drawLine(w * .37f, h * .81f, w * .63f, h * .81f, paint);

            float ratio = Math.max(0f, Math.min(1f, descriptor.progress / 100f));
            float shadeBottom = glassB - (glassB - glassT) * ratio;
            fill(withAlpha(strong, 0x36));
            c.drawRoundRect(glassL + w * .018f, glassT + h * .018f,
                    glassR - w * .018f, Math.max(glassT + h * .04f, shadeBottom),
                    w * .05f, w * .05f, paint);
            stroke(accent, Math.max(3f, w * .045f));
            c.drawLine(w * .29f, h * .26f, w * .29f, h * .74f, paint);
            c.drawLine(w * .71f, h * .26f, w * .71f, h * .74f, paint);
            fill(accent);
            c.drawCircle(w * .29f, h * (.74f - .48f * ratio),
                    Math.max(3f, w * .04f), paint);
            c.drawCircle(w * .71f, h * (.74f - .48f * ratio),
                    Math.max(3f, w * .04f), paint);
        }

        private void drawDesktops(android.graphics.Canvas c, float w, float h,
                int accent, int muted) {
            float gap = w * .07f, cw = w * .30f, ch = h * .27f;
            float left = (w - cw * 2f - gap) * .5f, top = (h - ch * 2f - gap) * .5f;
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    fill(row == 0 && col == 0 ? withAlpha(accent, 0xCC) : muted);
                    float x = left + col * (cw + gap), y = top + row * (ch + gap);
                    c.drawRoundRect(x, y, x + cw, y + ch, w * .04f, w * .04f, paint);
                }
            }
        }
    }
    /** Content shown to the right of the mode drawer: apps | layout | config. */
    private String stripMode = "apps";
    private boolean drawerExpanded;
    private int dockCellPx;
    private int dockIconPx;
    private int dockIconRowTopPadPx;
    /** Launcher icons, left to right — the order the boot reveal staggers them in. */
    private final List<MotionTrailLayout> launcherItems = new ArrayList<>();
    private ProjectionPresence projectionPresence;
    private PlaceGlance placeGlance;
    private TripRecorder tripRecorder;
    private final java.util.Map<String, Bitmap> packageIconBitmaps =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> packageIconMiss =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private MotionTrailLayout projectionItem;
    /** Waiting for Impulse to resolve a projection display task id. */
    private ProjectionPresence.Kind pendingProjectionKind;
    private final java.util.Set<String> pendingProjectionPackages = new java.util.HashSet<>();
    private Runnable pendingProjectionTimeout;
    /**
     * Single GWM hub that opens a flyout of {@link #PINNED_PACKAGES}. Destinations
     * still tracked in {@link #pinnedBound}; only the hub is drawn on the strip.
     */
    private MotionTrailLayout gwmHubItem;
    /** Launcher tile that enters Side by Side (the two-app split). Not a package. */
    private MotionTrailLayout sideBySideItem;
    /** Layout manager → Cards is open on the page: the rail jiggles and can be dragged. */
    private boolean railEditMode;
    /** Card the Layout manager just added; the rail scrolls to it once. */
    private String railFocusId = "";
    /** The Workspace rail card is showing its Layout face (2x2 Layout manager shortcuts). */
    private boolean workspaceLayoutMode;
    /** Layout manager screen open on the page: "desktops", "layout", "appearance" or "". */
    private String studioScreen = "";
    private View workspaceActionsFace;
    private View workspaceLayoutFace;
    private android.widget.TextView workspaceHeading;
    private final java.util.List<android.widget.TextView> workspaceLayoutCells = new ArrayList<>();
    private final java.util.Map<View, android.animation.Animator> railJiggles = new java.util.HashMap<>();
    /** Which pinned destinations are installed (or emulator-stubbed). */
    private final boolean[] pinnedBound = new boolean[PINNED_PACKAGES.length];
    private android.widget.PopupWindow gwmHubMenu;
    private android.widget.PopupWindow dockCustomizeSheet;
    /** Impulse-style name/icon/color overrides for dock tiles. */
    private DockAppOverrides dockAppOverrides = new DockAppOverrides(null);
    private final MotionTrailLayout[] recentItems = new MotionTrailLayout[3];
    private final List<String> recentPackages = new ArrayList<>();
    /** User-hidden packages (long-press → Hide). Survives restarts. */
    private final java.util.Set<String> hiddenPackages = new java.util.HashSet<>();
    /** Emulator-only launcher stubs — taps toast instead of launching. */
    private final java.util.Set<String> emulatorStubPackages = new java.util.HashSet<>();
    private Boolean emulatorDevice;
    /**
     * Packages that should open as freeform windows outside APP+APP split.
     * Default off — everything else goes fullscreen. Long-press → "Open as window".
     */
    private final java.util.Set<String> windowPackages = new java.util.HashSet<>();
    /** Main-strip icons keyed by package so recents can hide the duplicate. */
    private final java.util.Map<String, MotionTrailLayout> dockItemsByPackage =
            new java.util.LinkedHashMap<>();
    private android.widget.PopupWindow dockEditMenu;
    /** Long-press → "Unhide selected…" list. */
    private android.widget.PopupWindow unhidePicker;
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
    private String shellMode = SHELL_APP_CAR;
    private String launchSidePref = "auto";
    private boolean nextLaunchLeft = true;
    /** Side that most recently received a freeform launch (for L/R HUD). */
    private String lastLaunchSide = "";
    private String leftSlotUse = "app";
    private String rightSlotUse = "app";
    /** APP+APP width split: "1:1", "2:1" (left heavy), "1:2" (right heavy). */
    private String splitRatio = "1:1";
    /** Packages to auto-launch once after a cold start into saved APP+APP. */
    private String pendingRestoreLeft = "";
    private String pendingRestoreRight = "";
    private boolean appsOnlyRestoreDone;
    /** Native always-on-top FAB for APP+APP (system overlay above freeforms). */
    private View appsFabRoot;
    private android.widget.LinearLayout appsFabMenu;
    private android.widget.TextView appsFabButton;
    private WindowManager.LayoutParams appsFabLp;
    private boolean appsFabOverlayAttached;
    private boolean appsFabUsesSystemOverlay;
    private boolean appsFabMenuOpen;
    private boolean appsFabDragging;
    private float appsFabDownRawX;
    private float appsFabDownRawY;
    private int appsFabDownLpX;
    private int appsFabDownLpY;
    private int appsFabPosX = Integer.MIN_VALUE;
    private int appsFabPosY = Integer.MIN_VALUE;
    /** Button top (screen coords) while the menu is closed — anchor when opening upward. */
    private int appsFabBtnAnchorY = Integer.MIN_VALUE;
    /** Boot default when APP+APP is active but not explicitly saved. */
    private String lastNonAppsShellMode = SHELL_APP_CAR;
    private View appsFabScrim;
    private WindowManager.LayoutParams appsFabScrimLp;
    private boolean appsFabScrimAttached;
    /** Last CSS-mapped screen rect of the desktop switcher; the accessibility overlay consumes it. */
    private int desktopHitX;
    private int desktopHitY;
    private int desktopHitW;
    private int desktopHitH;
    private boolean desktopHitWanted;
    private Runnable pinMediaBoundsRunnable;
    private final MediaNowPlaying mediaNowPlaying = new MediaNowPlaying();
    /** Full-width 2px load line at display Y=655 (65px above the 720px panel). */
    private View bootProgressTrack;
    private View bootProgressFill;
    /** Subtle center spinner once the splash is gone but the GLB is not ready. */
    private View bootCenterLoader;
    private boolean bootHudCenterMode;
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
    private static final String SHELL_APP_CAR = "appCar";
    private static final String SHELL_APPS = "appsOnly";
    private static final String PREFS_SHELL = "h6_shell";
    private static final String PREF_DOCK_SURFACE = "dockSurfaceMode";
    private static final String DOCK_SURFACE_LAUNCHER = "launcher";
    private static final String DOCK_SURFACE_CARDS = "cards";
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
        int top = slotBandTopPx();
        int bottom = slotBandBottomPx();
        if (SHELL_APPS.equals(shellMode)) {
            int inset = Math.max(6, Math.round(d.width() * 0.004f));
            int gap = Math.max(6, Math.round(d.width() * 0.004f));
            int mid = splitDividerX(d);
            return clampSlot(new Rect(d.left + inset, top,
                    mid - gap / 2, bottom), d);
        }
        if (SHELL_APP_CAR.equals(shellMode)) {
            return clampSlot(new Rect(
                    d.left + Math.round(d.width() * 0.016f),
                    top,
                    d.left + Math.round(d.width() * 0.54f),
                    bottom), d);
        }
        return clampSlot(scaleToUsable(LEFT_POPUP_BOUNDS, d), d);
    }

    private Rect rightFreeformBounds() {
        Rect d = usableDisplayRect();
        int top = slotBandTopPx();
        int bottom = slotBandBottomPx();
        if (SHELL_APPS.equals(shellMode)) {
            int inset = Math.max(6, Math.round(d.width() * 0.004f));
            int gap = Math.max(6, Math.round(d.width() * 0.004f));
            int mid = splitDividerX(d);
            return clampSlot(new Rect(mid + gap / 2, top,
                    d.right - inset, bottom), d);
        }
        return clampSlot(scaleToUsable(RIGHT_APP_BOUNDS, d), d);
    }

    /** Display X of the APP+APP divider from {@link #splitRatio}. */
    private int splitDividerX(Rect d) {
        float frac = 0.5f;
        if ("2:1".equals(splitRatio)) frac = 2f / 3f;
        else if ("1:2".equals(splitRatio)) frac = 1f / 3f;
        return d.left + Math.round(d.width() * frac);
    }

    private static boolean isValidSplitRatio(String ratio) {
        return "1:1".equals(ratio) || "2:1".equals(ratio) || "1:2".equals(ratio);
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
                widestTopInsetPx = Math.max(widestTopInsetPx, insets.getSystemWindowInsetTop());
                // Use the remembered values, not the live read. Opening a slot sets
                // FLAG_LAYOUT_NO_LIMITS (see applyLauncherFocusPolicy), which makes
                // this window full-bleed and drops these insets to 0 — so a rect
                // measured before the launch and one measured after disagreed by the
                // rail and header, and every consumer recomputed a slot the freeform
                // window was no longer in. The bars are the MMI's own and draw over
                // every app window whatever the insets say.
                r.left += widestNavInsetPx;
                r.top += widestTopInsetPx;
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

    /**
     * Map a constant authored in full-panel pixels onto the usable area.
     * <p>
     * X still follows the OEM's side band — that is where the nav rail actually
     * is. Y deliberately does NOT. Scaling the authored height into whatever the
     * OEM bars leave both pushed the boards down by the header height and
     * squashed them ~8% (measured on the car: board top 152 against the
     * emulator's 52), and {@link #usableDisplayRect()}'s bottom cannot be
     * trusted for it anyway — see {@link #slotBandBottomPx()}. The panel is
     * exactly {@link #SLOT_REFERENCE}, so an authored Y already IS a display
     * coordinate; {@link #clampSlot} holds it inside the fixed band.
     */
    private Rect scaleToUsable(Rect reference, Rect usable) {
        float sx = usable.width() / (float) SLOT_REFERENCE.width();
        return new Rect(
                usable.left + Math.round(reference.left * sx),
                reference.top,
                usable.left + Math.round(reference.right * sx),
                reference.bottom);
    }

    /**
     * Top band kept clear of freeform slots. Layout / 3D chrome moved into the
     * launcher drawer, so this is only breathing room under the MMI header /
     * brand — widgets grow into the old 96dp button band.
     */
    private int chromeReservePx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(28f * density);
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
        int top = Math.max(r.top, slotBandTopPx());
        int bottom = Math.min(r.bottom, slotBandBottomPx());
        if (bottom - top < 200) bottom = top + 200;
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

    /**
     * Gap between the launcher icon strip and the OEM climate dock.
     * Keep the strip immediately above Impulse's reserved 60px bottom bar.
     * Overscan compensation below converts this display-space gap correctly.
     */
    private int launcherBottomGapPx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(60f * density);
    }

    /**
     * Bottom offset for FPS / SKIP / toasts (CSS --hv-launcher-bottom).
     * Sits in the strip's lower padding above the OEM climate dock — same
     * relationship as before: 20dp above the Impulse reserve.
     */
    private int chromeBottomGapPx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(80f * density);
    }

    /**
     * Impulse's persistent bar runs {@code wm overscan 0,0,0,N}, which shortens our
     * window from the bottom. Gravity.BOTTOM chrome would ride that shrink and jump
     * up; subtract this from bottom margins so screen Y stays fixed.
     */
    private int overscanBottomPx() {
        android.graphics.Point real = new android.graphics.Point();
        try {
            getWindowManager().getDefaultDisplay().getRealSize(real);
        } catch (Exception ignored) {}
        if (real.y <= 0) return 0;
        return Math.max(0, real.y - pageOriginRect().bottom);
    }

    /**
     * Design gap minus Impulse overscan, floored at 0 so large per-app overscans
     * pin chrome to the window bottom instead of clipping into the bar.
     */
    private int compensatedBottomMarginPx(int designGapPx) {
        return Math.max(0, designGapPx - overscanBottomPx());
    }

    /** Keep strip / SKIP at a fixed display Y when Impulse overscan changes. */
    private void applyLauncherStripBottomMargin() {
        int overscan = overscanBottomPx();
        if (overscan != loggedOverscanBottomPx) {
            loggedOverscanBottomPx = overscan;
            Log.w(TAG, "Overscan bottom=" + overscan + "px (compensating chrome)");
        }
        if (stripContainer != null) {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) stripContainer.getLayoutParams();
            if (lp != null) {
                int margin = compensatedBottomMarginPx(launcherBottomGapPx());
                if (lp.bottomMargin != margin) {
                    lp.bottomMargin = margin;
                    stripContainer.setLayoutParams(lp);
                }
            }
        }
        applyImpulseReserveBandGeometry();
        alignQuickCardsToWidgetBoard();
        if (splashSkipBtn != null) {
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) splashSkipBtn.getLayoutParams();
            if (lp != null) {
                int margin = compensatedBottomMarginPx(chromeBottomGapPx());
                if (lp.bottomMargin != margin) {
                    lp.bottomMargin = margin;
                    splashSkipBtn.setLayoutParams(lp);
                }
            }
        }
    }

    /**
     * Bottom band freeform slots must clear: strip height + bottom gap + pad.
     * Strip is {@link #LAUNCHER_STRIP_HEIGHT_DP} above the 60dp Impulse reserve.
     * APP+APP uses a native always-on-top FAB, so freeforms take the full
     * usable height (no dock / FAB pocket).
     * <p>
     * This used to hardcode 140dp for a strip that is really 148dp, and spend
     * the remaining 8dp as the gap — so on the car the widget boards' bottom
     * (512) landed exactly on the quick-card strip's top (512), with nothing
     * between them. Derive it from the strip's own constant instead.
     */
    private int dockReservePx() {
        if (SHELL_APPS.equals(shellMode)) return 0;
        float density = getResources().getDisplayMetrics().density;
        return launcherStripHeightPx() + launcherBottomGapPx()
                + Math.round(SLOT_DOCK_GAP_DP * density);
    }

    /**
     * Height of the native dock strip — launcher icons or quick cards. Shared
     * with the strip's own layout params so {@link #dockReservePx()} cannot
     * drift from the view it is reserving for.
     */
    private static final float LAUNCHER_STRIP_HEIGHT_DP = 148f;
    /** Breathing room between the widget boards and the dock strip. */
    private static final float SLOT_DOCK_GAP_DP = 16f;

    private int launcherStripHeightPx() {
        return Math.round(LAUNCHER_STRIP_HEIGHT_DP * getResources().getDisplayMetrics().density);
    }

    /**
     * Panel height in DISPLAY pixels. Never the window's: Impulse shortens our
     * window from the bottom (measured: mFrame [0,0][1920,700] on a 1920x720
     * panel), and a band measured against that moves whenever it changes.
     */
    private int panelHeightPx() {
        android.graphics.Point real = new android.graphics.Point();
        try {
            getWindowManager().getDefaultDisplay().getRealSize(real);
        } catch (Exception ignored) {}
        if (real.y > 0) return real.y;
        return getResources().getDisplayMetrics().heightPixels;
    }

    /**
     * Vertical band the widget boards and freeform slots live in, in DISPLAY
     * pixels — a fixed frame on the panel, not a fraction of whatever the OEM
     * bars leave.
     * <p>
     * The old derivation came from {@link #usableDisplayRect()}, which mixes the
     * real display height with insets read off our own window. The 20px the OEM
     * keeps at the bottom is invisible to those insets (the window is already
     * short by exactly that much), so every slot bottom sat 20px low; and the
     * 60px header was subtracted AND scaled, dropping the boards 100px and
     * squashing them. Anchoring both edges to the panel makes the car and the
     * emulator lay out identically and leaves nothing for overscan to move.
     * <p>
     * {@link #slotBandTopPx()} is the MINIMUM clearance, not the design top:
     * APP+CAR and APP+APP
     * deliberately fill the height under the OEM header, while the widget-board
     * modes carry their own larger authored top ({@link #LEFT_POPUP_BOUNDS})
     * through {@link #clampSlot}.
     */
    private int slotBandTopPx() {
        return Math.max(statusBarHeightPx(), widestTopInsetPx) + chromeReservePx();
    }

    private int slotBandBottomPx() {
        return Math.max(slotBandTopPx() + 200, panelHeightPx() - dockReservePx());
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
     * Bounds in CSS viewport coordinates for a popup that must live between the
     * actual MMI header and the first native dock target. The lower boundary is
     * intentionally 12dp above the dock rather than a guessed dock reserve.
     */
    private String popupVerticalBoundsToCssFragment() {
        Rect origin = pageOriginRect();
        int headerBottomOnDisplay = Math.max(statusBarHeightPx(), widestTopInsetPx);
        int popupTop = Math.max(0, headerBottomOnDisplay - origin.top);
        int popupBottom = origin.height();
        if (stripContainer != null && stripContainer.getHeight() > 0) {
            int[] dockLocation = new int[2];
            stripContainer.getLocationOnScreen(dockLocation);
            int gap = Math.round(12f * getResources().getDisplayMetrics().density);
            popupBottom = Math.max(popupTop, dockLocation[1] - origin.top - gap);
        }
        // This is deliberately a property fragment, not an object literal: it is
        // inserted into the larger onAndroidShellLayout object below.
        return "popupTop:" + cssPx(popupTop) + ",popupBottom:" + cssPx(popupBottom);
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
        // AA / CarPlay audio is published by MediaCenter, but starting
        // MediaCenter MAIN skips the track. Raise the projection task instead.
        if (ProjectionPresence.isProjectionPackage(packageName)) {
            mainHandler.post(() -> launchProjection(
                    ProjectionPresence.kindForPackage(packageName)));
            return;
        }
        if (emulatorStubPackages.contains(packageName)) {
            String msg = label != null && !label.isEmpty() ? label : packageName;
            android.widget.Toast.makeText(this, msg + " (emulator stub)", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        rememberRecentApp(packageName);
        if (CAR_SETTINGS_PACKAGE.equals(packageName)) {
            launchAndroidSettingsRoot();
            return;
        }
        if (isGwmApp(packageName) || isPinnedPackage(packageName)) {
            launchAppFullscreen(packageName);
            return;
        }
        // Fullscreen by default. Freeform only in APP+APP split, or when the
        // user opted this package into "Open as window" from the dock menu.
        if (!shouldLaunchAsWindow(packageName)) {
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

    /** True when this package should open freeform rather than fullscreen. */
    private boolean shouldLaunchAsWindow(String packageName) {
        return SHELL_APPS.equals(shellMode) || prefersWindow(packageName);
    }

    private boolean prefersWindow(String packageName) {
        return packageName != null && windowPackages.contains(packageName);
    }

    private boolean canToggleOpenAsWindow(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(getPackageName()) || isPinnedPackage(pkg) || isGwmApp(pkg)) return false;
        return !ProjectionPresence.isProjectionPackage(pkg);
    }

    private void loadShellPrefs() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE);
        // One car layout remains ("full size", stored as appCar). A saved
        // "triple", or anything unknown, boots into it; Side by Side stays.
        String mode = prefs.getString("mode", SHELL_APP_CAR);
        if (!SHELL_APPS.equals(mode)) mode = SHELL_APP_CAR;
        lastNonAppsShellMode = SHELL_APP_CAR;
        shellMode = mode;
        launchSidePref = prefs.getString("launchSide", "auto");
        nextLaunchLeft = prefs.getBoolean("nextLeft", true);
        String ratio = prefs.getString("splitRatio", "1:1");
        splitRatio = isValidSplitRatio(ratio) ? ratio : "1:1";
        pendingRestoreLeft = prefs.getString("leftApp", "");
        pendingRestoreRight = prefs.getString("rightApp", "");
        dockSurfaceMode = normalizeDockSurfaceMode(
                prefs.getString(PREF_DOCK_SURFACE, DOCK_SURFACE_LAUNCHER));
        appsOnlyRestoreDone = false;
        loadAppsFabPos();
        uiMode = readUiModePref();
        loadSlotUsesForMode();
        refreshDockSurfaceUi(false);
    }

    private String normalizeDockSurfaceMode(String mode) {
        return DOCK_SURFACE_CARDS.equals(mode)
                ? DOCK_SURFACE_CARDS : DOCK_SURFACE_LAUNCHER;
    }

    private void persistDockSurfaceMode() {
        getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .edit()
                .putString(PREF_DOCK_SURFACE, dockSurfaceMode)
                .apply();
    }

    /**
     * Native changes are optimistic so the control feels immediate. The next
     * updateDockIndicators callback is authoritative and can settle us back to
     * the page's persisted desktop state.
     */
    private void chooseDockSurface(String mode, boolean notifyPage) {
        dockSurfaceMode = normalizeDockSurfaceMode(mode);
        persistDockSurfaceMode();
        refreshDockSurfaceUi(true);
        if (notifyPage) {
            callViewerDock(DOCK_SURFACE_CARDS.equals(dockSurfaceMode)
                    ? "showCards" : "showLauncher");
        }
    }

    private void toggleDockSurface() {
        dockSurfaceMode = DOCK_SURFACE_CARDS.equals(dockSurfaceMode)
                ? DOCK_SURFACE_LAUNCHER : DOCK_SURFACE_CARDS;
        persistDockSurfaceMode();
        refreshDockSurfaceUi(true);
        callViewerDock("toggleDockMode");
    }

    private void saveShellPrefs() {
        android.content.SharedPreferences.Editor ed = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE).edit();
        String bootMode = shellMode;
        if (SHELL_APPS.equals(shellMode)) {
            bootMode = lastNonAppsShellMode;
            ed.putString("lastNonAppsMode", lastNonAppsShellMode);
        } else {
            lastNonAppsShellMode = shellMode;
            ed.putString("lastNonAppsMode", lastNonAppsShellMode);
        }
        ed.putString("mode", bootMode);
        ed.putString("launchSide", launchSidePref);
        ed.putBoolean("nextLeft", nextLaunchLeft);
        ed.putString("uiMode", uiMode);
        ed.putString("splitRatio", splitRatio);
        ed.apply();
    }

    private void applyShellMode(String mode) {
        if (mode == null) return;
        if (!SHELL_APPS.equals(mode)) mode = SHELL_APP_CAR;
        String prev = shellMode;
        lastNonAppsShellMode = SHELL_APP_CAR;
        shellMode = mode;
        saveShellPrefs();
        loadSlotUsesForMode();
        updateSlotAnchors();

        boolean leftOpen = activePopupPackage != null && !activePopupPackage.isEmpty();
        boolean rightOpen = activeMediaPackage != null && !activeMediaPackage.isEmpty();

        // Leaving Dual App Split Screen: tuck the freeforms away (remove tasks,
        // keep processes — closer to minimize than force-stop).
        if (SHELL_APPS.equals(prev) && !SHELL_APPS.equals(shellMode)) {
            minimizeAppsOnlySlots();
            leftOpen = false;
            rightOpen = false;
        }

        // APP+CAR has no right freeform slot — close the right app if open.
        if (SHELL_APP_CAR.equals(shellMode) && rightOpen) {
            dismissMediaPopup(activeMediaPackage);
            rightOpen = false;
        }

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
        refreshLayoutChipSelection();
        updateAppsOnlyChrome();
        notifyViewerShellLayout();
    }

    /** Hide freeform APP+APP slots without force-stopping the packages. */
    private void minimizeAppsOnlySlots() {
        String left = activePopupPackage;
        String right = activeMediaPackage;
        if (left != null && !left.isEmpty()) {
            int taskId = activePopupTaskId >= 0 ? activePopupTaskId : findTaskIdForPackage(left);
            if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
            removeTaskById(taskId);
            clearLeftSlot();
        }
        if (right != null && !right.isEmpty()) {
            int taskId = activeMediaTaskId >= 0 ? activeMediaTaskId : findTaskIdForPackage(right);
            if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);
            removeTaskById(taskId);
            clearRightSlot();
        }
        Log.w(TAG, "Minimized Dual App slots");
    }

    /** Hide the bottom dock/drawer in APP+APP; show the native always-on-top FAB. */
    private void updateAppsOnlyChrome() {
        applyImpulseReserveBandGeometry();
        if (SHELL_APPS.equals(shellMode)) {
            if (stripContainer != null) stripContainer.setVisibility(View.GONE);
            showAppsFabOverlay();
        } else {
            dismissAppsFabOverlay();
            updateLauncherStripVisibility();
        }
    }

    /**
     * The Studio is a web popup, not a replacement for the native dock. Keeping the rail
     * visible preserves the user's way back to Apps while a Studio pane is open. Shell mode
     * and boot reveal are the only visibility owners; indicator updates must not override them.
     */
    private void updateLauncherStripVisibility() {
        if (stripContainer == null) return;
        stripContainer.setVisibility(launcherRevealed && !SHELL_APPS.equals(shellMode)
                ? View.VISIBLE : View.GONE);
        applyImpulseReserveBandGeometry();
    }

    private void loadAppsFabPos() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE);
        appsFabPosX = prefs.getInt("fabX", Integer.MIN_VALUE);
        appsFabPosY = prefs.getInt("fabY", Integer.MIN_VALUE);
    }

    private void saveAppsFabPos() {
        getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .edit()
                .putInt("fabX", appsFabPosX)
                .putInt("fabY", appsFabPosY)
                .apply();
    }

    private void showAppsFabOverlay() {
        if (appsFabOverlayAttached) {
            refreshAppsFabMenu();
            return;
        }
        ensureAppsFabBuilt();
        if (appsFabRoot == null) return;

        if (tryAttachAppsFabSystemOverlay()) {
            appsFabUsesSystemOverlay = true;
            appsFabOverlayAttached = true;
            onAppsFabAttached("system-overlay");
            return;
        }
        if (tryAttachAppsFabInWindow()) {
            appsFabUsesSystemOverlay = false;
            appsFabOverlayAttached = true;
            onAppsFabAttached("in-window");
            return;
        }
        Log.w(TAG, "APP+APP FAB could not attach (overlay denied and no rootLayout)");
    }

    private void onAppsFabAttached(String how) {
        refreshAppsFabMenu();
        updateSlotAnchors();
        if (activePopupPackage != null && !activePopupPackage.isEmpty()) {
            pinSlotToBounds(activePopupPackage, activePopupComponent,
                    activePopupTaskId, leftFreeformBounds(), false);
        }
        if (activeMediaPackage != null && !activeMediaPackage.isEmpty()) {
            pinSlotToBounds(activeMediaPackage, activeMediaComponent,
                    activeMediaTaskId, rightFreeformBounds(), true);
        }
        notifyViewerShellLayout();
        Log.w(TAG, "APP+APP FAB attached (" + how + ") at " + appsFabPosX + "," + appsFabPosY);
    }

    private boolean tryAttachAppsFabSystemOverlay() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — will try in-window FAB");
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        try {
            if (appsFabLp == null) appsFabLp = buildAppsFabWindowLp();
            appsFabLp.x = appsFabPosX;
            appsFabLp.y = appsFabPosY;
            clampAppsFabPosition();
            appsFabLp.x = appsFabPosX;
            appsFabLp.y = appsFabPosY;
            getWindowManager().addView(appsFabRoot, appsFabLp);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "System overlay FAB attach failed", e);
            return false;
        }
    }

    private boolean tryAttachAppsFabInWindow() {
        if (rootLayout == null || appsFabRoot == null) return false;
        try {
            if (appsFabRoot.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) appsFabRoot.getParent()).removeView(appsFabRoot);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            clampAppsFabPosition();
            lp.leftMargin = appsFabPosX;
            lp.topMargin = appsFabPosY;
            appsFabRoot.setElevation(48f * getResources().getDisplayMetrics().density);
            rootLayout.addView(appsFabRoot, lp);
            appsFabRoot.bringToFront();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "In-window FAB attach failed", e);
            return false;
        }
    }

    private void dismissAppsFabOverlay() {
        appsFabMenuOpen = false;
        appsFabBtnAnchorY = Integer.MIN_VALUE;
        dismissAppsFabScrim();
        if (appsFabMenu != null) appsFabMenu.setVisibility(View.GONE);
        if (!appsFabOverlayAttached || appsFabRoot == null) {
            appsFabOverlayAttached = false;
            return;
        }
        try {
            if (appsFabUsesSystemOverlay) {
                getWindowManager().removeView(appsFabRoot);
            } else if (appsFabRoot.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) appsFabRoot.getParent()).removeView(appsFabRoot);
            }
        } catch (Exception ignored) {}
        appsFabOverlayAttached = false;
        appsFabUsesSystemOverlay = false;
        applyChromeOnTop(false);
    }

    private WindowManager.LayoutParams buildAppsFabWindowLp() {
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (android.os.Build.VERSION.SDK_INT < 26) {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        return lp;
    }

    private void updateDesktopSwitcherHitProxy(float cssLeft, float cssTop,
            float cssWidth, float cssHeight, boolean visible) {
        if (webView == null) return;
        int[] loc = new int[2];
        webView.getLocationOnScreen(loc);
        float scale = webView.getScale();
        if (scale <= 0f) scale = 1f;
        desktopHitX = loc[0] + Math.round(cssLeft * scale);
        desktopHitY = loc[1] + Math.round(cssTop * scale);
        desktopHitW = Math.max(0, Math.round(cssWidth * scale));
        desktopHitH = Math.max(0, Math.round(cssHeight * scale));
        desktopHitWanted = visible && desktopHitW > 1 && desktopHitH > 1;
        DesktopSwitcherHitService.applyFromViewer(this,
                desktopHitX, desktopHitY, desktopHitW, desktopHitH, desktopHitWanted);
    }

    boolean dispatchDesktopSwitcherHit(android.view.MotionEvent ev) {
        if (webView == null) return false;
        int[] loc = new int[2];
        webView.getLocationOnScreen(loc);
        android.view.MotionEvent copy = android.view.MotionEvent.obtain(ev);
        copy.offsetLocation(
                ev.getRawX() - loc[0] - ev.getX(),
                ev.getRawY() - loc[1] - ev.getY());
        try {
            return webView.dispatchTouchEvent(copy);
        } finally {
            copy.recycle();
        }
    }

    private void ensureAppsFabBuilt() {
        if (appsFabRoot != null) return;
        float d = getResources().getDisplayMetrics().density;
        final int fabSize = Math.round(72 * d);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setClipToPadding(false);
        root.setClipChildren(false);

        appsFabMenu = buildAppsFabMenu(d);
        appsFabMenu.setVisibility(View.GONE);
        root.addView(appsFabMenu);

        android.widget.TextView btn = new android.widget.TextView(this);
        btn.setText("☰");
        btn.setTextColor(0xFFEAF2F8);
        btn.setTextSize(26f);
        btn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xEB0E121A);
        bg.setCornerRadius(fabSize / 2f);
        bg.setStroke(Math.max(1, Math.round(1.5f * d)), 0x33FFFFFF);
        btn.setBackground(bg);
        btn.setElevation(8f * d);
        android.widget.LinearLayout.LayoutParams btnLp =
                new android.widget.LinearLayout.LayoutParams(fabSize, fabSize);
        btnLp.topMargin = Math.round(10 * d);
        btn.setLayoutParams(btnLp);
        btn.setClickable(true);
        btn.setFocusable(true);
        appsFabButton = btn;
        root.addView(btn);

        btn.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    appsFabDragging = false;
                    appsFabDownRawX = ev.getRawX();
                    appsFabDownRawY = ev.getRawY();
                    appsFabDownLpX = appsFabPosX;
                    appsFabDownLpY = appsFabPosY;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - appsFabDownRawX;
                    float dy = ev.getRawY() - appsFabDownRawY;
                    if (!appsFabDragging && (dx * dx + dy * dy) > (8 * d) * (8 * d)) {
                        appsFabDragging = true;
                        if (appsFabMenuOpen) setAppsFabMenuOpen(false);
                    }
                    if (appsFabDragging) {
                        appsFabPosX = appsFabDownLpX + Math.round(dx);
                        appsFabPosY = appsFabDownLpY + Math.round(dy);
                        clampAppsFabPosition();
                        applyAppsFabPosition();
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (appsFabDragging) {
                        saveAppsFabPos();
                    } else if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        setAppsFabMenuOpen(!appsFabMenuOpen);
                    }
                    appsFabDragging = false;
                    return true;
                default:
                    return false;
            }
        });

        if (appsFabPosX == Integer.MIN_VALUE) loadAppsFabPos();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (appsFabPosX == Integer.MIN_VALUE || appsFabPosY == Integer.MIN_VALUE) {
            appsFabPosX = Math.max(0, (dm.widthPixels - fabSize) / 2);
            appsFabPosY = Math.max(0, dm.heightPixels - fabSize - Math.round(24 * d));
        }
        clampAppsFabPosition();
        appsFabLp = buildAppsFabWindowLp();
        appsFabRoot = root;
    }

    private void applyAppsFabPosition() {
        if (!appsFabOverlayAttached || appsFabRoot == null) return;
        try {
            if (appsFabUsesSystemOverlay && appsFabLp != null) {
                appsFabLp.x = appsFabPosX;
                appsFabLp.y = appsFabPosY;
                getWindowManager().updateViewLayout(appsFabRoot, appsFabLp);
            } else if (appsFabRoot.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) appsFabRoot.getLayoutParams();
                lp.leftMargin = appsFabPosX;
                lp.topMargin = appsFabPosY;
                appsFabRoot.setLayoutParams(lp);
            }
        } catch (Exception ignored) {}
    }

    private void clampAppsFabPosition() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = appsFabRoot != null && appsFabRoot.getWidth() > 0
                ? appsFabRoot.getWidth()
                : Math.round((appsFabMenuOpen ? 300 : 72) * dm.density);
        int h = appsFabRoot != null && appsFabRoot.getHeight() > 0
                ? appsFabRoot.getHeight()
                : Math.round(72 * dm.density);
        if (appsFabRoot != null && (appsFabRoot.getWidth() <= 0 || appsFabRoot.getHeight() <= 0)) {
            appsFabRoot.measure(
                    View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST));
            w = Math.max(w, appsFabRoot.getMeasuredWidth());
            h = Math.max(h, appsFabRoot.getMeasuredHeight());
        }
        if (appsFabPosX == Integer.MIN_VALUE) appsFabPosX = 0;
        if (appsFabPosY == Integer.MIN_VALUE) appsFabPosY = 0;
        appsFabPosX = Math.max(0, Math.min(appsFabPosX, Math.max(0, dm.widthPixels - w)));
        appsFabPosY = Math.max(0, Math.min(appsFabPosY, Math.max(0, dm.heightPixels - h)));
    }

    private void ensureAppsFabScrimBuilt() {
        if (appsFabScrim != null) return;
        appsFabScrim = new View(this);
        appsFabScrim.setBackgroundColor(0x00000000);
        appsFabScrim.setClickable(true);
        appsFabScrim.setFocusable(true);
        appsFabScrim.setOnClickListener(v -> setAppsFabMenuOpen(false));
        appsFabScrimLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                android.os.Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT);
        appsFabScrimLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
    }

    private void showAppsFabScrim() {
        if (appsFabScrimAttached) return;
        ensureAppsFabScrimBuilt();
        try {
            if (appsFabUsesSystemOverlay) {
                getWindowManager().addView(appsFabScrim, appsFabScrimLp);
                if (appsFabOverlayAttached && appsFabRoot != null && appsFabLp != null) {
                    getWindowManager().removeView(appsFabRoot);
                    getWindowManager().addView(appsFabRoot, appsFabLp);
                }
            } else if (rootLayout != null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                rootLayout.addView(appsFabScrim, 0, lp);
                if (appsFabRoot != null) appsFabRoot.bringToFront();
            }
            appsFabScrimAttached = true;
        } catch (Exception e) {
            Log.w(TAG, "APP+APP FAB scrim attach failed", e);
        }
    }

    private void dismissAppsFabScrim() {
        if (!appsFabScrimAttached || appsFabScrim == null) {
            appsFabScrimAttached = false;
            return;
        }
        try {
            if (appsFabUsesSystemOverlay) {
                getWindowManager().removeView(appsFabScrim);
            } else if (appsFabScrim.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) appsFabScrim.getParent()).removeView(appsFabScrim);
            }
        } catch (Exception ignored) {}
        appsFabScrimAttached = false;
    }

    private void setAppsFabMenuOpen(boolean open) {
        float d = getResources().getDisplayMetrics().density;
        int menuGap = Math.round(10 * d);
        if (open && !appsFabMenuOpen) {
            appsFabBtnAnchorY = appsFabPosY;
        }
        appsFabMenuOpen = open;
        if (appsFabMenu != null) {
            appsFabMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        }
        if (appsFabButton != null) {
            appsFabButton.setTextColor(open ? 0xFF4FD6E8 : 0xFFEAF2F8);
        }
        if (open) {
            showAppsFabScrim();
            refreshAppsFabMenu();
        } else {
            dismissAppsFabScrim();
            if (appsFabBtnAnchorY != Integer.MIN_VALUE) {
                appsFabPosY = appsFabBtnAnchorY;
                appsFabBtnAnchorY = Integer.MIN_VALUE;
            }
        }
        // In-window FAB sits in our task — raise chrome so the menu isn't under freeforms.
        if (!appsFabUsesSystemOverlay) {
            applyChromeOnTop(open);
        }
        if (appsFabOverlayAttached && appsFabRoot != null) {
            // Overlay was measured while the menu was GONE (FAB-only width). Remeasure
            // so the panel expands instead of staying a ~56dp strip.
            appsFabRoot.post(this::remeasureAppsFabWindow);
        }
    }

    private void remeasureAppsFabWindow() {
        if (!appsFabOverlayAttached || appsFabRoot == null) return;
        appsFabRoot.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float d = getResources().getDisplayMetrics().density;
        int w = Math.max(appsFabRoot.getMeasuredWidth(), Math.round(72 * d));
        int h = Math.max(appsFabRoot.getMeasuredHeight(), Math.round(72 * d));
        if (appsFabMenuOpen && appsFabBtnAnchorY != Integer.MIN_VALUE) {
            int menuH = appsFabMenu != null ? appsFabMenu.getMeasuredHeight() : 0;
            int btnH = appsFabButton != null ? appsFabButton.getMeasuredHeight() : Math.round(72 * d);
            if (menuH > 0) {
                int menuGap = Math.round(10 * d);
                appsFabPosY = appsFabBtnAnchorY - menuH - menuGap;
                h = menuH + menuGap + btnH;
            }
        }
        clampAppsFabPosition();
        try {
            if (appsFabUsesSystemOverlay && appsFabLp != null) {
                appsFabLp.width = w;
                appsFabLp.height = h;
                appsFabLp.x = appsFabPosX;
                appsFabLp.y = appsFabPosY;
                getWindowManager().updateViewLayout(appsFabRoot, appsFabLp);
            } else {
                applyAppsFabPosition();
                appsFabRoot.requestLayout();
            }
        } catch (Exception ignored) {}
    }

    private boolean ensureAppsFabOverlay() {
        ensureAppsFabBuilt();
        return appsFabRoot != null;
    }

    private android.widget.LinearLayout buildAppsFabMenu(float d) {
        android.widget.LinearLayout menu = new android.widget.LinearLayout(this);
        menu.setOrientation(android.widget.LinearLayout.VERTICAL);
        menu.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(14 * d);
        menu.setPadding(pad, pad, pad, pad);
        // Explicit width — a WRAP_CONTENT overlay first measured with the menu GONE
        // otherwise stays ~FAB-wide and crushes labels into a strip.
        int minW = Math.round(300 * d);
        menu.setMinimumWidth(minW);
        menu.setLayoutParams(new android.widget.LinearLayout.LayoutParams(minW,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF00E121A);
        bg.setCornerRadius(14 * d);
        bg.setStroke(Math.max(1, Math.round(1 * d)), 0x24FFFFFF);
        menu.setBackground(bg);
        menu.setElevation(10f * d);

        menu.addView(makeAppsFabSectionLabel("SPLIT", d));
        android.widget.LinearLayout ratioRow = new android.widget.LinearLayout(this);
        ratioRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        ratioRow.setGravity(android.view.Gravity.CENTER);
        ratioRow.setTag("ratioRow");
        ratioRow.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        ratioRow.addView(makeAppsFabRatioBtn("1:1", d));
        ratioRow.addView(makeAppsFabRatioBtn("2:1", d));
        ratioRow.addView(makeAppsFabRatioBtn("1:2", d));
        menu.addView(ratioRow);

        menu.addView(makeAppsFabSectionLabel("LAYOUT", d));
        android.widget.LinearLayout layoutCol = new android.widget.LinearLayout(this);
        layoutCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        layoutCol.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        // Side by Side is entered from its launcher tile; this is the way back.
        layoutCol.addView(makeAppsFabLayoutTextBtn(SHELL_APP_CAR, "Exit Side by Side", d));
        menu.addView(layoutCol);

        android.widget.LinearLayout actionRow = new android.widget.LinearLayout(this);
        actionRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actionRow.setGravity(android.view.Gravity.CENTER);
        actionRow.setPadding(0, Math.round(8 * d), 0, 0);
        actionRow.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        actionRow.addView(makeAppsFabTextBtn("SWAP", d, () -> {
            setAppsFabMenuOpen(false);
            swapSlotApps();
        }));
        actionRow.addView(makeAppsFabTextBtn("SAVE", d, () -> {
            setAppsFabMenuOpen(false);
            saveAppsOnlyDefaultNow();
        }));
        menu.addView(actionRow);
        return menu;
    }

    private android.widget.TextView makeAppsFabSectionLabel(String text, float d) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(text);
        t.setTextColor(0x80EAF2F8);
        t.setTextSize(12f);
        t.setLetterSpacing(0.06f);
        t.setGravity(android.view.Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
        t.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private View makeAppsFabRatioBtn(String ratio, float d) {
        FrameLayout cell = new FrameLayout(this);
        int sizeW = Math.round(76 * d);
        int sizeH = Math.round(58 * d);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(sizeW, sizeH);
        lp.setMargins(Math.round(4 * d), 0, Math.round(4 * d), 0);
        cell.setLayoutParams(lp);
        cell.setTag(ratio);
        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            applySplitRatio(ratio);
            refreshAppsFabMenu();
        });
        cell.addView(makeRatioGlyphView(ratio, d));
        styleAppsFabIconCell(cell, ratio.equals(splitRatio), d);
        return cell;
    }

    private View makeRatioGlyphView(String ratio, float d) {
        android.widget.LinearLayout g = new android.widget.LinearLayout(this);
        g.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                Math.round(44 * d), Math.round(26 * d));
        glp.gravity = android.view.Gravity.CENTER;
        g.setLayoutParams(glp);
        float leftW = 1f;
        float rightW = 1f;
        if ("2:1".equals(ratio)) { leftW = 2f; rightW = 1f; }
        else if ("1:2".equals(ratio)) { leftW = 1f; rightW = 2f; }
        g.addView(makeGlyphBar(d, leftW));
        View gap = new View(this);
        android.widget.LinearLayout.LayoutParams gapLp =
                new android.widget.LinearLayout.LayoutParams(Math.round(4 * d), 1);
        gap.setLayoutParams(gapLp);
        g.addView(gap);
        g.addView(makeGlyphBar(d, rightW));
        return g;
    }

    private View makeGlyphBar(float d, float weight) {
        View bar = new View(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, weight);
        bar.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setColor(0xD9EAF2F8);
        gd.setCornerRadius(2 * d);
        bar.setBackground(gd);
        return bar;
    }

    private View makeAppsFabLayoutTextBtn(String mode, String label, float d) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(label);
        t.setTextColor(0xFFEAF2F8);
        t.setTextSize(13f);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(Math.round(12 * d), Math.round(12 * d), Math.round(12 * d), Math.round(12 * d));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(4 * d);
        lp.bottomMargin = Math.round(4 * d);
        t.setLayoutParams(lp);
        styleAppsFabIconCell(t, mode.equals(shellMode), d);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            setAppsFabMenuOpen(false);
            if (!mode.equals(shellMode)) applyShellMode(mode);
        });
        return t;
    }

    private android.widget.TextView makeAppsFabTextBtn(String label, float d, Runnable action) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(label);
        t.setTextColor(0xFFEAF2F8);
        t.setTextSize(14f);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(Math.round(18 * d), Math.round(14 * d), Math.round(18 * d), Math.round(14 * d));
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Math.round(4 * d), 0, Math.round(4 * d), 0);
        t.setLayoutParams(lp);
        styleAppsFabIconCell(t, false, d);
        t.setClickable(true);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private void styleAppsFabIconCell(View cell, boolean on, float d) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(on ? 0x2E4FD6E8 : 0x10FFFFFF);
        bg.setCornerRadius(10 * d);
        bg.setStroke(Math.max(1, Math.round(1 * d)), on ? 0xA64FD6E8 : 0x29FFFFFF);
        cell.setBackground(bg);
    }

    private void refreshAppsFabMenu() {
        if (appsFabMenu == null) return;
        View ratioRow = appsFabMenu.findViewWithTag("ratioRow");
        if (!(ratioRow instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup row = (android.view.ViewGroup) ratioRow;
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String) {
                styleAppsFabIconCell(child, tag.equals(splitRatio), d);
            }
        }
    }

    private void applySplitRatio(String ratio) {
        if (!isValidSplitRatio(ratio)) return;
        if (ratio.equals(splitRatio)) return;
        splitRatio = ratio;
        saveShellPrefs();
        updateSlotAnchors();
        boolean leftOpen = activePopupPackage != null && !activePopupPackage.isEmpty();
        boolean rightOpen = activeMediaPackage != null && !activeMediaPackage.isEmpty();
        if (leftOpen) {
            pinSlotToBounds(activePopupPackage, activePopupComponent,
                    activePopupTaskId, leftFreeformBounds(), false);
        }
        if (rightOpen) {
            pinSlotToBounds(activeMediaPackage, activeMediaComponent,
                    activeMediaTaskId, rightFreeformBounds(), true);
        }
        notifyViewerShellLayout();
    }

    private void launchAppInSlotNative(String packageName, String side) {
        if (packageName == null || packageName.isEmpty()) return;
        if (CAR_SETTINGS_PACKAGE.equals(packageName)) {
            launchAndroidSettingsRoot();
            return;
        }
        rememberRecentApp(packageName);
        if ("right".equals(side)) {
            applySlotUse("right", "app");
            launchAppInRightSlot(packageName);
        } else {
            applySlotUse("left", "app");
            launchAppInLeftSlot(packageName);
        }
    }

    private void swapSlotApps() {
        String leftPkg = activePopupPackage;
        String rightPkg = activeMediaPackage;
        android.content.ComponentName leftComp = activePopupComponent;
        android.content.ComponentName rightComp = activeMediaComponent;
        int leftTask = activePopupTaskId;
        int rightTask = activeMediaTaskId;

        boolean leftOpen = leftPkg != null && !leftPkg.isEmpty();
        boolean rightOpen = rightPkg != null && !rightPkg.isEmpty();
        if (!leftOpen && !rightOpen) return;

        activePopupPackage = rightPkg != null ? rightPkg : "";
        activePopupComponent = rightComp;
        activePopupTaskId = rightTask;
        activeMediaPackage = leftPkg != null ? leftPkg : "";
        activeMediaComponent = leftComp;
        activeMediaTaskId = leftTask;

        updateSlotAnchors();
        // After a swap the windows still have their old widths — force both the
        // AM resize path and Impulse bounds (resizeTask is often blocked here).
        if (activePopupPackage != null && !activePopupPackage.isEmpty()) {
            pinSlotToBounds(activePopupPackage, activePopupComponent,
                    activePopupTaskId, leftFreeformBounds(), false);
        }
        if (activeMediaPackage != null && !activeMediaPackage.isEmpty()) {
            pinSlotToBounds(activeMediaPackage, activeMediaComponent,
                    activeMediaTaskId, rightFreeformBounds(), true);
        }
        notifyViewerShellLayout();
        Log.w(TAG, "Swapped slots: left=" + activePopupPackage + " right=" + activeMediaPackage);
    }

    /**
     * Immediately resize a freeform slot and keep re-applying until it sticks.
     * Used after swap / split-ratio changes where the app would otherwise keep
     * its previous window size.
     */
    private void pinSlotToBounds(String packageName, android.content.ComponentName component,
            int knownTaskId, Rect bounds, boolean rightSlot) {
        if (packageName == null || packageName.isEmpty() || bounds == null) return;
        final Rect copy = new Rect(bounds);
        int taskId = knownTaskId;
        if (taskId < 0) taskId = findTaskIdForPackage(packageName);
        if (taskId >= 0) {
            if (rightSlot) activeMediaTaskId = taskId;
            else activePopupTaskId = taskId;
            resizeTaskToBounds(taskId, copy);
            setTaskAlwaysOnTop(taskId, true);
        }
        schedulePinPopupBounds(packageName, component, copy, rightSlot);
        requestTaskBounds(packageName, copy);
        // Impulse often needs a beat after the task has been raised / swapped.
        mainHandler.postDelayed(() -> {
            String active = rightSlot ? activeMediaPackage : activePopupPackage;
            if (packageName.equals(active)) requestTaskBounds(packageName, copy);
        }, 400);
        mainHandler.postDelayed(() -> {
            String active = rightSlot ? activeMediaPackage : activePopupPackage;
            if (packageName.equals(active)) requestTaskBounds(packageName, copy);
        }, 1000);
    }

    private void saveAppsOnlyDefaultNow() {
        shellMode = SHELL_APPS;
        android.content.SharedPreferences.Editor ed = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE).edit();
        ed.putString("mode", SHELL_APPS);
        ed.putString("lastNonAppsMode", lastNonAppsShellMode);
        ed.putString("splitRatio", splitRatio);
        ed.putString("leftApp", activePopupPackage != null ? activePopupPackage : "");
        ed.putString("rightApp", activeMediaPackage != null ? activeMediaPackage : "");
        ed.putString("launchSide", launchSidePref);
        ed.putBoolean("nextLeft", nextLaunchLeft);
        ed.putString("uiMode", uiMode);
        ed.apply();
        try {
            android.widget.Toast.makeText(this, "Side by Side saved as default", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        Log.w(TAG, "Saved APP+APP default ratio=" + splitRatio
                + " left=" + activePopupPackage + " right=" + activeMediaPackage);
    }

    /** Cold-start restore of the saved left/right packages when booting into APP+APP. */
    private void maybeRestoreAppsOnlySlots() {
        if (!SHELL_APPS.equals(shellMode) || appsOnlyRestoreDone) return;
        appsOnlyRestoreDone = true;
        final String left = pendingRestoreLeft != null ? pendingRestoreLeft : "";
        final String right = pendingRestoreRight != null ? pendingRestoreRight : "";
        pendingRestoreLeft = "";
        pendingRestoreRight = "";
        if (left.isEmpty() && right.isEmpty()) return;
        Log.w(TAG, "Restoring APP+APP slots left=" + left + " right=" + right);
        if (!left.isEmpty()) {
            mainHandler.postDelayed(() -> {
                if (SHELL_APPS.equals(shellMode)) launchAppInLeftSlot(left);
            }, 500);
        }
        if (!right.isEmpty()) {
            mainHandler.postDelayed(() -> {
                if (SHELL_APPS.equals(shellMode)) launchAppInRightSlot(right);
            }, 900);
        }
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
        alignQuickCardsToWidgetBoard();
    }

    /**
     * Keep the first quick card on the same display-space x coordinate as the
     * left widget board, even when the OEM navigation rail re-insets our window.
     */
    private void alignQuickCardsToWidgetBoard() {
        if (quickCardsRow == null || stripContainer == null || contentHost == null) return;
        float density = getResources().getDisplayMetrics().density;
        android.view.ViewGroup.LayoutParams rawHostLp = contentHost.getLayoutParams();
        int hostLeft = 0;
        if (rawHostLp instanceof android.widget.LinearLayout.LayoutParams) {
            android.widget.LinearLayout.LayoutParams hostLp =
                    (android.widget.LinearLayout.LayoutParams) rawHostLp;
            int desiredHostLeft = DOCK_SURFACE_CARDS.equals(dockSurfaceMode)
                    ? 0 : Math.round(12 * density);
            if (hostLp.leftMargin != desiredHostLeft) {
                hostLp.leftMargin = desiredHostLeft;
                contentHost.setLayoutParams(hostLp);
            }
            hostLeft = desiredHostLeft;
        }
        Rect origin = pageOriginRect();
        int boardLeftInWindow = Math.max(0, leftFreeformBounds().left - origin.left);
        int rowLeft = Math.max(0,
                boardLeftInWindow - stripContainer.getPaddingLeft() - hostLeft);
        if (quickCardsRow.getPaddingLeft() != rowLeft) {
            quickCardsRow.setPadding(rowLeft, quickCardsRow.getPaddingTop(),
                    quickCardsRow.getPaddingRight(), quickCardsRow.getPaddingBottom());
        }
    }

    /**
     * Draw only the part of the 60dp reserve that is still inside our window.
     * When Impulse already applies bottom overscan, this shrinks to zero instead
     * of creating a second black band above the real one.
     */
    private void applyImpulseReserveBandGeometry() {
        if (impulseReserveBand == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) impulseReserveBand.getLayoutParams();
        if (lp == null) return;
        int height = Math.max(0, launcherBottomGapPx() - overscanBottomPx());
        if (lp.height != height) {
            lp.height = height;
            impulseReserveBand.setLayoutParams(lp);
        }
        impulseReserveBand.setVisibility(launcherRevealed && height > 0 ? View.VISIBLE : View.INVISIBLE);
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
        applyLauncherStripBottomMargin();
        boolean left = activePopupPackage != null && !activePopupPackage.isEmpty();
        String right = (activeMediaPackage != null && !activeMediaPackage.isEmpty())
                ? "\"app\"" : "\"idle\"";
        applyLauncherFocusPolicy();
        String nextSide = nextLaunchLeft ? "left" : "right";
        if ("left".equals(launchSidePref) || "right".equals(launchSidePref)) nextSide = launchSidePref;
        String lastSide = (lastLaunchSide != null && !lastLaunchSide.isEmpty())
                ? lastLaunchSide : nextSide;
        // launcherBottom is Impulse-overscan compensated so CSS bottom: chrome stays
        // at a fixed display Y when wm overscan shortens the window.
        float launcherBottomCss = cssPx(compensatedBottomMarginPx(chromeBottomGapPx()));
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
                // Overscan-compensated: the page anchors this with CSS bottom:,
                // which is relative to a window Impulse has already shortened,
                // while the reserve itself is measured on the panel.
                + ",safeBottom:" + cssPx(compensatedBottomMarginPx(dockReservePx()))
                + ",launcherBottom:" + launcherBottomCss
                + "," + popupVerticalBoundsToCssFragment()
                + ",uiMode:\"" + uiMode + "\""
                + ",splitRatio:\"" + splitRatio + "\""
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
    /** Same, for the MMI header. No resource fallback exists for it. */
    private int widestTopInsetPx;
    private int loggedNavReservePx = -1;
    private int loggedOverscanBottomPx = -1;

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

    /** Impulse/Shizuku can see foreign tasks; we cannot on Android 9. */
    private void requestProjectionTaskResolve(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            Intent request = new Intent(ACTION_RESOLVE_TASK);
            request.setPackage(IMPULSE_PACKAGE);
            request.putExtra(ImpulseApi.EXTRA_CALLER, apiCallerToken());
            request.putExtra("package", packageName);
            request.putExtra("slot", "projection");
            sendBroadcast(request);
            Log.w(TAG, "Projection task resolve requested for " + packageName);
        } catch (Exception e) {
            Log.w(TAG, "Projection task resolve failed for " + packageName + " ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private void clearPendingProjection() {
        pendingProjectionKind = null;
        pendingProjectionPackages.clear();
        if (pendingProjectionTimeout != null) {
            mainHandler.removeCallbacks(pendingProjectionTimeout);
            pendingProjectionTimeout = null;
        }
    }

    private void completeProjectionRaise(int taskId, String packageName) {
        clearPendingProjection();
        if (taskId < 0) return;
        Log.w(TAG, "Projection raise task=" + taskId + " pkg=" + packageName);
        if (moveTaskToFrontNoAnim(taskId)) {
            requestTaskBounds(packageName, FULLSCREEN_BOUNDS);
            notifyViewerShellLayout();
        }
    }

    private void beginProjectionResolve(ProjectionPresence.Kind kind) {
        clearPendingProjection();
        pendingProjectionKind = kind;
        for (String pkg : ProjectionPresence.displayPackagesFor(kind)) {
            pendingProjectionPackages.add(pkg);
            requestProjectionTaskResolve(pkg);
        }
        if (projectionPresence != null) projectionPresence.requestShow(kind);
        final ProjectionPresence.Kind watch = kind;
        pendingProjectionTimeout = () -> {
            if (pendingProjectionKind != watch) return;
            Log.w(TAG, "Projection resolve timed out for " + watch + "; trying launch intents");
            clearPendingProjection();
            List<Intent> candidates = projectionPresence != null
                    ? projectionPresence.launchIntents(watch) : null;
            if (candidates == null || candidates.isEmpty()) {
                Log.w(TAG, "Projection " + watch + ": no launch fallback available");
                return;
            }
            for (Intent intent : candidates) {
                ComponentName cn = intent.getComponent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    Bundle opts = buildWindowOptions(WINDOWING_MODE_FULLSCREEN, FULLSCREEN_BOUNDS);
                    Log.w(TAG, "Projection launch " + watch + " -> " + cn);
                    startActivity(intent, opts);
                    if (cn != null) requestTaskBounds(cn.getPackageName(), FULLSCREEN_BOUNDS);
                    notifyViewerShellLayout();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Projection launch failed for " + cn, e);
                }
            }
        };
        mainHandler.postDelayed(pendingProjectionTimeout, 1500);
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
        String projection = intent.getStringExtra("launch_projection");
        if (projection != null && !projection.isEmpty()) {
            intent.removeExtra("launch_projection");
            if ("AA".equalsIgnoreCase(projection) || "ANDROID_AUTO".equalsIgnoreCase(projection)) {
                launchProjection(ProjectionPresence.Kind.ANDROID_AUTO);
            } else if ("CP".equalsIgnoreCase(projection) || "CARPLAY".equalsIgnoreCase(projection)) {
                launchProjection(ProjectionPresence.Kind.CARPLAY);
            }
        }
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
     * Projection (Android Auto / CarPlay) opens fullscreen, like the OEM apps.
     *
     * <p>The display activities ({@code AapActivity}, {@code CarPlayDisplayActivity})
     * are started by the projection service when a phone links and are not exported
     * to third-party apps — {@code startActivity} on them throws and the icon
     * appears dead. Raise the existing task instead (same path as freeform popups),
     * then fall back to any exported launcher entry the stack happens to ship.
     */
    private void launchProjection(ProjectionPresence.Kind kind) {
        if (kind == null || kind == ProjectionPresence.Kind.NONE) return;
        if (projectionPresence == null) return;
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
        if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);

        for (String pkg : ProjectionPresence.displayPackagesFor(kind)) {
            int taskId = findTaskIdForPackage(pkg);
            Log.w(TAG, "Projection lookup " + kind + " pkg=" + pkg + " task=" + taskId);
            if (taskId < 0) continue;
            Log.w(TAG, "Projection raise " + kind + " task=" + taskId + " pkg=" + pkg);
            if (moveTaskToFrontNoAnim(taskId)) {
                requestTaskBounds(pkg, FULLSCREEN_BOUNDS);
                notifyViewerShellLayout();
                return;
            }
        }

        beginProjectionResolve(kind);
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
        // Process-scoped and fed by its own receiver: see TripRecorder.get.
        tripRecorder = TripRecorder.get(this);
        webView.addJavascriptInterface(new TripBridge(tripRecorder), "TripBridge");

        placeGlance = new PlaceGlance(this, mainHandler, json -> {
            telemetryCache.put(PlaceGlance.KEY, json);
            if (webView == null) return;
            webView.evaluateJavascript(
                    String.format("if(window.onCarDataUpdate){window.onCarDataUpdate('%s','%s');}",
                            PlaceGlance.KEY.replace("'", "\\'"), json.replace("'", "\\'")),
                    null);
        });
        placeGlance.start();

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
                mainHandler.post(() -> updateQuickMediaCard(payload));
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
        try {
            IntentFilter clockFilter = new IntentFilter();
            clockFilter.addAction(Intent.ACTION_TIME_TICK);
            clockFilter.addAction(Intent.ACTION_TIME_CHANGED);
            clockFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            clockFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
            registerReceiver(clockEnvironmentReceiver, clockFilter);
        } catch (Exception e) {
            Log.w(TAG, "Clock environment receiver not registered", e);
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
            String url = viewerUrl();
            if (getIntent() != null && getIntent().getBooleanExtra("nosplash", false)) {
                url = url + "&nosplash";
            }
            webView.loadUrl(url);
        } else {
            webView.restoreState(savedInstanceState);
        }

        ensureFreeformSettings();
        loadShellPrefs();
        updateAppsOnlyChrome();
        updateSlotAnchors();
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
    /**
     * Horizontal scroller with rubber-band bounce at both ends. Works on API 28
     * (car) where stretch-overscroll does not exist — pulls the content past the
     * edge while dragging, then springs back on release.
     * <p>
     * Left fading edge is always on so icons fade out against the mode drawer
     * instead of stacking over it when swiped left.
     */
    private static class BounceHorizontalScrollView extends android.widget.HorizontalScrollView {
        private static final float MAX_PULL_DP = 56f;
        private static final float DAMP = 0.45f;
        private float pullPx;
        private float lastX = Float.NaN;
        private boolean pulling;
        private android.animation.ValueAnimator spring;
        private final float maxPullPx;
        private final boolean maskLeftAlways;

        BounceHorizontalScrollView(Context context) {
            this(context, true);
        }

        BounceHorizontalScrollView(Context context, boolean maskLeftAlways) {
            super(context);
            this.maskLeftAlways = maskLeftAlways;
            float d = context.getResources().getDisplayMetrics().density;
            maxPullPx = MAX_PULL_DP * (d <= 0f ? 1f : d);
            setOverScrollMode(View.OVER_SCROLL_ALWAYS);
            setFillViewport(false);
            setHorizontalFadingEdgeEnabled(true);
        }

        @Override
        protected float getLeftFadingEdgeStrength() {
            // Always fade the drawer-adjacent edge for the apps strip.
            if (maskLeftAlways) return 1.0f;
            return getScrollX() > 0 ? 1.0f : 0f;
        }

        @Override
        protected float getRightFadingEdgeStrength() {
            return 1.0f;
        }

        private View content() {
            return getChildCount() > 0 ? getChildAt(0) : null;
        }

        private int maxScrollX() {
            View child = content();
            if (child == null) return 0;
            return Math.max(0, child.getWidth() - getWidth() + getPaddingLeft() + getPaddingRight());
        }

        private void applyPull(float px) {
            pullPx = Math.max(-maxPullPx, Math.min(maxPullPx, px));
            View child = content();
            if (child != null) child.setTranslationX(pullPx);
        }

        private void springBack() {
            if (spring != null) spring.cancel();
            if (Math.abs(pullPx) < 0.5f) {
                applyPull(0f);
                return;
            }
            final float from = pullPx;
            spring = android.animation.ValueAnimator.ofFloat(from, 0f);
            spring.setDuration(280);
            spring.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            spring.addUpdateListener(a -> applyPull((Float) a.getAnimatedValue()));
            spring.start();
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                lastX = ev.getX();
                pulling = false;
                if (spring != null) spring.cancel();
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent ev) {
            final int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                lastX = ev.getX();
                pulling = false;
                if (spring != null) spring.cancel();
                return super.onTouchEvent(ev);
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) {
                float x = ev.getX();
                if (Float.isNaN(lastX)) lastX = x;
                float dx = x - lastX;
                lastX = x;
                int scrollX = getScrollX();
                int max = maxScrollX();
                boolean atStart = scrollX <= 0;
                boolean atEnd = scrollX >= max;
                // Dragging content further past the edge → rubber band.
                if ((atStart && dx > 0) || (atEnd && dx < 0) || pulling) {
                    pulling = true;
                    applyPull(pullPx + dx * DAMP);
                    return true;
                }
                // Leaving an overscroll while still dragging — release into normal scroll.
                if (pullPx != 0f && ((atStart && dx < 0) || (atEnd && dx > 0))) {
                    applyPull(0f);
                    pulling = false;
                }
                return super.onTouchEvent(ev);
            }
            if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                lastX = Float.NaN;
                if (pulling || Math.abs(pullPx) > 0.5f) {
                    pulling = false;
                    springBack();
                    // Still deliver UP to settle any fling state in the parent.
                    super.onTouchEvent(ev);
                    return true;
                }
                pulling = false;
            }
            return super.onTouchEvent(ev);
        }
    }

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
        updateAppsOnlyChrome();
        if (SHELL_APPS.equals(shellMode)) {
            maybeRestoreAppsOnlySlots();
            return;
        }
        updateLauncherStripVisibility();
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
        if (bootProgressTrack != null && bootProgressTrack.getVisibility() == View.VISIBLE) {
            bootProgressTrack.bringToFront();
        }
        if (bootCenterLoader != null && bootCenterLoader.getVisibility() == View.VISIBLE) {
            bootCenterLoader.bringToFront();
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

    /** Fixed display Y for the 2px boot line — 65px above the 720px panel. */
    private static final int BOOT_PROGRESS_LINE_TOP_Y = 655;

    private int bootProgressLineTopMarginPx() {
        return Math.max(0, BOOT_PROGRESS_LINE_TOP_Y - pageOriginRect().top);
    }

    private void layoutBootProgressLine() {
        if (bootProgressTrack == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bootProgressTrack.getLayoutParams();
        int top = bootProgressLineTopMarginPx();
        if (lp.topMargin != top || lp.gravity != (android.view.Gravity.TOP | android.view.Gravity.START)) {
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.topMargin = top;
            lp.bottomMargin = 0;
            bootProgressTrack.setLayoutParams(lp);
        }
    }

    /**
     * Boot load UI above the WebView. The splash &lt;video&gt; hole-punches
     * through in-page HTML, so progress has to live in native chrome:
     * a full-width 2px blue line at fixed display Y=655 (65px above the panel
     * bottom), swapped for a subtle center spinner once the clip is gone but
     * the GLB is still arriving. Hidden the moment the model is ready — even
     * if the intro fade is still playing.
     */
    private void setupBootHud(FrameLayout root) {
        float d = getResources().getDisplayMetrics().density;
        // Literal 2 device pixels — not density-scaled — so the line stays hairline
        // on the 1920×720 panel (density is typically 1.0 here anyway).
        int lineH = 2;

        FrameLayout track = new FrameLayout(this);
        track.setClickable(false);
        track.setFocusable(false);
        track.setBackgroundColor(0x28FFFFFF);
        track.setElevation(20f * d);
        View fill = new View(this);
        fill.setBackgroundColor(0xFF4FD6E8);
        fill.setLayoutParams(new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        track.addView(fill);
        bootProgressFill = fill;
        FrameLayout.LayoutParams trackLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, lineH);
        trackLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        trackLp.topMargin = bootProgressLineTopMarginPx();
        root.addView(track, trackLp);
        bootProgressTrack = track;
        track.setVisibility(View.GONE);

        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        int spin = Math.round(36 * d);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(spin, spin);
        spinLp.gravity = android.view.Gravity.CENTER;
        spinner.setLayoutParams(spinLp);
        spinner.setIndeterminate(true);
        try {
            spinner.getIndeterminateDrawable().setColorFilter(
                    0xFF4FD6E8, android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {}
        spinner.setElevation(20f * d);
        spinner.setVisibility(View.GONE);
        root.addView(spinner, spinLp);
        bootCenterLoader = spinner;
    }

    private void showBootHud(int pct) {
        if (bootHudCenterMode) {
            if (bootProgressTrack != null) bootProgressTrack.setVisibility(View.GONE);
            if (bootCenterLoader != null) {
                bootCenterLoader.setVisibility(View.VISIBLE);
                bootCenterLoader.bringToFront();
            }
            return;
        }
        if (bootCenterLoader != null) bootCenterLoader.setVisibility(View.GONE);
        if (bootProgressTrack == null || bootProgressFill == null) return;
        layoutBootProgressLine();
        bootProgressTrack.setVisibility(View.VISIBLE);
        bootProgressTrack.bringToFront();
        int w = bootProgressTrack.getWidth();
        if (w <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bootProgressFill.getLayoutParams();
        lp.width = Math.round(w * (Math.max(0, Math.min(100, pct)) / 100f));
        bootProgressFill.setLayoutParams(lp);
    }

    private void hideBootHud() {
        bootHudCenterMode = false;
        if (bootProgressTrack != null) bootProgressTrack.setVisibility(View.GONE);
        if (bootCenterLoader != null) bootCenterLoader.setVisibility(View.GONE);
    }

    /**
     * {@code "center"} replaces the bottom line with a spinner in the car gap
     * (skip / intro ended while still loading). Anything else restores the line.
     */
    private void layoutBootHud(String where) {
        bootHudCenterMode = "center".equals(where);
        if (bootHudCenterMode) {
            if (bootProgressTrack != null) bootProgressTrack.setVisibility(View.GONE);
            if (bootCenterLoader != null) {
                bootCenterLoader.setVisibility(View.VISIBLE);
                bootCenterLoader.bringToFront();
            }
        } else {
            if (bootCenterLoader != null) bootCenterLoader.setVisibility(View.GONE);
            if (bootProgressTrack != null && bootProgressTrack.getVisibility() == View.VISIBLE) {
                bootProgressTrack.bringToFront();
            }
        }
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
            hideSplashSkip();
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
        lp.bottomMargin = compensatedBottomMarginPx(chromeBottomGapPx());
        root.addView(skip, lp);
        splashSkipBtn = skip;
    }

    private void hideSplashSkip() {
        if (splashSkipBtn != null) splashSkipBtn.setVisibility(View.GONE);
    }

    private void setupNativeLauncherUI(FrameLayout rootLayout) {
        loadRecentApps();
        loadHiddenApps();
        loadWindowApps();
        dockAppOverrides = DockAppOverrides.load(getSharedPreferences(PREFS_SHELL, MODE_PRIVATE));
        dockSurfaceMode = normalizeDockSurfaceMode(getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .getString(PREF_DOCK_SURFACE, DOCK_SURFACE_LAUNCHER));
        float density = getResources().getDisplayMetrics().density;
        // Bigger than the old 52/78: dropping the captions freed vertical room in
        // the dock band. One GWM hub (ic_gwm on black) opens the four OEM shortcuts.
        int iconSizePx = Math.round(60 * density);
        int itemWidthPx = Math.round(84 * density);
        dockCellPx = itemWidthPx;
        dockIconPx = iconSizePx;
        int marginBottomPx = compensatedBottomMarginPx(launcherBottomGapPx());
        int fadeLengthPx = Math.round(72 * density);
        // Taller band for icon + two-line caption. Its bottom aligns with the
        // top of the reserved Impulse bar via launcherBottomGapPx().
        int stripHeightPx = launcherStripHeightPx();

        // Bottom icon strip — full width (media column sits above the dock band).
        // bottomMargin is overscan-compensated so Impulse wm overscan does not
        // lift the strip with the shortened window.
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

        View impulseBand = new View(this);
        android.graphics.drawable.GradientDrawable impulseBandBg =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] { 0x52000000, 0xA6000000 });
        impulseBand.setBackground(impulseBandBg);
        impulseBand.setClickable(false);
        impulseBand.setFocusable(false);
        impulseBand.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams impulseBandLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, launcherBottomGapPx());
        impulseBandLp.gravity = android.view.Gravity.BOTTOM;
        impulseBand.setLayoutParams(impulseBandLp);
        impulseReserveBand = impulseBand;

        android.widget.LinearLayout stripRow = new android.widget.LinearLayout(this);
        this.stripRow = stripRow;
        stripRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        stripRow.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        // Drawer must stay above the scroll band; host clips so icons cannot
        // paint left into the drawer when swiped.
        stripRow.setClipChildren(false);
        stripRow.setBackgroundColor(0x00000000);
        strip.addView(stripRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        int platePx = dockPlatePx(iconSizePx, density);
        int iconRowTopPad = Math.max(Math.round(8 * density), (stripHeightPx - platePx) / 2);
        dockIconRowTopPadPx = iconRowTopPad;

        modeDrawer = buildModeDrawer(density, itemWidthPx, iconSizePx, iconRowTopPad);
        // Keep the drawer above any scroll bleed (clip is the real fix; this is belt).
        modeDrawer.setElevation(10f * density);
        stripRow.addView(modeDrawer);

        FrameLayout host = new FrameLayout(this);
        host.setClipChildren(true);
        host.setClipToPadding(true);
        host.setBackgroundColor(0x00000000);
        contentHost = host;
        android.widget.LinearLayout.LayoutParams hostLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        hostLp.leftMargin = Math.round(12 * density);
        stripRow.addView(host, hostLp);

        BounceHorizontalScrollView scrollView = new BounceHorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        // Do not paint a left fade on the initial launcher item.  The first
        // icon is fully visible at rest; the fade becomes useful only after
        // the user has moved the row and that item has started leaving view.
        scrollView.setHorizontalFadingEdgeEnabled(false);
        scrollView.setFadingEdgeLength(fadeLengthPx);
        scrollView.setBackgroundColor(0x00000000);
        // Clip icons to the scroll viewport so they never draw over the drawer.
        scrollView.setClipChildren(true);
        scrollView.setClipToPadding(true);
        appsScrollView = scrollView;

        android.widget.LinearLayout iconsLayout = new android.widget.LinearLayout(this);
        iconsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        iconsLayout.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        iconsLayout.setPadding(Math.round(12 * density), iconRowTopPad, Math.round(24 * density), 0);
        // Trail smear stays inside each item; the scroll view clips the row.
        iconsLayout.setClipChildren(false);

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

        // Uniform spacing: every dock tile uses makeDockItem's rightMargin.
        // Order only: projection (AA/CarPlay) → GWM hub → recents → remaining apps.
        projectionItem = makeDockItem(itemWidthPx, iconSizePx, density);
        projectionItem.setVisibility(View.GONE);
        iconsLayout.addView(projectionItem);
        launcherItems.add(projectionItem);

        for (int i = 0; i < PINNED_PACKAGES.length; i++) {
            String pkg = PINNED_PACKAGES[i];
            pinnedBound[i] = false;
            ResolveInfo info = byPkg.get(pkg);
            if (info == null) {
                if (isEmulatorDevice()) {
                    emulatorStubPackages.add(pkg);
                    pinnedBound[i] = true;
                    Log.w(TAG, "Launcher pinned stub " + pkg + " | " + pinnedLabel(pkg, pm, null));
                }
                continue;
            }
            pinnedBound[i] = true;
            Log.w(TAG, "Launcher pinned " + pkg + " | " + pinnedLabel(pkg, pm, info));
        }

        gwmHubItem = makeDockItem(itemWidthPx, iconSizePx, density);
        gwmHubItem.setVisibility(View.GONE);
        iconsLayout.addView(gwmHubItem);
        launcherItems.add(gwmHubItem);
        bindGwmHub();
        updatePinnedVisibility();

        sideBySideItem = makeDockItem(itemWidthPx, iconSizePx, density);
        iconsLayout.addView(sideBySideItem);
        launcherItems.add(sideBySideItem);
        bindSideBySideItem();

        for (int i = 0; i < recentItems.length; i++) {
            recentItems[i] = makeDockItem(itemWidthPx, iconSizePx, density);
            recentItems[i].setVisibility(View.GONE);
            iconsLayout.addView(recentItems[i]);
            launcherItems.add(recentItems[i]);
        }

        seedRecentAppsFromSystem(apps);
        dockItemsByPackage.clear();

        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            if (excludedFromAppRow(pkg)) continue;

            String labelStr = launcherLabel(pm, info);
            Drawable iconDrawable = iconForLauncherApp(pm, info);
            MotionTrailLayout itemLayout = makeDockItem(itemWidthPx, iconSizePx, density);
            bindDockItem(itemLayout, iconDrawable, labelStr,
                    v -> launchAppForPackage(pkg, labelStr), pkg);
            iconsLayout.addView(itemLayout);
            launcherItems.add(itemLayout);
            dockItemsByPackage.put(pkg, itemLayout);
            // hideRecentDuplicatesFromStrip (via bindRecentSlots below) settles the
            // final visibility, including the user's hide list.
            if (isUserHidden(pkg)) itemLayout.setVisibility(View.GONE);
            Log.w(TAG, "Launcher " + (isUserHidden(pkg) ? "hidden " : "shown ") + pkg + " | " + labelStr
                    + (isGwmApp(pkg) ? " | gwm" : ""));
        }

        if (isEmulatorDevice()) {
            for (String[] stub : EMULATOR_EXTRA_GWM_STUBS) {
                String pkg = stub[0];
                String labelStr = stub[1];
                if (byPkg.containsKey(pkg) || excludedFromAppRow(pkg)) continue;
                emulatorStubPackages.add(pkg);
                MotionTrailLayout itemLayout = makeDockItem(itemWidthPx, iconSizePx, density);
                bindDockItem(itemLayout, launcherIconForPackage(pkg), labelStr,
                        v -> launchAppForPackage(pkg, labelStr), pkg);
                iconsLayout.addView(itemLayout);
                launcherItems.add(itemLayout);
                dockItemsByPackage.put(pkg, itemLayout);
                Log.w(TAG, "Launcher emulator stub " + pkg + " | " + labelStr);
            }
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

        host.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        cardsScrollView = buildQuickCardsRow(density, iconSizePx);
        cardsScrollView.setVisibility(DOCK_SURFACE_CARDS.equals(dockSurfaceMode)
                ? View.VISIBLE : View.GONE);
        cardsScrollView.setAlpha(DOCK_SURFACE_CARDS.equals(dockSurfaceMode) ? 1f : 0f);
        host.addView(cardsScrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        scrollView.setVisibility(DOCK_SURFACE_LAUNCHER.equals(dockSurfaceMode)
                ? View.VISIBLE : View.GONE);
        scrollView.setAlpha(DOCK_SURFACE_LAUNCHER.equals(dockSurfaceMode) ? 1f : 0f);

        layoutContentRow = buildLayoutContentRow(density, itemWidthPx, iconSizePx);
        layoutContentRow.setVisibility(View.GONE);
        layoutContentRow.setAlpha(0f);
        host.addView(layoutContentRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        configContentScroll = buildConfigContentRow(density, itemWidthPx, iconSizePx);
        configContentScroll.setVisibility(View.GONE);
        configContentScroll.setAlpha(0f);
        host.addView(configContentScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Icons live inside the same side band as the rest of the chrome.
        strip.setPadding(pageSideInsetPx(false), strip.getPaddingTop(),
                pageSideInsetPx(true), strip.getPaddingBottom());
        rootLayout.addView(impulseBand);
        rootLayout.addView(strip);
        applyImpulseReserveBandGeometry();
        // popupBottom is measured from this actual strip, so send one corrected
        // geometry payload once Android has positioned it (including overscan).
        strip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> {
            if (l != ol || t != ot || r != or_ || b != ob) {
                mainHandler.post(this::notifyViewerShellLayout);
            }
        });
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                scrollView.setHorizontalFadingEdgeEnabled(scrollX > 0));
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

        applyDrawerCollapsedUi(false);
        refreshLayoutChipSelection();
        refreshDockSurfaceUi(false);
        updateSlotAnchors();
        updateAppsOnlyChrome();
    }

    // ── Mode drawer (Apps / Layout / 3D) ──────────────────────────────────

    private static final String STRIP_APPS = "apps";
    private static final String STRIP_LAYOUT = "layout";
    private static final String STRIP_CONFIG = "config";

    private android.widget.LinearLayout buildModeDrawer(float density, int cellPx, int iconPx,
            int iconRowTopPad) {
        android.widget.LinearLayout drawer = new android.widget.LinearLayout(this);
        drawer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        drawer.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        drawer.setClipChildren(false);
        drawer.setBackgroundColor(0x00000000);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                cellPx, android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        lp.leftMargin = Math.round(8 * density);
        drawer.setLayoutParams(lp);

        // One clear dock affordance: the stacked-cards glyph returns to the
        // glanceable Cards surface. Layout and settings live in the Workspace
        // card, not a second hidden menu.
        modeCollapsedBtn = makeModeCell(density, cellPx, iconPx, iconRowTopPad,
                dockGlyphCards(cardsDockGlyphPx(iconPx, density), dockGlyphColor(false)),
                "Cards", v -> {
                    toggleDockSurface();
                    selectStripMode(STRIP_APPS);
                });
        modeCollapsedBtn.setContentDescription("Show quick cards");
        modeCollapsedBtn.setOnLongClickListener(v -> {
            callViewerDock("openDesktopStudio");
            return true;
        });
        modeCollapsedIcon = (android.widget.ImageView) modeCollapsedBtn.findViewWithTag("modeIcon");
        styleCardsDockCell(modeCollapsedBtn, density, iconPx);
        drawer.addView(modeCollapsedBtn);
        modeAppsBtn = null;
        modeLayoutBtn = null;
        modeConfigBtn = null;
        modeSurfaceBtn = null;
        return drawer;
    }

    private View makeModeCell(float density, int cellPx, int iconPx, int iconRowTopPad,
            Drawable glyph, String label, View.OnClickListener click) {
        android.widget.LinearLayout cell = new android.widget.LinearLayout(this);
        cell.setOrientation(android.widget.LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(cellPx,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        cell.setLayoutParams(lp);
        cell.setPadding(0, iconRowTopPad, 0, 0);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setOnClickListener(click);
        if (label != null && !label.isEmpty()) cell.setContentDescription(label);

        FrameLayout iconWrap = new FrameLayout(this);
        int wrapSize = dockPlatePx(iconPx, density);
        iconWrap.setLayoutParams(new android.widget.LinearLayout.LayoutParams(wrapSize, wrapSize));
        iconWrap.setBackgroundColor(0x00000000);

        View plate = new View(this);
        FrameLayout.LayoutParams plateLp = new FrameLayout.LayoutParams(wrapSize, wrapSize);
        plateLp.gravity = android.view.Gravity.CENTER;
        plate.setLayoutParams(plateLp);
        plate.setTag("modePlate");
        plate.setBackground(makeDockPlateDrawable(false, density));
        iconWrap.addView(plate);

        // Glyph inset so layout/settings artwork sits centred in the plate.
        int glyphPx = Math.round(iconPx * 0.68f);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setTag("modeIcon");
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(glyphPx, glyphPx);
        ivLp.gravity = android.view.Gravity.CENTER;
        iv.setLayoutParams(ivLp);
        iv.setImageDrawable(glyph);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(false);
        iconWrap.addView(iv);
        cell.addView(iconWrap);

        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setTag("modeLabel");
        tv.setText(label != null ? label : "");
        styleDockCaption(tv, density);
        tv.setTextColor(dockLabelColorMuted());
        tv.setLetterSpacing(0.02f);
        if (dockUiLight) tv.setShadowLayer(0f, 0f, 0f, 0);
        tv.setVisibility(View.INVISIBLE);
        cell.addView(tv);
        return cell;
    }

    private View makeModeCell(float density, int cellPx, int iconPx, Drawable glyph,
            String label, View.OnClickListener click) {
        int topPad = dockIconRowTopPadPx > 0 ? dockIconRowTopPadPx
                : Math.round(8 * density);
        return makeModeCell(density, cellPx, iconPx, topPad, glyph, label, click);
    }

    private void setModeCellLabelVisible(View cell, boolean visible) {
        if (cell == null) return;
        View label = cell.findViewWithTag("modeLabel");
        if (label == null) return;
        CharSequence text = (label instanceof android.widget.TextView)
                ? ((android.widget.TextView) label).getText() : null;
        boolean has = text != null && text.length() > 0;
        label.setVisibility(visible && has ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Widget-light → frosted plates + soft gray gradient rim.
     * Widget-dark → frosted white plates + white rim (classic dock).
     */
    private android.graphics.drawable.Drawable makeDockPlateDrawable(
            boolean selected, float density) {
        final float r = 14f * density;
        final int strokePx = Math.max(1, Math.round((selected ? 1.5f : 1f) * density));
        if (dockUiLight) {
            android.graphics.drawable.GradientDrawable border =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            dockPlateBorderGradientColors(selected));
            border.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            border.setCornerRadius(r);

            android.graphics.drawable.GradientDrawable fill =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            dockPlateGradientColors(selected));
            fill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            fill.setCornerRadius(Math.max(0f, r - strokePx));

            android.graphics.drawable.LayerDrawable layers =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[] { border, fill });
            layers.setLayerInset(1, strokePx, strokePx, strokePx, strokePx);
            return layers;
        }

        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        dockPlateGradientColors(selected));
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(r);
        d.setStroke(strokePx,
                selected ? 0xA8FFFFFF : 0x50FFFFFF);
        return d;
    }

    /** Soft gray rim for light-widget plates — no hard black stroke. */
    private int[] dockPlateBorderGradientColors(boolean selected) {
        return selected
                ? new int[] { withAlpha(dockAccentColor, 0xD0), 0x8898AABC }
                : new int[] { 0xA0B8C8D4, 0x60A0B0C0 };
    }

    /** Frosted glass fills — keep alpha high enough to read light on the dark drawer band. */
    private int[] dockPlateGradientColors(boolean selected) {
        if (dockUiLight) {
            return selected
                    ? new int[] { blendArgb(0xFFF8FAFC, dockAccentColor, 0.13f),
                            blendArgb(0xFFEDF2F6, dockAccentColor, 0.08f) }
                    : new int[] { 0xFFFAFCFE, 0xFFF2F6FA };
        }
        return selected
                ? new int[] { withAlpha(dockAccentColor, 0x72), 0x58FFFFFF }
                : new int[] { 0x5AFFFFFF, 0x48FFFFFF };
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private static int blendArgb(int base, int tint, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(((base >> 16) & 0xff) * (1f - a) + ((tint >> 16) & 0xff) * a);
        int g = Math.round(((base >> 8) & 0xff) * (1f - a) + ((tint >> 8) & 0xff) * a);
        int b = Math.round((base & 0xff) * (1f - a) + (tint & 0xff) * a);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

  /** Layout/config drawer band stays transparent like the apps launcher strip. */
    private void applyDockPanelBackground() {
        clearDockStripBackground(stripRow);
        clearDockStripBackground(contentHost);
        clearDockStripBackground(appsScrollView);
        clearDockStripBackground(cardsScrollView);
        clearDockStripBackground(modeDrawer);
        clearDockStripBackground(layoutContentRow);
        clearDockStripBackground(configContentScroll);
    }

    private void clearDockStripBackground(View v) {
        if (v == null) return;
        v.setBackground(null);
        v.setBackgroundColor(0x00000000);
    }

    private void refreshAllDockPlates() {
        if (modeCollapsedBtn != null && modeCollapsedBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeCollapsedBtn, false);
        }
        if (modeAppsBtn != null && modeAppsBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeAppsBtn, STRIP_APPS.equals(stripMode));
        }
        if (modeLayoutBtn != null && modeLayoutBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeLayoutBtn, STRIP_LAYOUT.equals(stripMode));
        }
        if (modeConfigBtn != null && modeConfigBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeConfigBtn, STRIP_CONFIG.equals(stripMode));
        }
        if (modeSurfaceBtn != null && modeSurfaceBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeSurfaceBtn, DOCK_SURFACE_CARDS.equals(dockSurfaceMode));
        }
        refreshDockSurfaceUi(false);
        refreshConfigToolPlates();
        refreshLayoutTipPlate();
        refreshQuickCardsTheme();
    }

    private void refreshConfigToolPlates() {
        float density = getResources().getDisplayMetrics().density;
        for (android.widget.ImageView iv : dockToolIcons.values()) {
            if (iv == null || iv.getParent() == null) continue;
            View iconWrap = (View) iv.getParent();
            View plate = iconWrap.findViewWithTag("modePlate");
            if (plate != null) {
                plate.setBackground(makeDockPlateDrawable(false, density));
            }
        }
    }

    private void refreshLayoutTipPlate() {
        if (layoutTipLabel == null) return;
        View cell = (View) layoutTipLabel.getParent();
        if (cell == null) return;
        View plate = cell.findViewWithTag("modePlate");
        if (plate != null) {
            float density = getResources().getDisplayMetrics().density;
            plate.setBackground(makeDockPlateDrawable(false, density));
        }
    }

    private int dockGlyphColor() {
        return dockGlyphColor(true);
    }

    private int dockGlyphColor(boolean selected) {
        if (selected) return dockAccentColor;
        if (!dockUiLight) return 0xE8FFFFFF;
        return 0xFF7E8996;
    }

    private int dockLabelColor() {
        return dockUiLight ? 0xFF3D4550 : 0xFFFFFFFF;
    }

    private int dockLabelColorMuted() {
        return dockUiLight ? 0xFF9AA3AE : 0xCCFFFFFF;
    }

    private android.widget.TextView findDockChipLabel(View cell) {
        if (cell == null) return null;
        View label = cell.findViewWithTag("dockChipLabel");
        return label instanceof android.widget.TextView
                ? (android.widget.TextView) label : null;
    }

    private void styleDockLabel(android.widget.TextView tv, boolean muted) {
        if (tv == null) return;
        tv.setTextColor(muted ? dockLabelColorMuted() : dockLabelColor());
    }

    private void applyDockUiTheme(boolean light) {
        if (dockUiLight == light) return;
        dockUiLight = light;
        refreshDockChromeTheme();
    }

    private void refreshDockChromeTheme() {
        applyDockPanelBackground();
        refreshAllDockPlates();
        refreshLayoutChipSelection();
        refreshLayoutThemeChip();
        styleDockLabel(layoutTipLabel, true);
        refreshModeDrawerGlyphs();
        refreshDockToolCaptions();
        refreshLauncherIconPlates();
        for (String cmd : dockToolIcons.keySet()) {
            refreshDockToolGlyph(cmd, dockToolGlyphFor(cmd));
        }
    }

    private void refreshDockToolCaptions() {
        for (android.widget.ImageView iv : dockToolIcons.values()) {
            if (iv == null || iv.getParent() == null) continue;
            View iconWrap = (View) iv.getParent();
            android.view.ViewParent colParent = iconWrap.getParent();
            if (!(colParent instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout col = (android.widget.LinearLayout) colParent;
            for (int i = 0; i < col.getChildCount(); i++) {
                View child = col.getChildAt(i);
                if (child instanceof android.widget.TextView
                        && "dockToolCaption".equals(child.getTag())) {
                    android.widget.TextView caption = (android.widget.TextView) child;
                    styleDockLabel(caption, true);
                    if (dockUiLight) caption.setShadowLayer(0f, 0f, 0f, 0);
                    else caption.setShadowLayer(3f, 0f, 1f, 0x99000000);
                }
            }
        }
    }

    private String dockToolGlyphFor(String cmd) {
        if ("model".equals(cmd)) return dockModelLabel;
        if ("perf".equals(cmd)) return dockPerfLabel;
        if ("paint".equals(cmd)) return dockPaintHex;
        if ("time".equals(cmd)) return dockTimeMode;
        if ("fps".equals(cmd)) return dockFpsOn ? "ON" : "OFF";
        if ("xray".equals(cmd)) return dockXrayOn ? "ON" : "OFF";
        return "";
    }

    private void refreshModeDrawerGlyphs() {
        if (modeAppsBtn != null && modeAppsBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeAppsBtn, STRIP_APPS.equals(stripMode));
        }
        if (modeLayoutBtn != null && modeLayoutBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeLayoutBtn, STRIP_LAYOUT.equals(stripMode));
        }
        if (modeConfigBtn != null && modeConfigBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeConfigBtn, STRIP_CONFIG.equals(stripMode));
        }
        if (modeCollapsedBtn != null && modeCollapsedBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeCollapsedBtn, false);
        }
        if (modeSurfaceBtn != null && modeSurfaceBtn.getVisibility() == View.VISIBLE) {
            setModeCellSelected(modeSurfaceBtn, DOCK_SURFACE_CARDS.equals(dockSurfaceMode));
        }
    }

    private void setModeCellIcon(View cell, Drawable glyph) {
        if (cell == null) return;
        android.widget.ImageView iv = cell.findViewWithTag("modeIcon");
        if (iv != null) iv.setImageDrawable(glyph);
    }

    private Drawable glyphForModeCell(View cell, int iconPx, boolean selected) {
        int color = dockGlyphColor(selected);
        if (cell == modeAppsBtn) return dockGlyphApps(iconPx, color);
        if (cell == modeLayoutBtn) return dockGlyphLayout(iconPx, color);
        if (cell == modeConfigBtn) return dockGlyphConfig(iconPx, color);
        if (cell == modeSurfaceBtn) return DOCK_SURFACE_CARDS.equals(dockSurfaceMode)
                ? dockGlyphApps(iconPx, color) : dockGlyphCards(iconPx, color);
        if (cell == modeCollapsedBtn) return dockGlyphCards(iconPx, color);
        return null;
    }

    private android.widget.TextView findModeCellLabel(View cell) {
        if (cell == null) return null;
        View label = cell.findViewWithTag("modeLabel");
        return label instanceof android.widget.TextView
                ? (android.widget.TextView) label : null;
    }

    private void refreshLayoutThemeChip() {
        if (layoutThemeChip == null) return;
        String mode = dockWidgetThemeMode != null ? dockWidgetThemeMode : "dark";
        String eff = dockWidgetThemeEffective != null ? dockWidgetThemeEffective : "dark";
        String label;
        if ("auto".equals(mode)) {
            label = "Appearance · Auto";
        } else if ("light".equals(mode)) {
            label = "Appearance · Light";
        } else {
            label = "Appearance · Dark";
        }
        if (layoutThemeLabel != null) layoutThemeLabel.setText(label);
        styleDockLabel(layoutThemeLabel, false);
        setModeCellSelected(layoutThemeChip, "light".equals(eff));
    }

    /** Solid rounded square behind third-party launcher icons (not dock glyphs). */
    private android.graphics.drawable.Drawable makeLauncherIconPlateDrawable(
            float density, boolean dark) {
        int stroke = Math.max(1, Math.round(density));
        float radius = 14f * density;
        android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                dark ? new int[] { 0x7AFFFFFF, 0x1FFFFFFF }
                        : (dockUiLight ? new int[] { 0x66FFFFFF, 0x2493A2B2 }
                                : new int[] { 0x88FFFFFF, 0x34FFFFFF }));
        border.setCornerRadius(radius);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                dark ? (dockUiLight ? new int[] { 0xFF202733, 0xFF10151C }
                        : new int[] { 0xF20A0D12, 0xEC000000 })
                        : (dockUiLight ? new int[] { 0xFFFCFDFE, 0xFFF0F4F7 }
                                : new int[] { 0xF8F3F6FA, 0xE8DFE6EC }));
        fill.setCornerRadius(Math.max(0f, radius - stroke));
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new Drawable[] { border, fill });
        layers.setLayerInset(1, stroke, stroke, stroke, stroke);
        return layers;
    }

    /** Custom plate fill from Personalizar — hairline so black still reads on the dark dock. */
    private android.graphics.drawable.Drawable makeLauncherIconPlateDrawable(
            float density, int fillColor) {
        int stroke = Math.max(1, Math.round(density));
        float radius = 14f * density;
        android.graphics.drawable.GradientDrawable fill =
                new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(radius);
        fill.setColor(fillColor);
        fill.setStroke(stroke, 0x38FFFFFF);
        return fill;
    }

    /** Saved plate colour when a substitute icon is in force; null keeps the default plate. */
    private Integer dockOverridePlateColor(String pkg) {
        if (pkg == null || dockAppOverrides == null) return null;
        if (dockAppOverrides.icon(pkg) == null) return null;
        String bg = dockAppOverrides.bg(pkg);
        if (bg == null) return null;
        return Integer.valueOf(DockAppOverrides.parseColor(bg, 0xFF3D4650));
    }

    /**
     * Plate baked into APP+APP PNGs so a customized glyph matches the dock tile.
     * Stock adaptive icons already fill the square; those stay unplated.
     */
    private Integer appsCatalogPlateColor(String pkg) {
        Integer custom = dockOverridePlateColor(pkg);
        if (custom != null) return custom;
        boolean substitute = dockAppOverrides != null && dockAppOverrides.icon(pkg) != null;
        if (!substitute && !usesDarkIconPlate(pkg)) return null;
        boolean dark = usesDarkIconPlate(pkg);
        int fill = dark
                ? (dockUiLight ? 0xFF202733 : 0xF20A0D12)
                : (dockUiLight ? 0xFFFCFDFE : 0xF8F3F6FA);
        return Integer.valueOf(fill);
    }

    /** PNG data URL for APP+APP tiles — same glyph/plate the dock already resolved. */
    private String encodeIconDataUrl(Drawable icon, int size, Integer plateColor) {
        if (icon == null || size <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        if (plateColor != null) {
            android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            fill.setColor(plateColor.intValue());
            float r = size * 0.22f;
            canvas.drawRoundRect(0, 0, size, size, r, r, fill);
            int pad = Math.round(size * 0.16f);
            icon.setBounds(pad, pad, size - pad, size - pad);
        } else {
            icon.setBounds(0, 0, size, size);
        }
        icon.draw(canvas);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
        return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    /** Square plate behind every dock icon — mode drawer + launcher row share this size. */
    private static int dockPlatePx(int iconPx, float density) {
        return iconPx + Math.round(10f * density);
    }

    /** Stacked-cards mark inside the round Cards plate — 32/72 from the HTML picker. */
    private static int cardsDockGlyphPx(int iconPx, float density) {
        return Math.round(dockPlatePx(iconPx, density) * (32f / 72f));
    }

    private void styleCardsDockCell(View cell, float density, int iconPx) {
        if (cell == null) return;
        View plate = cell.findViewWithTag("modePlate");
        if (plate != null) plate.setBackground(makeCircleFrostDrawable(false, density));
        android.widget.ImageView iv = cell.findViewWithTag("modeIcon");
        if (iv == null) return;
        int glyphPx = cardsDockGlyphPx(iconPx, density);
        android.view.ViewGroup.LayoutParams lp = iv.getLayoutParams();
        lp.width = glyphPx;
        lp.height = glyphPx;
        iv.setLayoutParams(lp);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(dockGlyphCards(glyphPx, dockGlyphColor(false)));
    }

    private android.graphics.drawable.Drawable makeCircleFrostDrawable(
            boolean selected, float density) {
        int inset = Math.max(1, Math.round(density));
        android.graphics.drawable.GradientDrawable rim =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        selected
                                ? new int[] { withAlpha(dockAccentColor, 0xD8),
                                        withAlpha(dockAccentColor, 0x60) }
                                : (dockUiLight ? new int[] { 0xB8FFFFFF, 0x4F8A99A8 }
                                        : new int[] { 0x70FFFFFF, 0x20FFFFFF }));
        rim.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        int[] fillColors = dockUiLight
                ? (selected
                        ? new int[] { blendArgb(0xF8F8FAFC, dockAccentColor, 0.10f),
                                blendArgb(0xF0E9EEF3, dockAccentColor, 0.06f) }
                        : new int[] { 0xF2F7F9FB, 0xE9E9EFF4 })
                : (selected
                        ? new int[] { blendArgb(0xEB18232D, dockAccentColor, 0.08f), 0xE00E141B }
                        : new int[] { 0xE01A222D, 0xD90E141B });
        android.graphics.drawable.GradientDrawable fill =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        fillColors);
        fill.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(new Drawable[] { rim, fill });
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    /**
     * No inset. A normalized adaptive icon (see {@link #normalizeAdaptiveIcon})
     * is always drawn back out as a fully opaque {@code sizePx x sizePx}
     * bitmap — its background layer fills that square edge to edge regardless
     * of {@link #adaptiveIconFillScale}, so {@code clipToOutline} in
     * {@link #wrapLauncherIconPlate} only ever trims that bitmap's own corner
     * pixels, exactly like a real launcher's mask. Insetting here instead
     * shrinks the icon inside the frame and exposes the plate's own fill as a
     * visible ring around every icon — tried once, and it read as a border
     * around icons that never needed the padding.
     */
    private static float launcherIconInsetFrac() {
        return 0f;
    }

    /**
     * Wraps a launcher {@link android.widget.ImageView} in a square rounded plate
     * with drop shadow so adaptive / circular / odd-shaped icons read uniformly.
     */
    private android.widget.FrameLayout wrapLauncherIconPlate(
            android.widget.ImageView iconView, int iconSizePx, float density) {
        return wrapLauncherIconPlate(iconView, iconSizePx, density, false);
    }

    private android.widget.FrameLayout wrapLauncherIconPlate(
            android.widget.ImageView iconView, int iconSizePx, float density, boolean darkPlate) {
        int platePx = dockPlatePx(iconSizePx, density);
        android.widget.FrameLayout iconBox = new android.widget.FrameLayout(this);
        iconBox.setClipChildren(false);
        iconBox.setClipToPadding(false);

        android.widget.FrameLayout iconFrame = new android.widget.FrameLayout(this);
        iconFrame.setTag("iconPlate");
        android.widget.FrameLayout.LayoutParams frameLp =
                new android.widget.FrameLayout.LayoutParams(platePx, platePx);
        frameLp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
        iconFrame.setLayoutParams(frameLp);
        iconFrame.setBackground(makeLauncherIconPlateDrawable(density, darkPlate));
        iconFrame.setElevation(7f * density);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            iconFrame.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            // Crop the icon itself to the plate's rounded-square outline instead of
            // padding it away from the corners — that's what let a full-bleed icon
            // fill edge to edge without square corners poking out past the plate.
            iconFrame.setClipToOutline(true);
        }

        int inset = Math.round(platePx * launcherIconInsetFrac());
        iconFrame.setPadding(inset, inset, inset, inset);
        iconView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iconFrame.addView(iconView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        iconBox.addView(iconFrame);
        return iconBox;
    }

    private void setModeCellSelected(View cell, boolean selected) {
        if (cell == null) return;
        cell.setSelected(selected);
        View plate = cell.findViewWithTag("modePlate");
        float density = getResources().getDisplayMetrics().density;
        int iconPx = dockIconPx > 0 ? dockIconPx
                : Math.round(60 * getResources().getDisplayMetrics().density);
        if (plate != null) {
            plate.setBackground(cell == modeCollapsedBtn
                    ? makeCircleFrostDrawable(selected, density)
                    : makeDockPlateDrawable(selected, density));
        }
        cell.setAlpha(dockUiLight ? 1f : (selected ? 1f : 0.78f));
        android.widget.ImageView iv = cell.findViewWithTag("modeIcon");
        if (iv != null) {
            int glyphPx = cell == modeCollapsedBtn
                    ? cardsDockGlyphPx(iconPx, density) : iconPx;
            Drawable glyph = glyphForModeCell(cell, glyphPx, selected);
            if (glyph != null) iv.setImageDrawable(glyph);
        }
        android.widget.TextView label = findModeCellLabel(cell);
        if (label != null) {
            label.setTextColor(selected ? dockLabelColor() : dockLabelColorMuted());
        }
    }

    private Drawable dockGlyphApps(int sizePx, int color) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        float s = sizePx;
        float pad = s * 0.18f;
        float gap = s * 0.14f;
        float dot = (s - 2f * pad - 2f * gap) / 3f;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float cx = pad + col * (dot + gap) + dot * 0.5f;
                float cy = pad + row * (dot + gap) + dot * 0.5f;
                c.drawCircle(cx, cy, dot * 0.42f, p);
            }
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private void refreshLauncherIconPlates() {
        float density = getResources().getDisplayMetrics().density;
        for (MotionTrailLayout item : launcherItems) {
            if (item == null) continue;
            View plate = item.findViewWithTag("iconPlate");
            if (plate == null) continue;
            Object tag = item.getTag();
            if (tag instanceof Integer) {
                plate.setBackground(makeLauncherIconPlateDrawable(density, ((Integer) tag).intValue()));
            } else {
                boolean dark = Boolean.TRUE.equals(tag);
                plate.setBackground(makeLauncherIconPlateDrawable(density, dark));
            }
        }
    }

    private android.graphics.Paint dockGlyphStroke(int color, int sizePx) {
        return dockGlyphStroke(color, sizePx, 0.055f);
    }

    private android.graphics.Paint dockGlyphStroke(int color, int sizePx, float widthFrac) {
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        p.setStrokeWidth(Math.max(1.75f, sizePx * widthFrac));
        return p;
    }

    private Drawable dockGlyphCards(int sizePx, int color) {
        // HTML cards-stack in a 24 viewBox, shown at 32px inside a 72px circle.
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        bmp.setDensity(getResources().getDisplayMetrics().densityDpi);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = dockGlyphStroke(color, sizePx, 1.7f / 24f);
        float s = sizePx;
        float rx = s * (2f / 24f);
        c.drawRoundRect(s * (6.2f / 24f), s * (3.4f / 24f),
                s * (20.4f / 24f), s * (13.8f / 24f), rx, rx, p);
        c.drawRoundRect(s * (3.4f / 24f), s * (9.4f / 24f),
                s * (17.6f / 24f), s * (20.6f / 24f), rx, rx, p);
        return new BitmapDrawable(getResources(), bmp);
    }

    private Drawable dockGlyphAppTiles(int sizePx, int color) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        bmp.setDensity(getResources().getDisplayMetrics().densityDpi);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = dockGlyphStroke(color, sizePx, 0.05f);
        float s = sizePx;
        float inset = p.getStrokeWidth() * 0.5f + 0.5f;
        float gap = s * 0.14f;
        float tile = (s - 2f * inset - gap) / 2f;
        float rx = s * 0.08f;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                float l = inset + col * (tile + gap);
                float t = inset + row * (tile + gap);
                c.drawRoundRect(l, t, l + tile, t + tile, rx, rx, p);
            }
        }
        return new BitmapDrawable(getResources(), bmp);
    }

    private Drawable dockGlyphMosaic(int sizePx, int color) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        bmp.setDensity(getResources().getDisplayMetrics().densityDpi);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = dockGlyphStroke(color, sizePx, 0.05f);
        float s = sizePx;
        float inset = p.getStrokeWidth() * 0.5f + 0.5f;
        float gap = s * 0.12f;
        float leftW = s * 0.42f;
        float rightL = inset + leftW + gap;
        float midY = s * 0.5f;
        float rx = s * 0.08f;
        c.drawRoundRect(inset, inset, inset + leftW, s - inset, rx, rx, p);
        c.drawRoundRect(rightL, inset, s - inset, midY - gap * 0.5f, rx, rx, p);
        c.drawRoundRect(rightL, midY + gap * 0.5f, s - inset, s - inset, rx, rx, p);
        return new BitmapDrawable(getResources(), bmp);
    }

    private Drawable dockGlyphLayout(int sizePx, int color) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(android.graphics.Paint.Style.FILL);
        float s = sizePx;
        float padY = s * 0.18f;
        float h = s - 2f * padY;
        float wApp = s * 0.18f;
        float wCar = s * 0.28f;
        float gap = s * 0.06f;
        float total = wApp + gap + wCar + gap + wApp;
        float x = (s - total) * 0.5f;
        c.drawRoundRect(x, padY, x + wApp, padY + h, 3f, 3f, p);
        x += wApp + gap;
        p.setAlpha(140);
        c.drawRoundRect(x, padY + h * 0.15f, x + wCar, padY + h * 0.85f, 4f, 4f, p);
        p.setAlpha(255);
        x += wCar + gap;
        c.drawRoundRect(x, padY, x + wApp, padY + h, 3f, 3f, p);
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private Drawable dockGlyphConfig(int sizePx, int color) {
        Drawable d = systemDockIcon(android.R.drawable.ic_menu_preferences, color);
        return d != null ? d : new android.graphics.drawable.ColorDrawable(color);
    }

    private Drawable glyphForStripMode(String mode) {
        int size = dockIconPx > 0 ? dockIconPx
                : Math.round(60 * getResources().getDisplayMetrics().density);
        int color = dockGlyphColor(true);
        // Draw at full icon size; makeModeCell insets into the plate.
        if (STRIP_LAYOUT.equals(mode)) return dockGlyphLayout(size, color);
        if (STRIP_CONFIG.equals(mode)) return dockGlyphConfig(size, color);
        return dockGlyphApps(size, color);
    }

    private void setDrawerExpanded(boolean expanded) {
        drawerExpanded = expanded;
        if (expanded) applyDrawerExpandedUi();
        else applyDrawerCollapsedUi(true);
    }

    private void applyDrawerCollapsedUi(boolean animate) {
        drawerExpanded = false;
        // In Cards mode Workspace is the single navigation source (Apps +
        // Organize). Do not leave a duplicate Apps icon or an empty drawer cell.
        boolean showLauncherApps = !DOCK_SURFACE_CARDS.equals(dockSurfaceMode);
        if (modeCollapsedBtn != null) {
            modeCollapsedBtn.setVisibility(showLauncherApps ? View.VISIBLE : View.GONE);
            if (showLauncherApps) {
                setModeCellSelected(modeCollapsedBtn, false);
                setModeCellLabelVisible(modeCollapsedBtn, true);
            }
        }
        if (modeAppsBtn != null) {
            modeAppsBtn.setVisibility(View.GONE);
            setModeCellLabelVisible(modeAppsBtn, false);
        }
        if (modeLayoutBtn != null) {
            modeLayoutBtn.setVisibility(View.GONE);
            setModeCellLabelVisible(modeLayoutBtn, false);
        }
        if (modeConfigBtn != null) {
            modeConfigBtn.setVisibility(View.GONE);
            setModeCellLabelVisible(modeConfigBtn, false);
        }
        if (modeSurfaceBtn != null) {
            modeSurfaceBtn.setVisibility(View.GONE);
            setModeCellLabelVisible(modeSurfaceBtn, false);
        }
        if (modeDrawer != null && dockCellPx > 0) {
            modeDrawer.setVisibility(showLauncherApps ? View.VISIBLE : View.GONE);
            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) modeDrawer.getLayoutParams();
            if (lp != null) {
                final int target = showLauncherApps ? dockCellPx : 0;
                if (animate && lp.width != target) {
                    android.animation.ValueAnimator anim =
                            android.animation.ValueAnimator.ofInt(lp.width, target);
                    anim.setDuration(180);
                    anim.addUpdateListener(a -> {
                        lp.width = (Integer) a.getAnimatedValue();
                        modeDrawer.setLayoutParams(lp);
                    });
                    anim.start();
                } else {
                    lp.width = target;
                    modeDrawer.setLayoutParams(lp);
                }
            }
        }
    }

    private void applyDrawerExpandedUi() {
        drawerExpanded = true;
        if (modeCollapsedBtn != null) modeCollapsedBtn.setVisibility(View.GONE);
        if (modeAppsBtn != null) {
            modeAppsBtn.setVisibility(View.VISIBLE);
            setModeCellSelected(modeAppsBtn, STRIP_APPS.equals(stripMode));
            setModeCellLabelVisible(modeAppsBtn, true);
        }
        if (modeLayoutBtn != null) {
            modeLayoutBtn.setVisibility(View.VISIBLE);
            setModeCellSelected(modeLayoutBtn, STRIP_LAYOUT.equals(stripMode));
            setModeCellLabelVisible(modeLayoutBtn, true);
        }
        if (modeConfigBtn != null) {
            modeConfigBtn.setVisibility(View.VISIBLE);
            setModeCellSelected(modeConfigBtn, STRIP_CONFIG.equals(stripMode));
            setModeCellLabelVisible(modeConfigBtn, true);
        }
        if (modeSurfaceBtn != null) {
            modeSurfaceBtn.setVisibility(View.VISIBLE);
            setModeCellSelected(modeSurfaceBtn, DOCK_SURFACE_CARDS.equals(dockSurfaceMode));
            android.widget.TextView label = findModeCellLabel(modeSurfaceBtn);
            if (label != null) label.setText(DOCK_SURFACE_CARDS.equals(dockSurfaceMode)
                    ? "Launcher" : "Cards");
            modeSurfaceBtn.setContentDescription(DOCK_SURFACE_CARDS.equals(dockSurfaceMode)
                    ? "Show launcher. Long press to customize"
                    : "Show quick cards. Long press to customize");
            setModeCellLabelVisible(modeSurfaceBtn, true);
        }
        if (modeDrawer != null && dockCellPx > 0) {
            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) modeDrawer.getLayoutParams();
            if (lp != null) {
                final int target = dockCellPx * 4;
                android.animation.ValueAnimator anim =
                        android.animation.ValueAnimator.ofInt(Math.max(dockCellPx, lp.width), target);
                anim.setDuration(200);
                anim.addUpdateListener(a -> {
                    lp.width = (Integer) a.getAnimatedValue();
                    modeDrawer.setLayoutParams(lp);
                });
                anim.start();
            }
        }
    }

    private void selectStripMode(String mode) {
        if (mode == null) mode = STRIP_APPS;
        final String next = mode;
        final String prev = stripMode;
        stripMode = next;
        applyDrawerCollapsedUi(true);
        if (!next.equals(prev)) crossfadeStripContent(prev, next);
        else showStripContent(next, false);
        if (STRIP_LAYOUT.equals(prev) && !STRIP_LAYOUT.equals(next)) {
            callViewerDock("closePanel");
        }
    }

    private void showStripContent(String mode, boolean fade) {
        View apps = appsScrollView;
        View cards = cardsScrollView;
        View layout = layoutContentRow;
        View config = configContentScroll;
        // Editing the cards needs them on screen, whichever surface is chosen.
        View show = (railEditMode || DOCK_SURFACE_CARDS.equals(dockSurfaceMode)) ? cards : apps;
        if (STRIP_LAYOUT.equals(mode)) show = layout;
        else if (STRIP_CONFIG.equals(mode)) show = config;
        View[] all = { apps, cards, layout, config };
        for (View v : all) {
            if (v == null) continue;
            if (v == show) {
                v.setVisibility(View.VISIBLE);
                if (fade) {
                    v.animate().alpha(1f).setDuration(180).start();
                } else {
                    v.setAlpha(1f);
                }
            } else if (v.getVisibility() == View.VISIBLE) {
                if (fade) {
                    final View hide = v;
                    hide.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                        hide.setVisibility(View.GONE);
                    }).start();
                } else {
                    v.setAlpha(0f);
                    v.setVisibility(View.GONE);
                }
            }
        }
        if (STRIP_LAYOUT.equals(mode)) refreshLayoutChipSelection();
        applyDockPanelBackground();
    }

    private void crossfadeStripContent(String from, String to) {
        showStripContent(to, true);
    }

    private void refreshDockSurfaceUi(boolean animate) {
        boolean cards = DOCK_SURFACE_CARDS.equals(dockSurfaceMode);
        alignQuickCardsToWidgetBoard();
        setModeCellSelected(layoutLauncherSurfaceChip, !cards);
        setModeCellSelected(layoutCardsSurfaceChip, cards);
        if (layoutLauncherSurfaceChip != null) layoutLauncherSurfaceChip.setSelected(!cards);
        if (layoutCardsSurfaceChip != null) layoutCardsSurfaceChip.setSelected(cards);
        if (modeSurfaceBtn != null) {
            android.widget.TextView label = findModeCellLabel(modeSurfaceBtn);
            if (label != null) label.setText(cards ? "Launcher" : "Cards");
            modeSurfaceBtn.setContentDescription(cards
                    ? "Show launcher. Long press to customize"
                    : "Show quick cards. Long press to customize");
            if (modeSurfaceBtn.getVisibility() == View.VISIBLE) {
                setModeCellSelected(modeSurfaceBtn, cards);
                setModeCellLabelVisible(modeSurfaceBtn, true);
            }
        }
        if (STRIP_APPS.equals(stripMode) && appsScrollView != null && cardsScrollView != null) {
            showStripContent(STRIP_APPS, animate);
        }
        if (!drawerExpanded) applyDrawerCollapsedUi(false);
        refreshQuickCardsTheme();
        refreshDesktopIndicator();
    }

    /**
     * Mode hue for a driving card's wash, or 0 for no wash.
     *
     * Deliberately not the accent: the accent means "this is selected/live" all
     * over these cards, and a card-wide fill in that colour would drown the
     * signal. These are scene colours — green for eco, ice for snow — and they
     * sit under everything at low alpha.
     */
    private int drivingWashColor(String state) {
        if (state == null) return 0;
        switch (state) {
            case "eco": return 0xFF4FBF6A;
            case "normal": return 0xFF4A7FB5;
            case "sport": return 0xFFE0392C;
            case "snow": return 0xFF6FB6E8;
            case "sand": return 0xFFD9A650;
            case "mud": return 0xFF9A7346;
            case "awd": return 0xFF5C7A94;
            case "hev": return 0xFF7C74D6;
            case "evp": return 0xFF4F93DA;
            case "ev": return 0xFF35B98F;
            case "level1": return 0xFF6E7A93;
            case "level2": return 0xFF4C86C4;
            case "level3": return 0xFF3C63C0;
            case "onepedal": return 0xFF7C4FD0;
            default: return 0;
        }
    }

    private android.graphics.drawable.Drawable makeFrostLayer(boolean selected, float density) {
        return makeFrostLayer(selected, density, 0);
    }

    private android.graphics.drawable.Drawable makeFrostLayer(
            boolean selected, float density, int wash) {
        int inset = Math.max(1, Math.round(density));
        float radius = 16f * density;
        android.graphics.drawable.GradientDrawable rim = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                selected
                        ? new int[] { withAlpha(dockAccentColor, 0xD8), withAlpha(dockAccentColor, 0x60) }
                        : (dockUiLight ? new int[] { 0xB8FFFFFF, 0x4F8A99A8 }
                                : new int[] { 0x70FFFFFF, 0x20FFFFFF }));
        rim.setCornerRadius(radius);
        int[] fillColors;
        if (dockUiLight) {
            fillColors = selected
                    ? new int[] { blendArgb(0xF8F8FAFC, dockAccentColor, 0.10f),
                            blendArgb(0xF0E9EEF3, dockAccentColor, 0.06f) }
                    : new int[] { 0xF2F7F9FB, 0xE9E9EFF4 };
        } else {
            fillColors = selected
                    ? new int[] { blendArgb(0xEB18232D, dockAccentColor, 0.08f), 0xE00E141B }
                    : new int[] { 0xE01A222D, 0xD90E141B };
        }
        if (wash != 0) {
            // Strongest at the top of the card and nearly gone by the bottom, so
            // the readout keeps a clean ground to sit on.
            float top = dockUiLight ? 0.22f : 0.34f;
            float bottom = dockUiLight ? 0.02f : 0.04f;
            fillColors = new int[] {
                blendArgb(fillColors[0], wash, top),
                blendArgb(fillColors[fillColors.length - 1], wash, bottom),
            };
        }
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, fillColors);
        fill.setCornerRadius(Math.max(0f, radius - inset));
        if (wash == 0) {
            android.graphics.drawable.LayerDrawable plain =
                    new android.graphics.drawable.LayerDrawable(new Drawable[] { rim, fill });
            plain.setLayerInset(1, inset, inset, inset, inset);
            return plain;
        }

        // A soft pool of light behind the graphic. This is the part that reads
        // as imagery rather than as a coloured panel.
        android.graphics.drawable.GradientDrawable glow =
                new android.graphics.drawable.GradientDrawable();
        glow.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        glow.setCornerRadius(Math.max(0f, radius - inset));
        glow.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.22f, 0.34f);
        glow.setGradientRadius(118f * density);
        glow.setColors(new int[] { withAlpha(wash, dockUiLight ? 0x5A : 0x86),
                withAlpha(wash, dockUiLight ? 0x18 : 0x24), withAlpha(wash, 0) });

        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new Drawable[] { rim, fill, glow });
        layers.setLayerInset(1, inset, inset, inset, inset);
        layers.setLayerInset(2, inset, inset, inset, inset);
        return layers;
    }

    private android.graphics.drawable.Drawable makeFrostStateDrawable(
            boolean selected, float density) {
        return makeFrostStateDrawable(selected, density, 0);
    }

    private android.graphics.drawable.Drawable makeFrostStateDrawable(
            boolean selected, float density, int wash) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed }, makeFrostLayer(true, density, wash));
        states.addState(new int[] { android.R.attr.state_selected }, makeFrostLayer(true, density, wash));
        states.addState(new int[0], makeFrostLayer(selected, density, wash));
        return states;
    }

    private View buildQuickCardsRow(float density, int iconPx) {
        BounceHorizontalScrollView scroll = new BounceHorizontalScrollView(this, false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFadingEdgeLength(Math.round(42 * density));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setBackgroundColor(0x00000000);
        scroll.setHorizontalFadingEdgeEnabled(false);
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                scroll.setHorizontalFadingEdgeEnabled(scrollX > 0));

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        // Cards sit directly on the top edge of the lower band. This keeps
        // their top edge aligned with the widget board above while retaining
        // the 60dp Impulse reserve below the band.
        row.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        row.setPadding(Math.round(12 * density), Math.round(5 * density),
                Math.round(24 * density), Math.round(6 * density));
        quickCardsRow = row;
        populateQuickCardsRow(row, density);
        bindRailEditLongPress();

        scroll.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private void populateQuickCardsRow(android.widget.LinearLayout row, float density) {
        row.removeAllViews();
        quickCardViews.clear();
        quickCardHosts.clear();
        lastDrivingWash.clear();
        quickClimateValue = null;
        quickConsumptionValue = null;
        quickCardValues.clear();
        quickCardDetails.clear();
        quickCardGraphics.clear();
        quickClockCards.clear();
        quickCardDemoBadges.clear();
        quickMediaArt = null;
        quickMediaHasArt = false;
        quickMediaTitle = null;
        quickMediaArtist = null;
        quickMediaPlayPause = null;
        quickMediaAppIcon = null;
        quickMediaAppRow = null;
        quickMediaChip = null;
        if (quickMediaBars != null) quickMediaBars.setPlaying(false);
        quickMediaBars = null;
        quickMediaButtons.clear();

        // Cards mode starts with one predictable Workspace card. It exposes
        // both routes users need after hiding the launcher: get back to apps
        // or organize desktops/cards. This replaces the old Apps/Layout/
        // Settings/Launcher drawer quartet.
        row.addView(makeQuickWorkspaceCard(density));

        if (bottomCardsConfigured) {
            int count = Math.min(bottomCards.size(), bottomCardLimit);
            for (int i = 0; i < count; i++) {
                final BottomCardDescriptor descriptor = bottomCards.get(i);
                // Media is the one rail card with meaningful immediate controls.
                // Keep its transport surface when users reorder/select it instead
                // of reducing it to a generic navigation tile.
                if ("media".equals(descriptor.id)) {
                    View media = makeQuickMediaCard(density, descriptor);
                    media.setTag("bottomCard:" + descriptor.id);
                    quickCardHosts.put(descriptor.id, media);
                    row.addView(media);
                    continue;
                }
                final BottomCardDescriptor cardDescriptor = descriptor;
                View card = makeQuickVisualCard(density, descriptor, v -> {
                    // A card with a menu shows it; the menu's own last row is
                    // what reaches the full page.
                    if (!cardDescriptor.menu.isEmpty()) showQuickMenu(v, cardDescriptor);
                    else callViewerDock(cardDescriptor.action);
                });
                card.setTag("bottomCard:" + descriptor.id);
                android.widget.TextView valueView =
                        (android.widget.TextView) card.findViewWithTag("quickValue");
                if (valueView != null) quickCardValues.put(descriptor.id, valueView);
                row.addView(card);
            }
            replayMediaPayload();
            return;
        }

        View climate = makeQuickTextCard(density, 218, "CLIMATE",
                "— °C  ·  Fan —  ·  AUTO —",
                "Climate summary. Opens the climate page when available",
                v -> callViewerDock("openClimate"));
        quickClimateValue = (android.widget.TextView) climate.findViewWithTag("quickValue");
        row.addView(climate);

        View consumption = makeQuickTextCard(density, 196, "ENERGIA", "—",
                "Resumo de energia. Abre a tela de energia",
                v -> callViewerDock("openConsumption"));
        quickConsumptionValue = (android.widget.TextView) consumption.findViewWithTag("quickValue");
        row.addView(consumption);

        row.addView(makeQuickMediaCard(density, null));
        replayMediaPayload();
    }

    /**
     * Re-apply the last now-playing payload to freshly built media views.
     *
     * Same class of bug as the driving wash that refreshQuickCardsTheme has to
     * carry: state baked into a card at build time disappears the next time the
     * rail is rebuilt, and it disappears silently.
     */
    private void replayMediaPayload() {
        if (lastMediaPayload != null) updateQuickMediaCard(lastMediaPayload);
    }

    /**
     * CoffeeOS-style glance card: a purpose-built mini graphic, a strong live
     * readout and compact secondary metrics. Values still come from the web
     * payload, so the card never invents vehicle telemetry.
     */
    private View makeQuickVisualCard(float density, BottomCardDescriptor descriptor,
            View.OnClickListener click) {
        // Clock faces are designed on a 224 x 124 canvas. Giving them the
        // generic header/padding would make the card a different composition
        // from the Studio preview, so it owns the entire card surface.
        if ("clock".equals(descriptor.id)) {
            return makeQuickClockCard(density, descriptor, click);
        }
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.TOP);
        int padH = Math.round(13 * density);
        card.setPadding(padH, Math.round(9 * density), padH, Math.round(9 * density));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                Math.round(quickVisualCardWidthDp(descriptor.id) * density),
                Math.round(124 * density));
        lp.rightMargin = Math.round(10 * density);
        card.setLayoutParams(lp);
        card.setMinimumHeight(Math.round(112 * density));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(bottomCardAccessibilityDescription(descriptor));
        card.setOnClickListener(click);
        if (!descriptor.longAction.isEmpty()) {
            final String longCommand = descriptor.longAction;
            // Returning true consumes the gesture, so the long press cannot also
            // fire the card's ordinary click when the finger lifts.
            card.setLongClickable(true);
            card.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                callViewerDock(longCommand);
                return true;
            });
        }
        card.setBackground(makeFrostStateDrawable(false, density,
                drivingWashColor(descriptor.state)));
        card.setElevation(3f * density);
        quickCardViews.add(card);
        quickCardHosts.put(descriptor.id, card);
        lastDrivingWash.put(descriptor.id, drivingWashColor(descriptor.state));

        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // NAVIGATION's source-chip icon (SOURCE_CHIP_ICON_DP) is taller than this
        // 22dp strip. Grow the strip itself and NAVIGATION's graphic content below
        // loses that height -- drawNavigation sizes every font off its own view
        // height, so the whole card's type shrank. Let the icon overflow the
        // header visually instead: the strip stays 22dp for everyone, so nothing
        // downstream of it moves or resizes.
        header.setClipChildren(false);
        header.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(22 * density)));

        android.widget.TextView demoBadge = new android.widget.TextView(this);
        demoBadge.setTag("frostAccent");
        demoBadge.setText("DEMO");
        demoBadge.setTextSize(8f);
        demoBadge.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        demoBadge.setLetterSpacing(0.10f);
        demoBadge.setVisibility(descriptor.demo ? View.VISIBLE : View.GONE);
        android.widget.LinearLayout.LayoutParams demoBadgeLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        demoBadgeLp.rightMargin = Math.round(7 * density);
        header.addView(demoBadge, demoBadgeLp);
        quickCardDemoBadges.put(descriptor.id, demoBadge);

        android.widget.TextView heading = new android.widget.TextView(this);
        heading.setTag("frostSecondary");
        heading.setText(descriptor.title.toUpperCase(java.util.Locale.US));
        heading.setTextSize(9.5f);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        heading.setLetterSpacing(0.10f);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setTextColor(dockLabelColorMuted());
        heading.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(heading);

        // Same slot the MEDIA card puts its chip in: after the weighted
        // heading, before the chevron, so both read as one rail.
        boolean isNavigation = "navigation".equals(descriptor.id);
        if (isNavigation) {
            SourceChip navChip = makeSourceChip(density);
            android.widget.LinearLayout.LayoutParams navChipLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            // Bottom-align so the icon's overflow (it is taller than this 22dp
            // strip) goes up into the card's own top padding, not down into the
            // graphic content below -- which draws after this in Z-order and
            // would paint over a downward overflow.
            navChipLp.gravity = android.view.Gravity.BOTTOM;
            header.addView(navChip.row, navChipLp);
            quickCardSourceChips.put(descriptor.id, navChip);
            applyNavigationSourceChip(descriptor);
        }

        // NAVIGATION reads MEDIA's now-playing chip as "what is guiding" rather
        // than "open route details", so like MEDIA it has nothing to disclose
        // and skips the chevron every other CoffeeOS card shows.
        if (!isNavigation) {
            android.widget.TextView affordance = new android.widget.TextView(this);
            affordance.setTag("frostSecondary");
            affordance.setText("›");
            affordance.setTextSize(18f);
            affordance.setGravity(android.view.Gravity.CENTER);
            affordance.setTextColor(dockLabelColorMuted());
            header.addView(affordance, new android.widget.LinearLayout.LayoutParams(
                    Math.round(18 * density), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        card.addView(header);

        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        content.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams contentLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.topMargin = Math.round(2 * density);
        content.setLayoutParams(contentLp);

        QuickCardGraphicView graphic = new QuickCardGraphicView(this, descriptor);
        boolean fullGraphicCard = "tires".equals(descriptor.id) || "status".equals(descriptor.id)
                || "navigation".equals(descriptor.id);
        if (fullGraphicCard) {
            graphic.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            content.addView(graphic);
            card.addView(content);
            quickCardGraphics.put(descriptor.id, graphic);
            return card;
        }
        android.widget.LinearLayout.LayoutParams graphicLp = new android.widget.LinearLayout.LayoutParams(
                Math.round(("power".equals(descriptor.id) ? 156 : 76) * density),
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        graphicLp.rightMargin = Math.round(10 * density);
        graphic.setLayoutParams(graphicLp);
        if (!descriptor.iconAction.isEmpty()) {
            final String iconCommand = descriptor.iconAction;
            graphic.setClickable(true);
            graphic.setFocusable(true);
            graphic.setContentDescription(descriptor.title + ". Change to the next setting");
            graphic.setOnClickListener(v -> callViewerDock(iconCommand));
        }
        content.addView(graphic);

        android.widget.LinearLayout copy = new android.widget.LinearLayout(this);
        copy.setOrientation(android.widget.LinearLayout.VERTICAL);
        copy.setGravity(android.view.Gravity.CENTER_VERTICAL);
        copy.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        android.widget.TextView primary = new android.widget.TextView(this);
        primary.setTag("quickValue");
        primary.setText(quickVisualPrimary(descriptor));
        primary.setTextSize("clock".equals(descriptor.id) ? 24f : 21f);
        primary.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        primary.setTextColor(dockLabelColor());
        primary.setMaxLines(1);
        primary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(primary);

        android.widget.TextView detail = new android.widget.TextView(this);
        detail.setTag("frostSecondary");
        detail.setText(quickVisualDetail(descriptor));
        detail.setTextSize(9.5f);
        detail.setLetterSpacing(0.025f);
        detail.setLineSpacing(0f, 1.02f);
        // The source badge is long and must never ellipsise into something that
        // reads like a different claim ("DEMO · SIMULATED · NOT VEHICLE..." is
        // not the same statement).
        detail.setMaxLines("power".equals(descriptor.id) ? 4 : 3);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams detailLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = Math.round(3 * density);
        detail.setLayoutParams(detailLp);
        copy.addView(detail);
        content.addView(copy);
        card.addView(content);

        quickCardValues.put(descriptor.id, primary);
        quickCardDetails.put(descriptor.id, detail);
        quickCardGraphics.put(descriptor.id, graphic);
        return card;
    }

    private String quickVisualDetail(BottomCardDescriptor descriptor) {
        if ("power".equals(descriptor.id)) {
            return descriptor.metricA + "\n" + descriptor.metricB + "\n" + descriptor.secondary;
        }
        if ("tires".equals(descriptor.id)) {
            String rear = labelTirePair("RL", "RR", descriptor.metricA);
            if (descriptor.secondary.isEmpty()) return rear;
            return rear + (rear.isEmpty() ? "" : "\n") + descriptor.secondary;
        }
        String metrics = descriptor.metricA;
        if (!descriptor.metricB.isEmpty()) {
            metrics += (metrics.isEmpty() ? "" : "  ·  ") + descriptor.metricB;
        }
        if (descriptor.secondary.isEmpty()) return metrics;
        return descriptor.secondary + (metrics.isEmpty() ? "" : "\n" + metrics);
    }

    private String quickVisualPrimary(BottomCardDescriptor descriptor) {
        String primary = descriptor.primary.isEmpty() ? descriptor.value : descriptor.primary;
        return "tires".equals(descriptor.id) ? labelTirePair("FL", "FR", primary) : primary;
    }

    private String labelTirePair(String leftLabel, String rightLabel, String pair) {
        if (pair == null || pair.isEmpty()) return "";
        String[] values = pair.split("\\s*[·/]\\s*", 2);
        if (values.length != 2) return pair;
        return leftLabel + " " + values[0] + "   " + rightLabel + " " + values[1];
    }

    /** "12,40,0,..." from the page, clamped to 0-100 and at most 31 values. */
    private static int[] parseEnergyBars(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new int[0];
        String[] parts = raw.split(",");
        int[] out = new int[Math.min(parts.length, 31)];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = Math.max(0, Math.min(100, Integer.parseInt(parts[i].trim())));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private int quickVisualCardWidthDp(String id) {
        if ("power".equals(id)) return 318;
        if ("navigation".equals(id)) return 286;
        if ("roof".equals(id)) return 286;
        if ("status".equals(id) || "tires".equals(id)) return 270;
        if ("range".equals(id) || "consumption".equals(id)) return 258;
        if ("clock".equals(id)) return 224;
        return 238;
    }

    private View makeQuickClockCard(float density, BottomCardDescriptor descriptor,
            View.OnClickListener click) {
        FrameLayout card = new FrameLayout(this);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                Math.round(224 * density), Math.round(124 * density));
        lp.rightMargin = Math.round(10 * density);
        card.setLayoutParams(lp);
        card.setMinimumHeight(Math.round(112 * density));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(bottomCardAccessibilityDescription(descriptor));
        card.setOnClickListener(click);
        if (!descriptor.longAction.isEmpty()) {
            final String longCommand = descriptor.longAction;
            card.setLongClickable(true);
            card.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                callViewerDock(longCommand);
                return true;
            });
        }
        card.setBackground(makeFrostStateDrawable(false, density,
                drivingWashColor(descriptor.state)));
        card.setElevation(3f * density);
        quickCardViews.add(card);
        quickCardHosts.put(descriptor.id, card);
        lastDrivingWash.put(descriptor.id, drivingWashColor(descriptor.state));

        QuickClockCardView clock = new QuickClockCardView(this, descriptor);
        clock.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (quickClockFaceBitmap != null) clock.setRenderedFace(quickClockFaceBitmap);
        card.addView(clock);
        quickClockCards.put(descriptor.id, clock);
        return card;
    }

    private View makeQuickWorkspaceCard(float density) {
        // Split card: two full-height hit targets, no inner chips. Heading sits
        // behind the actions so it does not steal taps from the Apps half.
        FrameLayout card = new FrameLayout(this);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                Math.round(242 * density), Math.round(124 * density));
        lp.rightMargin = Math.round(10 * density);
        card.setLayoutParams(lp);
        card.setContentDescription("Workspace: app launcher and layout manager");
        card.setBackground(makeFrostStateDrawable(false, density));
        card.setElevation(3f * density);
        quickCardViews.add(card);

        android.widget.TextView heading = new android.widget.TextView(this);
        heading.setText(workspaceLayoutMode ? "LAYOUT" : "WORKSPACE");
        heading.setTag("frostSecondary");
        heading.setTextSize(10.5f);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        heading.setLetterSpacing(0.11f);
        heading.setTextColor(dockLabelColorMuted());
        heading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams headingLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        headingLp.leftMargin = Math.round(14 * density);
        headingLp.topMargin = Math.round(10 * density);
        heading.setLayoutParams(headingLp);
        heading.setVisibility(workspaceLayoutMode ? View.GONE : View.VISIBLE);
        card.addView(heading);
        workspaceHeading = heading;

        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actions.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // Title band is ~10dp inset + 10.5sp caption. Pad the stacks (not the
        // row) so APPS / LAYOUT centre in the leftover height and the halves
        // still receive taps on the title.
        int titleBand = Math.round(28 * density);
        actions.addView(makeWorkspaceAction(density, true, "APPS", "Show app launcher",
                v -> chooseDockSurface(DOCK_SURFACE_LAUNCHER, true)));

        View divider = new View(this);
        divider.setTag("workspaceSplit");
        android.widget.LinearLayout.LayoutParams dividerLp =
                new android.widget.LinearLayout.LayoutParams(
                        Math.max(1, Math.round(density)),
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        dividerLp.topMargin = titleBand;
        dividerLp.bottomMargin = Math.round(14 * density);
        divider.setLayoutParams(dividerLp);
        divider.setBackgroundColor(dockUiLight ? 0x30808080 : 0x32FFFFFF);
        actions.addView(divider);

        actions.addView(makeWorkspaceAction(density, false, "LAYOUT", "Show layout shortcuts",
                v -> setWorkspaceLayoutMode(true)));
        card.addView(actions);
        workspaceActionsFace = actions;
        View layoutFace = makeWorkspaceLayoutFace(density);
        card.addView(layoutFace);
        workspaceLayoutFace = layoutFace;
        // A rebuild keeps whichever face was up.
        actions.setVisibility(workspaceLayoutMode ? View.GONE : View.VISIBLE);
        layoutFace.setVisibility(workspaceLayoutMode ? View.VISIBLE : View.GONE);
        return card;
    }

    /**
     * The Workspace card's Layout face: the three Layout manager screens and the
     * way back, as a 2x2 in the same card. LAYOUT flips to it, Return flips back
     * (and closes whatever screen was open).
     */
    private View makeWorkspaceLayoutFace(float density) {
        workspaceLayoutCells.clear();
        android.widget.LinearLayout grid = new android.widget.LinearLayout(this);
        grid.setOrientation(android.widget.LinearLayout.VERTICAL);
        grid.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        int pad = Math.round(6 * density);
        // No title band: the LAYOUT heading is hidden on this face, so the four
        // cells take the whole card.
        grid.setPadding(pad, pad, pad, pad);
        final String[][] cells = {
                {"desktops", "Desktops", "openLayoutDesktops"},
                {"layout", "Cards & widgets", "openLayoutCards"},
                {"appearance", "Appearance", "openLayoutAppearance"},
                {"return", "Return", ""},
        };
        for (int r = 0; r < 2; r++) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < 2; c++) {
                final String[] def = cells[r * 2 + c];
                android.widget.TextView cell = new android.widget.TextView(this);
                cell.setText(def[1]);
                cell.setTag("workspaceLayoutCell:" + def[0]);
                cell.setGravity(android.view.Gravity.CENTER);
                cell.setTextSize(13f);
                cell.setMaxLines(1);
                cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cell.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.NORMAL));
                cell.setContentDescription("return".equals(def[0]) ? "Return to workspace" : "Open " + def[1]);
                android.widget.LinearLayout.LayoutParams cellLp = new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                int m = Math.round(2 * density);
                cellLp.setMargins(m, m, m, m);
                cell.setLayoutParams(cellLp);
                cell.setClickable(true);
                cell.setFocusable(true);
                cell.setOnClickListener(v -> {
                    if ("return".equals(def[0])) {
                        setWorkspaceLayoutMode(false);
                        if (desktopStudioOpen) callViewerDock("closeDesktopStudio");
                    } else {
                        callViewerDock(def[2]);
                    }
                });
                workspaceLayoutCells.add(cell);
                row.addView(cell);
            }
            grid.addView(row);
        }
        styleWorkspaceLayoutCells();
        return grid;
    }

    /** Colours and the "this screen is open" mark; re-run on theme and screen changes. */
    private void styleWorkspaceLayoutCells() {
        float density = getResources().getDisplayMetrics().density;
        for (android.widget.TextView cell : workspaceLayoutCells) {
            String key = String.valueOf(cell.getTag()).substring("workspaceLayoutCell:".length());
            boolean isReturn = "return".equals(key);
            boolean on = !isReturn && key.equals(studioScreen);
            // The three screens stay neutral (accent only marks the open one);
            // Return wears the accent and carries a back arrow.
            cell.setTextColor(on ? dockAccentColor : dockLabelColor());
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(9 * density);
            bg.setColor(isReturn ? withAlpha(dockAccentColor, 0x33)
                    : on ? withAlpha(dockAccentColor, 0x2E) : (dockUiLight ? 0x0F000000 : 0x12FFFFFF));
            if (on) bg.setStroke(Math.max(1, Math.round(density)), withAlpha(dockAccentColor, 0xA0));
            android.graphics.drawable.Drawable content = bg;
            if (isReturn) {
                // Drawn as a centred background layer, not a compound drawable:
                // an empty TextView still reserves a text line under a top
                // compound, which pushed the arrow above the middle.
                cell.setText("");
                android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {
                                bg, workspaceBackGlyph(density, dockAccentColor) });
                layers.setLayerGravity(1, android.view.Gravity.CENTER);
                content = layers;
            }
            cell.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(withAlpha(dockLabelColor(), 0x33)), content, null));
        }
    }

    /** A left arrow (shaft + chevron) for the Layout face's Return cell. */
    private android.graphics.drawable.Drawable workspaceBackGlyph(float density, int color) {
        int box = Math.max(8, Math.round(22 * density));
        Bitmap bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        p.setStrokeWidth(2.2f * box / 24f);
        p.setColor(color);
        float u = box / 24f;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(19 * u, 12 * u);
        path.lineTo(5 * u, 12 * u);
        path.moveTo(11 * u, 6 * u);
        path.lineTo(5 * u, 12 * u);
        path.lineTo(11 * u, 18 * u);
        c.drawPath(path, p);
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private void setWorkspaceLayoutMode(boolean on) {
        if (workspaceLayoutMode == on) return;
        workspaceLayoutMode = on;
        if (workspaceHeading != null) {
            workspaceHeading.setText(on ? "LAYOUT" : "WORKSPACE");
            workspaceHeading.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        crossfadeFaces(on ? workspaceActionsFace : workspaceLayoutFace,
                on ? workspaceLayoutFace : workspaceActionsFace);
    }

    private void crossfadeFaces(View out, View in) {
        if (in != null) {
            in.animate().cancel();
            in.setAlpha(0f);
            in.setVisibility(View.VISIBLE);
            in.animate().alpha(1f).setDuration(160).start();
        }
        if (out != null) {
            out.animate().cancel();
            out.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                out.setVisibility(View.GONE);
                out.setAlpha(1f);
            }).start();
        }
    }

    private View makeWorkspaceAction(float density, boolean apps, String label,
            String description, View.OnClickListener click) {
        // Icon + caption centred as one stack. A TextView compound-drawable
        // centres the label and parks the glyph above it, which is why
        // WORKSPACE sat on the Apps tiles in the first car capture.
        android.widget.LinearLayout cell = new android.widget.LinearLayout(this);
        cell.setOrientation(android.widget.LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setTag(apps ? "workspaceApps" : "workspaceLayout");
        cell.setContentDescription(description);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setOnClickListener(click);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        cell.setLayoutParams(lp);
        cell.setPadding(0, Math.round(28 * density), 0, 0);

        android.widget.ImageView glyph = new android.widget.ImageView(this);
        glyph.setTag("workspaceGlyph");
        int size = Math.round(32 * density);
        android.widget.LinearLayout.LayoutParams glyphLp =
                new android.widget.LinearLayout.LayoutParams(size, size);
        glyph.setLayoutParams(glyphLp);
        glyph.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        cell.addView(glyph);

        android.widget.TextView caption = new android.widget.TextView(this);
        caption.setText(label);
        caption.setTag("workspaceLabel");
        caption.setGravity(android.view.Gravity.CENTER);
        caption.setTextSize(10.5f);
        caption.setMaxLines(1);
        caption.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        caption.setLetterSpacing(0.11f);
        android.widget.LinearLayout.LayoutParams captionLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        captionLp.topMargin = Math.round(6 * density);
        caption.setLayoutParams(captionLp);
        cell.addView(caption);

        applyWorkspaceActionGlyph(cell);
        return cell;
    }

    private void applyWorkspaceActionGlyph(View cell) {
        if (cell == null) return;
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(32 * density);
        int color = dockLabelColor();
        android.widget.ImageView glyph = cell.findViewWithTag("workspaceGlyph");
        if (glyph != null) {
            glyph.setImageDrawable("workspaceApps".equals(cell.getTag())
                    ? dockGlyphAppTiles(size, color)
                    : dockGlyphMosaic(size, color));
        }
        android.widget.TextView caption = cell.findViewWithTag("workspaceLabel");
        if (caption != null) caption.setTextColor(color);
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        mask.setColor(0xFFFFFFFF);
        cell.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(withAlpha(color, 0x33)),
                null, mask));
    }

    private View makeQuickTextCard(float density, int widthDp, String title, String value,
            String description, View.OnClickListener click) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int padH = Math.round(16 * density);
        card.setPadding(padH, Math.round(10 * density), padH, Math.round(9 * density));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                Math.round(widthDp * density), Math.round(124 * density));
        lp.rightMargin = Math.round(10 * density);
        card.setLayoutParams(lp);
        card.setMinimumHeight(Math.round(112 * density));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(description);
        card.setOnClickListener(click);
        card.setBackground(makeFrostStateDrawable(false, density));
        card.setElevation(3f * density);
        quickCardViews.add(card);

        android.widget.TextView heading = new android.widget.TextView(this);
        heading.setTag("frostSecondary");
        heading.setText(title);
        heading.setTextSize(10.5f);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        heading.setLetterSpacing(0.11f);
        card.addView(heading);

        android.widget.TextView body = new android.widget.TextView(this);
        body.setTag("quickValue");
        body.setText(value);
        body.setTextSize(18f);
        body.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        body.setMaxLines(1);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.LinearLayout.LayoutParams bodyLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Math.round(4 * density);
        body.setLayoutParams(bodyLp);
        card.addView(body);
        return card;
    }

    /**
     * MEDIA rail card.
     *
     * This is the one rail tile that is NOT makeQuickVisualCard, and it stays
     * that way deliberately: it is the only card whose useful action is a
     * command rather than a reading, and folding it into the generic card
     * would cost the three transport buttons to gain a shape it does not want.
     * MediaCenter, the OEM reference, puts transport on its rail surface too.
     *
     * What it borrows from the generic card is the part that was missing: the
     * body runs the descriptor's allow-listed action, so a tap anywhere that
     * is not a control opens the MEDIA popup like every other card.
     */
    private View makeQuickMediaCard(float density, BottomCardDescriptor descriptor) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // Only the frost rim's own inset, so the artwork can bleed to the
        // card's top, left and bottom edges without covering the rim itself.
        int rim = Math.max(1, Math.round(density));
        card.setPadding(rim, rim, rim, rim);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                Math.round(MEDIA_CARD_W_DP * density), Math.round(MEDIA_CARD_H_DP * density));
        lp.rightMargin = Math.round(10 * density);
        card.setLayoutParams(lp);
        card.setBackground(makeFrostStateDrawable(false, density));
        card.setElevation(3f * density);
        card.setContentDescription(descriptor != null
                ? bottomCardAccessibilityDescription(descriptor)
                : "Media quick controls");
        // The body opens the PLAYER — that is what a now-playing card is for,
        // projection sources included. It is clickable unconditionally, so a
        // card with nothing to open still consumes its own touch instead of
        // letting it fall through to the 3D canvas underneath.
        card.setClickable(true);
        card.setFocusable(true);
        final String bodyCommand = descriptor != null ? descriptor.action : "";
        card.setOnClickListener(v -> {
            if (quickMediaCanLaunch && quickMediaPackage != null && !quickMediaPackage.isEmpty()) {
                launchAppForPackage(quickMediaPackage,
                        quickMediaTitle != null ? quickMediaTitle.getText().toString() : "Media");
            } else if (!bodyCommand.isEmpty()) {
                // Nothing to open: fall back to the card's own command so the
                // tap answers rather than doing nothing.
                callViewerDock(bodyCommand);
            }
        });
        if (descriptor != null && !descriptor.longAction.isEmpty()) {
            final String longCommand = descriptor.longAction;
            card.setLongClickable(true);
            card.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                callViewerDock(longCommand);
                return true;
            });
        }
        quickCardViews.add(card);

        quickMediaArt = new android.widget.ImageView(this);
        quickMediaArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        quickMediaArt.setBackground(makeDockPlateDrawable(false, density));
        quickMediaArt.setPadding(Math.round(MEDIA_ART_GLYPH_PAD_DP * density),
                Math.round(MEDIA_ART_GLYPH_PAD_DP * density),
                Math.round(MEDIA_ART_GLYPH_PAD_DP * density),
                Math.round(MEDIA_ART_GLYPH_PAD_DP * density));
        quickMediaArt.setImageDrawable(mediaArtPlaceholder(density));
        quickMediaArt.setClipToOutline(true);
        // Rounded into the card's own corners on the left, hard-cut on the
        // right. An Outline can only clip a UNIFORM round rect on this
        // platform (Outline.canClip is false for a path before API 30), so the
        // outline is pushed one radius past the right edge instead: the
        // rounding happens outside the view, and the view's bounds do the cut.
        quickMediaArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                float d = getResources().getDisplayMetrics().density;
                float r = (MEDIA_CARD_RADIUS_DP * d) - Math.max(1f, d);
                outline.setRoundRect(0, 0, view.getWidth() + Math.round(r),
                        view.getHeight(), r);
            }
        });
        int artSide = Math.round(MEDIA_CARD_H_DP * density) - 2 * rim;
        android.widget.LinearLayout.LayoutParams artLp = new android.widget.LinearLayout.LayoutParams(
                artSide, android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        quickMediaArt.setLayoutParams(artLp);
        card.addView(quickMediaArt);

        android.widget.LinearLayout copy = new android.widget.LinearLayout(this);
        copy.setOrientation(android.widget.LinearLayout.VERTICAL);
        copy.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // 4dp, not 8: measured on the car the column needs ~116dp (30dp source
        // chip row, title, artist, 48dp play) and 8dp padding left 108, so the
        // play circle drew 38dp tall -- clipped top and bottom.
        copy.setPadding(Math.round(13 * density), Math.round(4 * density),
                Math.round(11 * density), Math.round(4 * density));
        copy.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        // Heading row: the card's own name on the left, and on the right the
        // app that published the track — its real icon and label, both from the
        // now-playing payload rather than guessed from the package name.
        android.widget.LinearLayout headRow = new android.widget.LinearLayout(this);
        headRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headRow.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.TextView heading = new android.widget.TextView(this);
        heading.setTag("frostSecondary");
        heading.setText("MEDIA");
        heading.setTextSize(10.5f);
        heading.setLetterSpacing(0.11f);
        heading.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(heading);

        quickMediaAppRow = makeQuickMediaAppChip(density);
        headRow.addView(quickMediaAppRow);
        copy.addView(headRow);
        quickMediaTitle = new android.widget.TextView(this);
        quickMediaTitle.setTag("quickValue");
        quickMediaTitle.setText("Nothing playing");
        quickMediaTitle.setTextSize(15f);
        quickMediaTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        quickMediaTitle.setMaxLines(1);
        quickMediaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(quickMediaTitle);
        quickMediaArtist = new android.widget.TextView(this);
        quickMediaArtist.setTag("frostSecondary");
        quickMediaArtist.setText("Choose a media app");
        quickMediaArtist.setTextSize(9.5f);
        quickMediaArtist.setMaxLines(1);
        quickMediaArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(quickMediaArtist);

        android.widget.LinearLayout controls = new android.widget.LinearLayout(this);
        controls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        // WRAP_CONTENT, not a fixed height. This row was pinned to 36dp from
        // when the buttons were 34dp rectangles; the 48dp play circle overflowed
        // it and the row clipped the top and bottom off every button.
        android.widget.LinearLayout.LayoutParams controlsLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        controlsLp.topMargin = 0;
        controls.setLayoutParams(controlsLp);
        controls.addView(makeQuickMediaButton(density, false, "prev", "Previous track",
                v -> mediaNowPlaying.prev()));
        quickMediaPlayPause = makeQuickMediaButton(density, true, "play", "Play",
                v -> mediaNowPlaying.playPause());
        controls.addView(quickMediaPlayPause);
        controls.addView(makeQuickMediaButton(density, false, "next", "Next track",
                v -> mediaNowPlaying.next()));
        copy.addView(controls);
        // The right half stacks: ambient bars behind, copy in front. A
        // LinearLayout cannot overlap its children, so this needs a frame.
        android.widget.FrameLayout right = new android.widget.FrameLayout(this);
        right.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        quickMediaBars = new QuickMediaBarsView(this);
        right.addView(quickMediaBars, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        right.addView(copy);
        card.addView(right);
        return card;
    }

    /**
     * The source app's icon, on a card's top right.
     *
     * One chip, two cards: MEDIA marks what is playing, NAVIGATION marks what
     * is guiding. They used to disagree — NAVIGATION drew its own badge onto
     * the QuickCardGraphicView canvas, icon only and at its own size, so the
     * same rail showed two different Android Auto marks side by side. The
     * canvas badge is gone; both now build this and resolve through
     * {@link #resolveSourceChipIcon}, which was already keyed on package +
     * label and needed nothing media-specific. It used to also carry a name
     * TextView next to the icon; both cards now show the icon alone.
     */
    private static final class SourceChip {
        final android.widget.LinearLayout row;
        final android.widget.ImageView icon;

        SourceChip(android.widget.LinearLayout row, android.widget.ImageView icon) {
            this.row = row;
            this.icon = icon;
        }
    }

    private SourceChip makeSourceChip(float density) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);
        row.setVisibility(View.GONE);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        android.widget.LinearLayout.LayoutParams iconLp =
                new android.widget.LinearLayout.LayoutParams(
                        Math.round(SOURCE_CHIP_ICON_DP * density), Math.round(SOURCE_CHIP_ICON_DP * density));
        icon.setLayoutParams(iconLp);
        row.addView(icon);
        return new SourceChip(row, icon);
    }

    /**
     * Hidden outright when there is no icon, because an empty pill on an idle
     * card reads as a control that has stopped working.
     */
    private void applySourceChip(SourceChip chip, Drawable customIcon, Bitmap decoded) {
        if (chip == null) return;
        boolean show = customIcon != null || decoded != null;
        chip.row.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        if (customIcon != null) {
            chip.icon.setImageDrawable(customIcon);
        } else {
            chip.icon.setImageBitmap(decoded);
        }
    }

    private View makeQuickMediaAppChip(float density) {
        SourceChip chip = makeSourceChip(density);
        quickMediaAppIcon = chip.icon;
        quickMediaChip = chip;
        return chip.row;
    }

    private void applyQuickMediaAppChip(String iconDataUrl, String label) {
        if (quickMediaAppRow == null) return;
        String pkg = quickMediaPackage != null ? quickMediaPackage : "";
        Drawable customIcon = resolveSourceChipIcon(pkg, label);
        applySourceChip(quickMediaChip, customIcon,
                customIcon == null ? decodeDataUrlBitmap(iconDataUrl) : null);
    }

    /**
     * Stamp the NAVIGATION card's chip from its descriptor.
     *
     * There is no icon payload here the way MediaNowPlaying supplies one, so
     * the chip is whatever the shared resolver makes of the package — which
     * for a projection package is our own mark, the point of the exercise.
     */
    private void applyNavigationSourceChip(BottomCardDescriptor card) {
        SourceChip chip = quickCardSourceChips.get(card.id);
        if (chip == null) return;
        String pkg = card.appPackage == null ? "" : card.appPackage.trim();
        if (pkg.isEmpty()) {
            applySourceChip(chip, null, null);
            return;
        }
        applySourceChip(chip, resolveSourceChipIcon(pkg, null), null);
    }

    /**
     * Icon for a card's source chip: dock substitute first, then our own
     * AA/CarPlay assets (the system packages ship a generic glyph), then the
     * caller's own payload icon if it has one (MEDIA does; NAVIGATION does not).
     */
    private Drawable resolveSourceChipIcon(String pkg, String label) {
        if (pkg != null && !pkg.isEmpty() && dockAppOverrides != null) {
            String slug = dockAppOverrides.icon(pkg);
            if (slug != null) {
                Drawable sub = DockAppOverrides.drawableFor(this, slug, dockAppOverrides.color(pkg));
                if (sub != null) return sub;
            }
        }
        if (isAndroidAutoMediaSource(pkg, label)) {
            if (projectionPresence != null) {
                Drawable branded = projectionPresence.iconFor(ProjectionPresence.Kind.ANDROID_AUTO);
                if (branded != null) return branded;
            }
            try { return getDrawable(R.drawable.ic_android_auto_default); } catch (Exception ignored) {}
        }
        if (isCarPlayMediaSource(pkg, label)) {
            if (projectionPresence != null) {
                Drawable branded = projectionPresence.iconFor(ProjectionPresence.Kind.CARPLAY);
                if (branded != null) return branded;
            }
            try { return getDrawable(R.drawable.ic_carplay_default); } catch (Exception ignored) {}
        }
        return null;
    }

    /** MediaCenter owns both AA and USB — only the AA label is projection. */
    private static boolean isAndroidAutoMediaSource(String pkg, String label) {
        if (label != null && label.equalsIgnoreCase("ANDROID AUTO")) return true;
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(java.util.Locale.US);
        return p.contains("androidauto") || p.contains("projection.gearhead");
    }

    private static boolean isCarPlayMediaSource(String pkg, String label) {
        if (label != null) {
            String l = label.trim();
            if (l.equalsIgnoreCase("CARPLAY") || l.equalsIgnoreCase("CarPlay")) return true;
        }
        if (pkg == null || pkg.isEmpty()) return false;
        return pkg.toLowerCase(java.util.Locale.US).contains("carplay");
    }

    /** Shared decode for the base64 payloads the media bridge sends. */
    private static Bitmap decodeDataUrlBitmap(String dataUrl) {
        if (dataUrl == null) return null;
        int comma = dataUrl.indexOf(',');
        if (comma < 0 || comma + 1 >= dataUrl.length()) return null;
        try {
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One transport button.
     *
     * These used to be TextViews carrying "‹", "▶", "›" and "Ⅱ" — a typographic
     * quote, a geometric-shape arrow and a Roman numeral, three different fonts
     * at three different optical weights, in rectangles. The play button even
     * changed width when it flipped to pause. They are drawn now, on the same
     * 24x24 grid as the widget's icons, so the set matches itself and matches
     * the other two surfaces.
     */
    private android.widget.ImageView makeQuickMediaButton(float density, boolean primary,
            String glyph, String description, View.OnClickListener click) {
        int size = Math.round((primary ? MEDIA_BTN_PRIMARY_DP : MEDIA_BTN_DP) * density);
        android.widget.ImageView button = new android.widget.ImageView(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(size, size);
        lp.rightMargin = Math.round(8 * density);
        button.setLayoutParams(lp);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setTag(primary ? "mediaBtnPrimary" : "mediaBtn");
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImageDrawable(mediaGlyph(density, primary, glyph));
        button.setBackground(makeMediaButtonBackground(density, primary, size));
        button.setOnClickListener(click);
        button.setEnabled(quickMediaAvailable);
        button.setAlpha(quickMediaAvailable ? 1f : 0.35f);
        quickMediaButtons.add(button);
        return button;
    }

    /**
     * A circle, outlined for prev/next and accent-filled for play/pause.
     *
     * The pressed state is not decoration: a button that does not answer a
     * touch on a head unit reads as a missed tap.
     */
    private android.graphics.drawable.Drawable makeMediaButtonBackground(
            float density, boolean primary, int size) {
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed },
                mediaButtonFace(density, primary, true));
        states.addState(new int[0], mediaButtonFace(density, primary, false));
        return states;
    }

    private android.graphics.drawable.Drawable mediaButtonFace(
            float density, boolean primary, boolean pressed) {
        android.graphics.drawable.GradientDrawable face =
                new android.graphics.drawable.GradientDrawable();
        face.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (primary) {
            face.setColor(withAlpha(dockAccentColor, pressed ? 0xC0 : 0xFF));
            face.setStroke(Math.max(1, Math.round(density)), dockAccentColor);
        } else {
            face.setColor(dockUiLight
                    ? (pressed ? 0x2418222C : 0x0F18222C)
                    : (pressed ? 0x2EFFFFFF : 0x14FFFFFF));
            face.setStroke(Math.max(1, Math.round(density)),
                    dockUiLight ? 0x338A99A8 : 0x2EFFFFFF);
        }
        return face;
    }

    /**
     * The mark shown when the source published no artwork.
     *
     * A disc, not a play triangle: the system's ic_media_play filled the whole
     * art panel and read as an enormous play button sitting next to the real
     * one. Same shape the widget and the popup draw.
     */
    private android.graphics.drawable.Drawable mediaArtPlaceholder(float density) {
        int box = Math.max(16, Math.round(48 * density));
        Bitmap bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, box * 0.062f));
        p.setColor(withAlpha(dockLabelColor(), 0x66));
        float mid = box / 2f;
        c.drawCircle(mid, mid, box * 0.375f, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        c.drawCircle(mid, mid, box * 0.10f, p);
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    /**
     * Transport glyphs, on the same 24x24 grid as the widget's SVG icons.
     *
     * Rectangles and triangles only, so there is nothing here a font could
     * substitute differently from one glyph to the next.
     */
    private android.graphics.drawable.Drawable mediaGlyph(
            float density, boolean primary, String kind) {
        int box = Math.round((primary ? MEDIA_BTN_PRIMARY_DP : MEDIA_BTN_DP) * 0.46f * density);
        box = Math.max(8, box);
        Bitmap bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setStyle(android.graphics.Paint.Style.FILL);
        // Dark on the accent fill, the card's own foreground on an outlined ring.
        p.setColor(primary ? 0xFF06131A : dockLabelColor());
        final float u = box / 24f;
        if ("prev".equals(kind)) {
            c.drawRect(6 * u, 6 * u, 8 * u, 18 * u, p);
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(18 * u, 6 * u);
            tri.lineTo(18 * u, 18 * u);
            tri.lineTo(9.5f * u, 12 * u);
            tri.close();
            c.drawPath(tri, p);
        } else if ("next".equals(kind)) {
            c.drawRect(16 * u, 6 * u, 18 * u, 18 * u, p);
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(6 * u, 6 * u);
            tri.lineTo(6 * u, 18 * u);
            tri.lineTo(14.5f * u, 12 * u);
            tri.close();
            c.drawPath(tri, p);
        } else if ("pause".equals(kind)) {
            c.drawRect(6 * u, 5 * u, 10 * u, 19 * u, p);
            c.drawRect(14 * u, 5 * u, 18 * u, 19 * u, p);
        } else {
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(8 * u, 5 * u);
            tri.lineTo(8 * u, 19 * u);
            tri.lineTo(19 * u, 12 * u);
            tri.close();
            c.drawPath(tri, p);
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private void updateQuickMediaCard(JSONObject payload) {
        if (payload == null) return;
        lastMediaPayload = payload;
        quickMediaPackage = payload.optString("packageName", "");
        quickMediaPlaying = payload.optBoolean("playing", false);
        quickMediaCanLaunch = payload.optBoolean("canLaunch", false);
        if (quickMediaBars != null) {
            quickMediaBars.setPlaying(quickMediaPlaying);
        }
        syncMediaVisualizerWanted(mediaAudioWanted());
        boolean hasTrack = payload.optBoolean("hasTrack", false);
        quickMediaAvailable = hasTrack && mediaNowPlaying != null;
        for (android.widget.ImageView button : quickMediaButtons) {
            button.setEnabled(quickMediaAvailable);
            button.setAlpha(quickMediaAvailable ? 1f : 0.35f);
        }
        String title = payload.optString("title", "");
        String artist = payload.optString("artist", "");
        String app = payload.optString("appLabel", "");
        String display = hasTrack && !title.isEmpty() ? title
                : (!app.isEmpty() ? app : "Nothing playing");
        if (quickMediaTitle != null) quickMediaTitle.setText(display);
        if (quickMediaArtist != null) {
            String album = payload.optString("album", "");
            quickMediaArtist.setText(!artist.isEmpty() ? artist
                    : (hasTrack ? album : "Choose a media app"));
            quickMediaArtist.setVisibility(
                    quickMediaArtist.getText().length() == 0 ? View.INVISIBLE : View.VISIBLE);
        }
        if (quickMediaArt != null) {
            Bitmap art = decodeDataUrlBitmap(payload.optString("artDataUrl", ""));
            quickMediaHasArt = art != null;
            if (quickMediaHasArt) {
                quickMediaArt.setPadding(0, 0, 0, 0);
                quickMediaArt.setImageBitmap(art);
            } else {
                float d = getResources().getDisplayMetrics().density;
                int pad = Math.round(MEDIA_ART_GLYPH_PAD_DP * d);
                quickMediaArt.setPadding(pad, pad, pad, pad);
                quickMediaArt.setImageDrawable(mediaArtPlaceholder(d));
            }
        }
        if (quickMediaPlayPause != null) {
            float d = getResources().getDisplayMetrics().density;
            quickMediaPlayPause.setImageDrawable(
                    mediaGlyph(d, true, quickMediaPlaying ? "pause" : "play"));
            quickMediaPlayPause.setContentDescription(quickMediaPlaying ? "Pause" : "Play");
        }
        applyQuickMediaAppChip(payload.optString("appIcon", ""), app);
    }

    /**
     * The mode wash a card view is currently wearing.
     *
     * The theme pass only has the view, not the descriptor, and it rebuilds every
     * background — so without this it repaints the driving cards flat on the next
     * payload and the wash silently disappears.
     */
    private int washForCardView(View card) {
        Object tag = card.getTag();
        if (!(tag instanceof String)) return 0;
        String id = (String) tag;
        if (!id.startsWith("bottomCard:")) return 0;
        Integer wash = lastDrivingWash.get(id.substring("bottomCard:".length()));
        return wash == null ? 0 : wash;
    }

    private void refreshQuickCardsTheme() {
        float density = getResources().getDisplayMetrics().density;
        for (View card : quickCardViews) {
            if (card == null) continue;
            card.setBackground(makeFrostStateDrawable(card.isSelected(), density,
                    washForCardView(card)));
            tintFrostText(card);
        }
        // Both the ring colours and the glyph tint are baked in at build time,
        // so a theme change has to redraw them or the transport keeps the
        // previous theme's contrast. Same trap as the driving wash.
        for (android.widget.ImageView button : quickMediaButtons) {
            if (button == null) continue;
            boolean primary = "mediaBtnPrimary".equals(button.getTag());
            int size = Math.round((primary ? MEDIA_BTN_PRIMARY_DP : MEDIA_BTN_DP) * density);
            button.setBackground(makeMediaButtonBackground(density, primary, size));
            if (!primary) {
                button.setImageDrawable(mediaGlyph(density, false,
                        "Previous track".contentEquals(
                                button.getContentDescription() == null ? "" : button.getContentDescription())
                                ? "prev" : "next"));
            }
        }
        if (quickMediaPlayPause != null) {
            quickMediaPlayPause.setImageDrawable(
                    mediaGlyph(density, true, quickMediaPlaying ? "pause" : "play"));
        }
        if (quickMediaBars != null) quickMediaBars.retint();
        if (quickMediaArt != null) {
            quickMediaArt.setBackground(makeDockPlateDrawable(false, density));
            if (!quickMediaHasArt) {
                quickMediaArt.setImageDrawable(mediaArtPlaceholder(density));
            }
        }
        // Not on every call: this runs on every dock update, and the demo
        // telemetry updates about once a second, which closed a menu (the rail
        // ⋯ in edit mode, the driving cards' quick menus) before it was seen.
        String themeSig = dockUiLight + "|" + dockAccentColor;
        if (!themeSig.equals(quickMenuThemeSig)) {
            quickMenuThemeSig = themeSig;
            dismissQuickMenu();
        }
        for (QuickCardGraphicView graphic : quickCardGraphics.values()) {
            if (graphic != null) graphic.invalidate();
        }
        for (QuickClockCardView clock : quickClockCards.values()) {
            if (clock != null) clock.invalidate();
        }
    }

    private void applyQuickClockFace(String revision, int sequence, Bitmap bitmap) {
        if (bitmap == null) return;
        if (revision.equals(quickClockFaceRevision) && sequence < quickClockFaceSequence) return;
        quickClockFaceRevision = revision;
        quickClockFaceSequence = sequence;
        quickClockFaceBitmap = bitmap;
        for (QuickClockCardView clock : quickClockCards.values()) {
            if (clock != null) clock.setRenderedFace(bitmap);
        }
    }

    private void tintFrostText(View view) {
        Object tag = view.getTag();
        if ("workspaceApps".equals(tag) || "workspaceLayout".equals(tag)) {
            applyWorkspaceActionGlyph(view);
        } else if (tag instanceof String && ((String) tag).startsWith("workspaceLayoutCell:")) {
            styleWorkspaceLayoutCells();
        } else if (view instanceof android.widget.TextView) {
            boolean accent = "frostAccent".equals(tag) || "frostActionAccent".equals(tag);
            ((android.widget.TextView) view).setTextColor(accent ? dockAccentColor
                    : ("frostSecondary".equals(tag) ? dockLabelColorMuted() : dockLabelColor()));
            if ("frostAction".equals(tag)) {
                view.setBackground(makeDockPlateDrawable(false,
                        getResources().getDisplayMetrics().density));
            } else if ("frostActionAccent".equals(tag)) {
                view.setBackground(makeDockPlateDrawable(false,
                        getResources().getDisplayMetrics().density));
            }
        } else if ("quickAccentRail".equals(tag)) {
            view.setBackgroundColor(withAlpha(dockAccentColor, 0xD8));
        } else if ("workspaceSplit".equals(tag)) {
            view.setBackgroundColor(dockUiLight ? 0x30808080 : 0x32FFFFFF);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintFrostText(group.getChildAt(i));
        }
    }

    private void refreshDesktopIndicator() {
        int count = Math.max(1, desktopCount);
        int index = Math.max(0, Math.min(activeDesktopIndex, count - 1));
        String name = activeDesktopName == null || activeDesktopName.trim().isEmpty()
                ? "Desktop" : activeDesktopName.trim();
        if (activeDesktopTitle != null) activeDesktopTitle.setText(name);
        if (activeDesktopMeta != null) activeDesktopMeta.setText((index + 1) + " of " + count);
        if (activeDesktopTitle != null && activeDesktopTitle.getParent() instanceof View) {
            View card = (View) activeDesktopTitle.getParent();
            card.setBackground(makeFrostStateDrawable(true,
                    getResources().getDisplayMetrics().density));
            activeDesktopTitle.setTextColor(dockLabelColor());
            if (activeDesktopMeta != null) activeDesktopMeta.setTextColor(dockLabelColorMuted());
            card.setContentDescription(name + ", desktop " + (index + 1) + " of " + count
                    + ". Tap or long press to customize");
        }
    }

    private View buildLayoutContentRow(float density, int cellPx, int iconPx) {
        BounceHorizontalScrollView scroll = new BounceHorizontalScrollView(this, false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFadingEdgeLength(Math.round(48 * density));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setBackgroundColor(0x00000000);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        row.setPadding(Math.round(8 * density), Math.round(4 * density),
                Math.round(24 * density), Math.round(4 * density));
        row.setClipChildren(false);
        row.setBackgroundColor(0x00000000);

        android.widget.LinearLayout desktopControls = new android.widget.LinearLayout(this);
        desktopControls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        desktopControls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        View prev = makeWideDockChip(density, Math.round(64 * density), iconPx,
                "‹", v -> callViewerDock("previousDesktop"));
        prev.setContentDescription("Previous desktop");
        desktopControls.addView(prev);
        View current = makeDesktopStatusChip(density, Math.round(178 * density), iconPx);
        current.setOnClickListener(v -> callViewerDock("openDesktopStudio"));
        current.setOnLongClickListener(v -> {
            callViewerDock("openDesktopStudio");
            return true;
        });
        desktopControls.addView(current);
        View next = makeWideDockChip(density, Math.round(64 * density), iconPx,
                "›", v -> callViewerDock("nextDesktop"));
        next.setContentDescription("Next desktop");
        desktopControls.addView(next);
        View customize = makeWideDockChip(density, Math.round(118 * density), iconPx,
                "Customize", v -> callViewerDock("openDesktopStudio"));
        customize.setContentDescription("Customize desktop");
        desktopControls.addView(customize);
        row.addView(makeLayoutGroup(density, "DESKTOP", desktopControls));
        row.addView(makeLayoutDivider(density));

        android.widget.LinearLayout bottomControls = new android.widget.LinearLayout(this);
        bottomControls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bottomControls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layoutLauncherSurfaceChip = makeWideDockChip(density, Math.round(116 * density), iconPx,
                "Launcher", v -> chooseDockSurface(DOCK_SURFACE_LAUNCHER, true));
        layoutLauncherSurfaceChip.setContentDescription("Show app launcher. Long press to customize");
        layoutLauncherSurfaceChip.setOnLongClickListener(v -> {
            callViewerDock("openDesktopStudio");
            return true;
        });
        layoutCardsSurfaceChip = makeWideDockChip(density, Math.round(102 * density), iconPx,
                "Cards", v -> chooseDockSurface(DOCK_SURFACE_CARDS, true));
        layoutCardsSurfaceChip.setContentDescription("Show quick cards. Long press to customize");
        layoutCardsSurfaceChip.setOnLongClickListener(v -> {
            callViewerDock("openDesktopStudio");
            return true;
        });
        bottomControls.addView(layoutLauncherSurfaceChip);
        bottomControls.addView(layoutCardsSurfaceChip);
        row.addView(makeLayoutGroup(density, "BOTTOM", bottomControls));
        row.addView(makeLayoutDivider(density));

        android.widget.LinearLayout otherControls = new android.widget.LinearLayout(this);
        otherControls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        otherControls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layoutCenterFillChip = makeCenterFillChip(density, Math.round(190 * density), iconPx);
        otherControls.addView(layoutCenterFillChip);
        refreshCenterFillChip();

        layoutAddWidgetChip = makeWideDockChip(density, Math.round(126 * density), iconPx,
                "Add card", v -> callViewerDock("addWidget"));
        layoutAddWidgetChip.setContentDescription("Add card");
        otherControls.addView(layoutAddWidgetChip);

        layoutThemeChip = makeWideDockChip(density, Math.round(142 * density), iconPx,
                "Appearance", v -> callViewerDock("cycleWidgetTheme"));
        layoutThemeLabel = findDockChipLabel(layoutThemeChip);
        layoutThemeChip.setContentDescription("Cycle appearance theme");
        otherControls.addView(layoutThemeChip);
        row.addView(makeLayoutGroup(density, "OTHER", otherControls));

        scroll.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private View makeLayoutGroup(float density, String label, View controls) {
        android.widget.LinearLayout group = new android.widget.LinearLayout(this);
        group.setOrientation(android.widget.LinearLayout.VERTICAL);
        group.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams groupLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        group.setLayoutParams(groupLp);
        android.widget.TextView heading = new android.widget.TextView(this);
        heading.setText(label);
        heading.setTextSize(9f);
        heading.setTextColor(dockLabelColorMuted());
        heading.setLetterSpacing(0.12f);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        android.widget.LinearLayout.LayoutParams headingLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                Math.round(18 * density));
        headingLp.leftMargin = Math.round(8 * density);
        heading.setLayoutParams(headingLp);
        group.addView(heading);
        group.addView(controls, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f));
        return group;
    }

    private View makeLayoutDivider(float density) {
        View divider = new View(this);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                Math.max(1, Math.round(density)), Math.round(76 * density));
        lp.leftMargin = Math.round(12 * density);
        lp.rightMargin = Math.round(12 * density);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(dockUiLight ? 0x30808080 : 0x32FFFFFF);
        return divider;
    }

    private View makeDesktopStatusChip(float density, int widthPx, int iconPx) {
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                widthPx, Math.round(iconPx + 10 * density));
        lp.leftMargin = Math.round(4 * density);
        card.setLayoutParams(lp);
        card.setPadding(Math.round(12 * density), 0, Math.round(12 * density), 0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(makeFrostStateDrawable(true, density));
        activeDesktopTitle = new android.widget.TextView(this);
        activeDesktopTitle.setText(activeDesktopName);
        activeDesktopTitle.setTextColor(dockLabelColor());
        activeDesktopTitle.setTextSize(13f);
        activeDesktopTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        activeDesktopTitle.setMaxLines(1);
        activeDesktopTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        activeDesktopTitle.setGravity(android.view.Gravity.CENTER);
        card.addView(activeDesktopTitle);
        activeDesktopMeta = new android.widget.TextView(this);
        activeDesktopMeta.setText("1 of 1");
        activeDesktopMeta.setTextColor(dockLabelColorMuted());
        activeDesktopMeta.setTextSize(10f);
        activeDesktopMeta.setLetterSpacing(0.08f);
        activeDesktopMeta.setGravity(android.view.Gravity.CENTER);
        card.addView(activeDesktopMeta);
        refreshDesktopIndicator();
        return card;
    }

    /** Platform drawable, tinted for the dock. */
    private Drawable systemDockIcon(int resId) {
        return systemDockIcon(resId, dockGlyphColor(true));
    }

    private Drawable systemDockIcon(int resId, int color) {
        Drawable d = getResources().getDrawable(resId, getTheme());
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        return d;
    }

    /**
     * Cycles 3D car -> Bing wallpaper -> both; the gear configures the
     * wallpaper in either state that shows one.
     */
    private View makeCenterFillChip(float density, int widthPx, int iconPx) {
        FrameLayout cell = new FrameLayout(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(widthPx,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        lp.rightMargin = Math.round(8 * density);
        cell.setLayoutParams(lp);
        cell.setContentDescription("Center background");

        int plateH = Math.round(iconPx + 10 * density);
        View plate = new View(this);
        FrameLayout.LayoutParams plateLp = new FrameLayout.LayoutParams(
                widthPx - Math.round(4 * density), plateH);
        plateLp.gravity = android.view.Gravity.CENTER;
        plate.setLayoutParams(plateLp);
        plate.setTag("modePlate");
        plate.setBackground(makeDockPlateDrawable(false, density));
        cell.addView(plate);

        android.widget.LinearLayout inner = new android.widget.LinearLayout(this);
        inner.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        inner.setGravity(android.view.Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, plateH);
        innerLp.gravity = android.view.Gravity.CENTER;
        inner.setLayoutParams(innerLp);
        inner.setPadding(Math.round(10 * density), 0, Math.round(8 * density), 0);

        android.widget.LinearLayout toggle = new android.widget.LinearLayout(this);
        toggle.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        toggle.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams toggleLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        toggle.setLayoutParams(toggleLp);
        toggle.setClickable(true);
        toggle.setFocusable(true);
        toggle.setOnClickListener(v -> callViewerDock("toggleCenterFill"));

        int g = Math.round(iconPx * 0.44f);
        layoutCenterFillIcon = new android.widget.ImageView(this);
        android.widget.LinearLayout.LayoutParams ivLp =
                new android.widget.LinearLayout.LayoutParams(g, g);
        ivLp.rightMargin = Math.round(8 * density);
        layoutCenterFillIcon.setLayoutParams(ivLp);
        layoutCenterFillIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        toggle.addView(layoutCenterFillIcon);

        layoutCenterFillLabel = new android.widget.TextView(this);
        layoutCenterFillLabel.setTextColor(dockLabelColor());
        layoutCenterFillLabel.setTextSize(11f);
        layoutCenterFillLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        layoutCenterFillLabel.setLetterSpacing(0.04f);
        layoutCenterFillLabel.setMaxLines(1);
        layoutCenterFillLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toggle.addView(layoutCenterFillLabel);
        inner.addView(toggle);

        layoutCenterFillGear = new android.widget.ImageView(this);
        int gearPx = Math.round(iconPx * 0.38f);
        android.widget.LinearLayout.LayoutParams gearLp =
                new android.widget.LinearLayout.LayoutParams(gearPx, gearPx);
        gearLp.leftMargin = Math.round(4 * density);
        layoutCenterFillGear.setLayoutParams(gearLp);
        layoutCenterFillGear.setImageDrawable(systemDockIcon(android.R.drawable.ic_menu_preferences));
        layoutCenterFillGear.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        layoutCenterFillGear.setClickable(true);
        layoutCenterFillGear.setFocusable(true);
        layoutCenterFillGear.setContentDescription("Configure wallpaper");
        layoutCenterFillGear.setOnClickListener(v -> callViewerDock("configureWallpaper"));
        layoutCenterFillGear.setVisibility(View.GONE);
        inner.addView(layoutCenterFillGear);

        cell.addView(inner);
        return cell;
    }

    private void applyCenterFillIndicator(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("mode")) {
                String m = o.optString("mode", centerFillMode);
                centerFillMode = ("wallpaper".equals(m) || "mixed".equals(m)) ? m : "car";
            }
            if (layoutCenterFillChip != null && o.has("visible")) {
                layoutCenterFillChip.setVisibility(o.optBoolean("visible", true)
                        ? View.VISIBLE : View.GONE);
            }
            refreshCenterFillChip();
        } catch (Exception e) {
            Log.w(TAG, "updateCenterFill parse failed", e);
        }
    }

    private void refreshCenterFillChip() {
        if (layoutCenterFillChip == null) return;
        if (SHELL_APPS.equals(shellMode)) {
            layoutCenterFillChip.setVisibility(View.GONE);
            return;
        }
        if (layoutCenterFillChip.getVisibility() != View.VISIBLE) {
            layoutCenterFillChip.setVisibility(View.VISIBLE);
        }
        // Three states: 3D car only, wallpaper only, or both (car over
        // wallpaper). The gear opens the wallpaper picker, so it belongs to
        // every state that actually shows a wallpaper — mixed included.
        boolean mixed = "mixed".equals(centerFillMode);
        boolean wall = "wallpaper".equals(centerFillMode);
        boolean usesWallpaper = wall || mixed;
        if (layoutCenterFillIcon != null) {
            layoutCenterFillIcon.setImageDrawable(systemDockIcon(
                    usesWallpaper ? android.R.drawable.ic_menu_gallery
                            : android.R.drawable.ic_menu_directions));
        }
        if (layoutCenterFillLabel != null) {
            layoutCenterFillLabel.setText(mixed ? "Wallpaper + 3D"
                    : (wall ? "Wallpaper" : "3D Car"));
            styleDockLabel(layoutCenterFillLabel, false);
        }
        if (layoutCenterFillGear != null) {
            layoutCenterFillGear.setVisibility(usesWallpaper ? View.VISIBLE : View.GONE);
        }
        setModeCellSelected(layoutCenterFillChip, usesWallpaper);
    }

    private Drawable makeSimpleLayoutGlyph(int sizePx, int kind) {
        // kind 2 = app+car, kind 3 = app+app (two equal blocks) — centred.
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(dockGlyphColor());
        float s = sizePx;
        float padY = s * 0.18f;
        float h = s - 2f * padY;
        float gap = s * 0.07f;
        if (kind == 2) {
            float wApp = s * 0.28f;
            float wCar = s * 0.34f;
            float total = wApp + gap + wCar;
            float x = (s - total) * 0.5f;
            c.drawRoundRect(x, padY, x + wApp, padY + h, 3f, 3f, p);
            x += wApp + gap;
            p.setAlpha(140);
            c.drawRoundRect(x, padY + h * 0.12f, x + wCar, padY + h * 0.88f, 4f, 4f, p);
        } else {
            float w = (s - gap) * 0.38f;
            float total = w + gap + w;
            float x = (s - total) * 0.5f;
            c.drawRoundRect(x, padY, x + w, padY + h, 3f, 3f, p);
            c.drawRoundRect(x + w + gap, padY, x + w + gap + w, padY + h, 3f, 3f, p);
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private View makeWideDockChip(float density, int widthPx, int iconPx, String label,
            View.OnClickListener click) {
        FrameLayout cell = new FrameLayout(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(widthPx,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        lp.leftMargin = Math.round(6 * density);
        cell.setLayoutParams(lp);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setOnClickListener(click);
        cell.setContentDescription(label);

        View plate = new View(this);
        int plateH = Math.round(iconPx + 10 * density);
        FrameLayout.LayoutParams plateLp = new FrameLayout.LayoutParams(
                widthPx - Math.round(8 * density), plateH);
        plateLp.gravity = android.view.Gravity.CENTER;
        plate.setLayoutParams(plateLp);
        plate.setTag("modePlate");
        plate.setBackground(makeDockPlateDrawable(false, density));
        cell.addView(plate);

        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setTag("dockChipLabel");
        FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tvLp.gravity = android.view.Gravity.CENTER;
        tv.setLayoutParams(tvLp);
        tv.setText(label);
        tv.setTextColor(dockLabelColor());
        tv.setTextSize(12f);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        tv.setLetterSpacing(0.04f);
        cell.addView(tv);
        return cell;
    }

    /** Non-interactive hint chip (same plate language as Add Widget). */
    private View makeDockTipChip(float density, int widthPx, int iconPx, String label) {
        FrameLayout cell = new FrameLayout(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(widthPx,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
        lp.leftMargin = Math.round(6 * density);
        cell.setLayoutParams(lp);
        cell.setClickable(false);
        cell.setFocusable(false);
        cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cell.setContentDescription(label);

        View plate = new View(this);
        int plateH = Math.round(iconPx + 10 * density);
        FrameLayout.LayoutParams plateLp = new FrameLayout.LayoutParams(
                widthPx - Math.round(8 * density), plateH);
        plateLp.gravity = android.view.Gravity.CENTER;
        plate.setLayoutParams(plateLp);
        plate.setTag("modePlate");
        plate.setBackground(makeDockPlateDrawable(false, density));
        cell.addView(plate);

        android.widget.TextView tv = new android.widget.TextView(this);
        FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tvLp.gravity = android.view.Gravity.CENTER;
        tv.setLayoutParams(tvLp);
        tv.setTag("dockChipLabel");
        tv.setText(label);
        tv.setTextColor(dockLabelColorMuted());
        tv.setTextSize(10.5f);
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        tv.setLetterSpacing(0.03f);
        tv.setMaxLines(2);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(Math.round(10 * density), 0, Math.round(10 * density), 0);
        cell.addView(tv);
        return cell;
    }

    private void refreshLayoutChipSelection() {
        refreshCenterFillChip();
        refreshLayoutThemeChip();
        refreshDockSurfaceUi(false);
        refreshDesktopIndicator();
    }

    private void styleLayoutChipLabel(View chip) {
        if (chip == null) return;
        for (int i = 0; i < ((android.view.ViewGroup) chip).getChildCount(); i++) {
            View child = ((android.view.ViewGroup) chip).getChildAt(i);
            if (child instanceof android.widget.LinearLayout) {
                android.widget.LinearLayout inner = (android.widget.LinearLayout) child;
                for (int j = 0; j < inner.getChildCount(); j++) {
                    View rowChild = inner.getChildAt(j);
                    if (rowChild instanceof android.widget.TextView) {
                        styleDockLabel((android.widget.TextView) rowChild, false);
                    }
                }
            }
        }
    }

    private View buildConfigContentRow(float density, int cellPx, int iconPx) {
        BounceHorizontalScrollView scroll = new BounceHorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Math.round(48 * density));
        scroll.setClipChildren(true);
        scroll.setClipToPadding(true);
        scroll.setBackgroundColor(0x00000000);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(8 * density), 0, Math.round(24 * density), 0);
        row.setClipChildren(false);

        String[][] cmds = {
                { "camera", "Camera" },
                { "paint", "Color" },
                { "wheels", "Wheels" },
                { "speed", "Speed" },
                { "doors", "Doors" },
                { "lights", "Lights" },
                { "env", "Env" },
                { "model", "Model" },
                { "perf", "Perf" },
                { "fps", "FPS" },
                { "xray", "X-Ray" },
                { "time", "Time" },
                { "save", "Save" },
        };
        dockToolIcons.clear();
        // Text indicators (MODEL / PERF) need nearly the full plate; stroke
        // icons stay at ~55% like the old top toolbar.
        dockToolGlyphPx = Math.round(iconPx * 0.55f);
        for (String[] cmd : cmds) {
            boolean textIcon = "model".equals(cmd[0]) || "perf".equals(cmd[0])
                    || "fps".equals(cmd[0]);
            int glyphPx = textIcon ? Math.round(iconPx * 0.92f) : dockToolGlyphPx;
            row.addView(makeToolDockChip(density, cellPx, iconPx,
                    dockToolGlyph(cmd[0], glyphPx), cmd[0], cmd[1],
                    v -> callViewerDock(cmd[0])));
        }
        scroll.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    /** Stroke / text icons matching the old desktop top-toolbar glyphs. */
    private Drawable dockToolGlyph(String cmd, int sizePx) {
        return dockToolGlyph(cmd, sizePx, null);
    }

    private Drawable dockGlyphXray(int sizePx) {
        try {
            if (dockXrayGlyphSrc == null) {
                try (InputStream is = getAssets().open("www/assets/icons/xray-engine.png")) {
                    dockXrayGlyphSrc = BitmapFactory.decodeStream(is);
                }
            }
            if (dockXrayGlyphSrc == null) return null;
            Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(new PorterDuffColorFilter(dockGlyphColor(), PorterDuff.Mode.SRC_IN));
            c.drawBitmap(dockXrayGlyphSrc, null,
                    new Rect(0, 0, sizePx, sizePx), p);
            return new BitmapDrawable(getResources(), out);
        } catch (Exception e) {
            Log.w(TAG, "xray dock glyph load failed", e);
            return null;
        }
    }

    private Drawable dockToolGlyph(String cmd, int sizePx, String stateOverride) {
        if ("xray".equals(cmd)) {
            Drawable xray = dockGlyphXray(sizePx);
            if (xray != null) return xray;
        }
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(dockGlyphColor());
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.6f, sizePx * 0.075f));
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        float s = sizePx;
        float u = s / 24f;
        String cmdKey = cmd == null ? "" : cmd;
        switch (cmdKey) {
            case "camera":
                c.drawRoundRect(3 * u, 7 * u, 21 * u, 20 * u, 2 * u, 2 * u, p);
                c.drawCircle(12 * u, 13.5f * u, 3.2f * u, p);
                c.drawLine(8 * u, 7 * u, 9.5f * u, 4.5f * u, p);
                c.drawLine(9.5f * u, 4.5f * u, 14.5f * u, 4.5f * u, p);
                c.drawLine(14.5f * u, 4.5f * u, 16 * u, 7 * u, p);
                break;
            case "paint": {
                String hex = stateOverride != null ? stateOverride : dockPaintHex;
                int fill = parseCssColor(hex, 0xFFF4F5F7);
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(fill | 0xFF000000);
                c.drawCircle(12 * u, 12 * u, 7 * u, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setColor(0x59FFFFFF);
                c.drawCircle(12 * u, 12 * u, 7 * u, p);
                break;
            }
            case "wheels":
                // Full spoke wheel (old top-toolbar glyph).
                c.drawCircle(12 * u, 12 * u, 10 * u, p);
                c.drawCircle(12 * u, 12 * u, 3 * u, p);
                c.drawLine(12 * u, 2 * u, 12 * u, 9 * u, p);
                c.drawLine(12 * u, 15 * u, 12 * u, 22 * u, p);
                c.drawLine(2 * u, 12 * u, 9 * u, 12 * u, p);
                c.drawLine(15 * u, 12 * u, 22 * u, 12 * u, p);
                c.drawLine(4.93f * u, 4.93f * u, 9.88f * u, 9.88f * u, p);
                c.drawLine(14.12f * u, 14.12f * u, 19.07f * u, 19.07f * u, p);
                c.drawLine(4.93f * u, 19.07f * u, 9.88f * u, 14.12f * u, p);
                c.drawLine(14.12f * u, 9.88f * u, 19.07f * u, 4.93f * u, p);
                break;
            case "speed":
                c.drawArc(new android.graphics.RectF(3 * u, 5 * u, 21 * u, 23 * u),
                        200, 140, false, p);
                c.drawLine(12 * u, 14 * u, 16.5f * u, 9 * u, p);
                break;
            case "doors":
                // Key-fob / remote with signal wings (old doors glyph).
                c.drawRoundRect(6 * u, 2 * u, 18 * u, 22 * u, 3 * u, 3 * u, p);
                c.drawLine(6 * u, 8 * u, 2 * u, 6 * u, p);
                c.drawLine(18 * u, 8 * u, 22 * u, 6 * u, p);
                c.drawLine(6 * u, 16 * u, 2 * u, 14 * u, p);
                c.drawLine(18 * u, 16 * u, 22 * u, 14 * u, p);
                break;
            case "lights":
                c.drawLine(2 * u, 8 * u, 8 * u, 8 * u, p);
                c.drawLine(2 * u, 12 * u, 9 * u, 12 * u, p);
                c.drawLine(2 * u, 16 * u, 8 * u, 16 * u, p);
                c.drawLine(13 * u, 5 * u, 13 * u, 19 * u, p);
                android.graphics.Path beam = new android.graphics.Path();
                beam.moveTo(13 * u, 5 * u);
                beam.cubicTo(20 * u, 7 * u, 20 * u, 17 * u, 13 * u, 19 * u);
                c.drawPath(beam, p);
                break;
            case "env":
                c.drawCircle(12 * u, 12 * u, 9 * u, p);
                c.drawLine(3 * u, 12 * u, 21 * u, 12 * u, p);
                android.graphics.Path mer = new android.graphics.Path();
                mer.moveTo(12 * u, 3 * u);
                mer.cubicTo(16 * u, 7 * u, 16 * u, 17 * u, 12 * u, 21 * u);
                mer.moveTo(12 * u, 3 * u);
                mer.cubicTo(8 * u, 7 * u, 8 * u, 17 * u, 12 * u, 21 * u);
                c.drawPath(mer, p);
                break;
            case "model": {
                String label = stateOverride != null ? stateOverride : dockModelLabel;
                if (label == null || label.isEmpty()) label = "PHEV34";
                drawDockTextGlyph(c, p, s, label);
                break;
            }
            case "perf": {
                String label = stateOverride != null ? stateOverride : dockPerfLabel;
                if (label == null || label.isEmpty()) label = "OFF";
                drawDockTextGlyph(c, p, s, label);
                break;
            }
            case "fps": {
                drawDockTextGlyph(c, p, s, "FPS");
                break;
            }
            case "xray":
                // Bitmap glyph loads above; this stroke fallback only runs if the asset is missing.
                c.drawRoundRect(7 * u, 9.5f * u, 17 * u, 14.5f * u, 1 * u, 1 * u, p);
                c.drawCircle(5 * u, 17 * u, 2.2f * u, p);
                c.drawCircle(19 * u, 17 * u, 2.2f * u, p);
                c.drawLine(3 * u, 12 * u, 5 * u, 12 * u, p);
                c.drawLine(19 * u, 12 * u, 21 * u, 12 * u, p);
                break;
            case "time": {
                String mode = stateOverride != null ? stateOverride : dockTimeMode;
                if ("night".equals(mode)) {
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    android.graphics.Path moon = new android.graphics.Path();
                    moon.addCircle(12 * u, 12 * u, 8 * u, android.graphics.Path.Direction.CW);
                    android.graphics.Path cut = new android.graphics.Path();
                    cut.addCircle(16 * u, 9 * u, 6.5f * u, android.graphics.Path.Direction.CW);
                    moon.op(cut, android.graphics.Path.Op.DIFFERENCE);
                    c.drawPath(moon, p);
                } else if ("auto".equals(mode)) {
                    drawDockTextGlyph(c, p, s, "AUTO");
                } else {
                    // Day — sun.
                    c.drawCircle(12 * u, 12 * u, 4.2f * u, p);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4.0;
                        float x0 = 12 * u + (float) Math.cos(a) * 6.5f * u;
                        float y0 = 12 * u + (float) Math.sin(a) * 6.5f * u;
                        float x1 = 12 * u + (float) Math.cos(a) * 9.2f * u;
                        float y1 = 12 * u + (float) Math.sin(a) * 9.2f * u;
                        c.drawLine(x0, y0, x1, y1, p);
                    }
                }
                break;
            }
            case "save":
                android.graphics.Path disk = new android.graphics.Path();
                disk.moveTo(5 * u, 3 * u);
                disk.lineTo(16 * u, 3 * u);
                disk.lineTo(21 * u, 8 * u);
                disk.lineTo(21 * u, 21 * u);
                disk.lineTo(5 * u, 21 * u);
                disk.close();
                c.drawPath(disk, p);
                c.drawRect(8 * u, 13 * u, 16 * u, 21 * u, p);
                c.drawRect(8 * u, 3 * u, 15 * u, 8 * u, p);
                break;
            default:
                c.drawCircle(12 * u, 12 * u, 6 * u, p);
                break;
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private void drawDockTextGlyph(android.graphics.Canvas c, android.graphics.Paint p,
            float sizePx, String label) {
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(dockGlyphColor());
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        float textSize = sizePx * (label != null && label.length() > 4 ? 0.28f : 0.34f);
        p.setTextSize(textSize);
        p.setStrokeWidth(0f);
        android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = sizePx * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        c.drawText(label, sizePx * 0.5f, baseline, p);
    }

    private View makeToolDockChip(float density, int cellPx, int iconPx, Drawable glyph,
            String cmd, String label, View.OnClickListener click) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(cellPx,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(4 * density);
        col.setLayoutParams(lp);
        col.setClickable(true);
        col.setFocusable(true);
        col.setOnClickListener(click);

        FrameLayout iconWrap = new FrameLayout(this);
        int wrapH = Math.round(iconPx + 10 * density);
        iconWrap.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, wrapH));
        iconWrap.setBackgroundColor(0x00000000);

        View plate = new View(this);
        int plateSize = Math.round(iconPx + 4 * density);
        FrameLayout.LayoutParams plateLp = new FrameLayout.LayoutParams(plateSize, plateSize);
        plateLp.gravity = android.view.Gravity.CENTER;
        plate.setLayoutParams(plateLp);
        plate.setTag("modePlate");
        plate.setBackground(makeDockPlateDrawable(false, density));
        iconWrap.addView(plate);

        android.widget.ImageView iv = new android.widget.ImageView(this);
        boolean textIcon = "model".equals(cmd) || "perf".equals(cmd) || "fps".equals(cmd);
        int g = textIcon ? Math.round(iconPx * 0.92f) : Math.round(iconPx * 0.55f);
        FrameLayout.LayoutParams gLp = new FrameLayout.LayoutParams(g, g);
        gLp.gravity = android.view.Gravity.CENTER;
        iv.setLayoutParams(gLp);
        iv.setImageDrawable(glyph);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setTag(cmd);
        iconWrap.addView(iv);
        if (cmd != null) dockToolIcons.put(cmd, iv);
        col.addView(iconWrap);

        android.widget.TextView caption = new android.widget.TextView(this);
        caption.setTag("dockToolCaption");
        caption.setText(label.toUpperCase());
        caption.setTextColor(dockLabelColorMuted());
        caption.setTextSize(10f);
        caption.setGravity(android.view.Gravity.CENTER);
        caption.setLetterSpacing(0.06f);
        if (dockUiLight) caption.setShadowLayer(0f, 0f, 0f, 0);
        else caption.setShadowLayer(3f, 0f, 1f, 0x99000000);
        caption.setMaxLines(2);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(caption);
        return col;
    }

    private void applyDockIndicators(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("dockMode")) {
                String mode = o.optString("dockMode", dockSurfaceMode);
                if (DOCK_SURFACE_LAUNCHER.equals(mode) || DOCK_SURFACE_CARDS.equals(mode)) {
                    dockSurfaceMode = mode;
                    persistDockSurfaceMode();
                }
            }
            if (o.has("activeDesktopName")) {
                String name = o.optString("activeDesktopName", activeDesktopName);
                if (name != null && !name.trim().isEmpty()) activeDesktopName = name.trim();
            }
            if (o.has("desktopCount")) desktopCount = Math.max(1, o.optInt("desktopCount", 1));
            if (o.has("desktopStudioOpen")) {
                desktopStudioOpen = o.optBoolean("desktopStudioOpen", false);
                updateLauncherStripVisibility();
            }
            if (o.has("activeDesktopIndex")) {
                activeDesktopIndex = Math.max(0, o.optInt("activeDesktopIndex", 0));
            }
            if (o.has("studioScreen")) {
                String screen = o.optString("studioScreen", "");
                if (!"desktops".equals(screen) && !"layout".equals(screen) && !"appearance".equals(screen)) {
                    screen = "";
                }
                if (!screen.equals(studioScreen)) {
                    studioScreen = screen;
                    // A screen opened from anywhere (the header pill, a rail card)
                    // flips the card to its Layout face, one tap from the others.
                    if (!screen.isEmpty()) setWorkspaceLayoutMode(true);
                    styleWorkspaceLayoutCells();
                }
            }
            boolean accentChanged = false;
            if (o.has("accent")) {
                String accent = o.optString("accent", "");
                if (accent != null && accent.trim().startsWith("#")) {
                    int next = parseCssColor(accent.trim(), dockAccentColor);
                    accentChanged = next != dockAccentColor;
                    dockAccentColor = next;
                }
            }
            applyBottomCardsConfiguration(o);
            // After the cards: edit mode reads each card's editMenu to draw its ⋯.
            if (o.has("railEdit")) setRailEditMode(o.optBoolean("railEdit", false));
            if (o.has("railFocus")) {
                String focus = cleanBottomCardText(o.optString("railFocus", ""), 32);
                // After the configuration above, so a card added in the same
                // payload already has its view in the rebuilt row.
                if (!focus.equals(railFocusId)) {
                    railFocusId = focus;
                    if (!focus.isEmpty()) scrollRailToCard(focus);
                }
            }
            // Icons and graphics bake the accent in when they are built, and
            // refreshQuickCardsTheme only repaints backgrounds and text -- so an
            // accent change has to rebuild the rail or tinted children keep the
            // old colour. Rare enough to be free, and Sport now changes the
            // accent often enough that a stale tint is visible.
            if (accentChanged) rebuildQuickCardsRow();
            applyQuickCardIndicators(o);
            if (o.has("model")) {
                String v = o.optString("model", dockModelLabel);
                if (v != null && !v.isEmpty()) dockModelLabel = v;
            }
            if (o.has("perf")) {
                String v = o.optString("perf", dockPerfLabel);
                if (v != null && !v.isEmpty()) dockPerfLabel = v;
            }
            if (o.has("paint")) {
                String v = o.optString("paint", dockPaintHex);
                if (v != null && !v.isEmpty()) dockPaintHex = v;
            }
            if (o.has("time")) {
                String v = o.optString("time", dockTimeMode);
                if (v != null && !v.isEmpty()) dockTimeMode = v;
            }
            if (o.has("fps")) dockFpsOn = o.optBoolean("fps", false);
            if (o.has("xray")) dockXrayOn = o.optBoolean("xray", false);
            if (o.has("widgetThemeMode")) {
                String v = o.optString("widgetThemeMode", dockWidgetThemeMode);
                if (v != null && !v.isEmpty()) dockWidgetThemeMode = v;
            }
            if (o.has("widgetTheme")) {
                String v = o.optString("widgetTheme", dockWidgetThemeEffective);
                if (v != null && !v.isEmpty()) dockWidgetThemeEffective = v;
            }
            if (o.has("uiTheme")) {
                applyDockUiTheme("light".equals(o.optString("uiTheme", "dark")));
            }
            refreshDockToolGlyph("model", dockModelLabel);
            refreshDockToolGlyph("perf", dockPerfLabel);
            refreshDockToolGlyph("paint", dockPaintHex);
            refreshDockToolGlyph("time", dockTimeMode);
            refreshDockToolGlyph("fps", dockFpsOn ? "ON" : "OFF");
            refreshDockToolGlyph("xray", dockXrayOn ? "ON" : "OFF");
            refreshLayoutThemeChip();
            refreshDockSurfaceUi(false);
            refreshAllDockPlates();
        } catch (Exception e) {
            Log.w(TAG, "updateDockIndicators parse failed", e);
        }
    }

    /**
     * Applies the optional web-owned Cards surface contract. Absence intentionally restores the
     * native fallback cards so older web bundles and partial indicator payloads remain usable.
     */
    private void applyBottomCardsConfiguration(JSONObject root) {
        if (root == null || !root.has("bottomCards")) {
            if (!bottomCardsConfigured) return;
            bottomCardsConfigured = false;
            bottomCardLimit = 0;
            bottomCards.clear();
            rebuildQuickCardsRow();
            return;
        }

        JSONArray rawCards = root.optJSONArray("bottomCards");
        if (rawCards == null) {
            if (!bottomCardsConfigured && bottomCards.isEmpty()) return;
            bottomCardsConfigured = false;
            bottomCardLimit = 0;
            bottomCards.clear();
            rebuildQuickCardsRow();
            return;
        }

        List<BottomCardDescriptor> next = new ArrayList<>();
        int max = Math.min(rawCards.length(), 16);
        for (int i = 0; i < max; i++) {
            JSONObject raw = rawCards.optJSONObject(i);
            if (raw == null) continue;
            String id = cleanBottomCardText(raw.optString("id", ""), 32);
            String title = cleanBottomCardText(raw.optString("title", ""), 24);
            String value = cleanBottomCardText(raw.optString("value", ""), 48);
            String action = raw.optString("action", "").trim();
            String primary = cleanBottomCardText(raw.optString("primary", ""), 32);
            String secondary = cleanBottomCardText(raw.optString("secondary", ""), 48);
            String metricA = cleanBottomCardText(raw.optString("metricA", ""), 32);
            String metricB = cleanBottomCardText(raw.optString("metricB", ""), 32);
            int progress = Math.max(0, Math.min(100, raw.optInt("progress", 0)));
            boolean demo = raw.optBoolean("demo", false);
            if (id.isEmpty() || title.isEmpty() || !BOTTOM_CARD_ACTIONS.contains(action)) continue;
            // A tap on the graphic runs its own command, so it is allow-listed
            // exactly like the card's; an unknown one degrades to "no icon action"
            // rather than reaching the dock unchecked.
            String iconAction = raw.optString("iconAction", "").trim();
            if (!BOTTOM_CARD_ACTIONS.contains(iconAction)) iconAction = "";
            String longAction = raw.optString("longAction", "").trim();
            if (!BOTTOM_CARD_ACTIONS.contains(longAction)) longAction = "";
            String glyph = sanitizeGlyphPath(raw.optString("glyph", ""));
            String glyphText = cleanBottomCardText(raw.optString("glyphText", ""), 6);
            String clockFace = sanitizeClockOption(raw.optString("clockFace", "panorama"),
                    new String[] {"panorama", "meridian", "split", "date-spine"}, "panorama");
            String clockHourFormat = sanitizeClockOption(raw.optString("clockHourFormat", "system"),
                    new String[] {"system", "24", "12"}, "system");
            String dialMarks = sanitizeClockOption(raw.optString("dialMarks", "index"),
                    new String[] {"index", "plain"}, "index");
            String splitPlates = sanitizeClockOption(raw.optString("splitPlates", "frost"),
                    new String[] {"frost", "flat"}, "frost");
            String dateSpineFormat = sanitizeClockOption(raw.optString("dateSpineFormat", "month-name"),
                    new String[] {"month-name", "numeric"}, "month-name");
            String dateWording = sanitizeClockOption(raw.optString("dateWording", "short"),
                    new String[] {"short", "long"}, "short");
            if ("power".equals(id)) glyphText = sanitizePowerDirections(raw.optString("glyphText", ""));
            java.util.List<QuickMenuRow> menu = parseQuickMenu(raw.optJSONArray("menu"));
            String state = "tires".equals(id)
                    ? sanitizeTiresState(raw.optString("state",
                            raw.optString("tireState", "unavailable")))
                    : ("status".equals(id) ? sanitizeStatusState(raw.optString("state", "unavailable"))
                            : ("power".equals(id) ? sanitizePowerState(raw.optString("state", "unavailable"))
                            : ("navigation".equals(id) ? sanitizeNavigationState(raw.optString("state", "unavailable"))
                            : (DRIVING_CARD_IDS.contains(id)
                                    ? sanitizeDrivingState(raw.optString("state", "unknown")) : ""))));
            String[] wheelStates = ("tires".equals(id) || "status".equals(id))
                    ? sanitizeWheelStates(raw.optString("wheelStates",
                            raw.optString("tireWheelStates", "")))
                    : new String[] {"unavailable", "unavailable", "unavailable", "unavailable"};
            String[] openingStates = "status".equals(id)
                    ? sanitizeOpeningStates(raw.optString("openingStates", ""))
                    : new String[] {"unknown", "unknown", "unknown", "unknown", "unknown"};
            String powerVariant = "power".equals(id)
                    ? sanitizePowerVariant(raw.optString("powerVariant", "")) : "phev19";
            boolean socKnown = "power".equals(id) && raw.optBoolean("socKnown", false);
            double powerSoc = raw.optDouble("powerSoc", progress);
            if(Double.isNaN(powerSoc)||Double.isInfinite(powerSoc)||powerSoc<0||powerSoc>100){socKnown=false;powerSoc=0;}
            String[] seatBeltStates = "status".equals(id)
                    ? sanitizeSeatBeltStates(raw.optString("seatBeltStates", ""))
                    : new String[] {"unknown", "unknown", "unknown", "unknown", "unknown"};
            int sunroofLevel = "status".equals(id)
                    ? Math.max(0, Math.min(100, raw.optInt("sunroofLevel", 0))) : 0;
            int curtainLevel = "status".equals(id)
                    ? Math.max(0, Math.min(100, raw.optInt("curtainLevel", 0))) : 0;
            BottomCardDescriptor descriptor = new BottomCardDescriptor(id, title.toUpperCase(java.util.Locale.US), value,
                    action, primary, secondary, metricA, metricB, progress, state, powerVariant, socKnown, powerSoc, wheelStates,
                    openingStates, seatBeltStates, sunroofLevel, curtainLevel, demo,
                    iconAction, longAction, glyph, glyphText, clockFace, clockHourFormat,
                    dialMarks, splitPlates, dateSpineFormat, dateWording, menu);
            descriptor.tirePressures = cleanBottomCardText(raw.optString("tirePressures", ""), 48);
            descriptor.tireTemperatures = cleanBottomCardText(raw.optString("tireTemperatures", ""), 48);
            descriptor.tirePressureUnit = cleanBottomCardText(raw.optString("tirePressureUnit", ""), 6);
            descriptor.appPackage = cleanBottomCardText(raw.optString("appPackage", ""), 80);
            descriptor.navRemaining = cleanBottomCardText(raw.optString("navRemaining", ""), 16);
            descriptor.navDuration = cleanBottomCardText(raw.optString("navDuration", ""), 16);
            descriptor.navEta = cleanBottomCardText(raw.optString("navEta", ""), 8);
            descriptor.navIdleCity = cleanBottomCardText(raw.optString("navIdleCity", ""), 32);
            descriptor.navIdleAction = cleanBottomCardText(raw.optString("navIdleAction", ""), 48);
            if ("consumption".equals(id)) descriptor.energyBars = parseEnergyBars(raw.optString("energyBars", ""));
            descriptor.editMenu = parseEditMenu(raw.optJSONArray("editMenu"));
            next.add(descriptor);
        }

        int requested = root.has("bottomCardLimit")
                ? root.optInt("bottomCardLimit", next.size()) : next.size();
        int nextLimit = Math.max(0, Math.min(requested, next.size()));
        // Telemetry commonly changes only a card's value. Do not tear down the entire
        // horizontal rail (and reset its scroll position) for that case.
        if (bottomCardsConfigured && bottomCardLimit == nextLimit
                && sameBottomCardStructure(bottomCards, next)) {
            updateBottomCardValues(next);
            bottomCards.clear();
            bottomCards.addAll(next);
            return;
        }

        bottomCardsConfigured = true;
        bottomCardLimit = nextLimit;
        bottomCards.clear();
        bottomCards.addAll(next);
        rebuildQuickCardsRow();
    }

    private String cleanBottomCardText(String value, int maxLength) {
        String clean = cleanIndicator(value);
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
    }

    private String sanitizeClockOption(String value, String[] allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        for (String option : allowed) if (option.equals(normalized)) return normalized;
        return fallback;
    }

    /**
     * Glyph paths arrive from the web payload, so they are validated like any
     * other untrusted string: the generator emits only absolute M/L/C/Z with
     * spaces and numbers, and anything else is dropped rather than parsed.
     */
    private String sanitizeGlyphPath(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 4000) return "";
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = c == 'M' || c == 'L' || c == 'C' || c == 'Z' || c == ' '
                    || c == '.' || c == '-' || (c >= '0' && c <= '9');
            if (!ok) return "";
        }
        return trimmed;
    }

    /**
     * Quick-menu rows from the payload.
     *
     * A row's command is either one of the fixed allow-listed actions or a
     * driving write, which carries a value and so cannot be a fixed token. The
     * shape is checked here and the value itself is re-derived from the mode
     * tables on the web side — this end only proves it looks like a write, never
     * that it is a legal one.
     */
    private java.util.List<QuickMenuRow> parseQuickMenu(JSONArray raw) {
        if (raw == null || raw.length() == 0) return java.util.Collections.emptyList();
        java.util.List<QuickMenuRow> rows = new ArrayList<>();
        int max = Math.min(raw.length(), 12);
        for (int i = 0; i < max; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String label = cleanBottomCardText(item.optString("label", ""), 28);
            String command = item.optString("command", "").trim();
            if (label.isEmpty() || command.isEmpty()) continue;
            if (!BOTTOM_CARD_ACTIONS.contains(command) && !isDrivingSetCommand(command)) continue;
            rows.add(new QuickMenuRow(label, command, item.optBoolean("selected", false)));
        }
        return rows;
    }

    /**
     * Rows for a rail card's ⋯ in edit mode. Only two command shapes are
     * relayed: a destination (cardAction:...) and the clock face. The page
     * re-checks both; anything else is dropped here.
     */
    private java.util.List<QuickMenuRow> parseEditMenu(JSONArray raw) {
        if (raw == null || raw.length() == 0) return java.util.Collections.emptyList();
        java.util.List<QuickMenuRow> rows = new ArrayList<>();
        int max = Math.min(raw.length(), 10);
        for (int i = 0; i < max; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String label = cleanBottomCardText(item.optString("label", ""), 28);
            String command = item.optString("command", "").trim();
            if (label.isEmpty()) continue;
            if (!isCardActionCommand(command) && !"openClockSettings".equals(command)) continue;
            rows.add(new QuickMenuRow(label, command, item.optBoolean("selected", false)));
        }
        return rows;
    }

    /** cardAction:&lt;card&gt;:popup|new|desktop:&lt;desktopId&gt;; ids letters, digits, '_' and '-'. */
    private boolean isCardActionCommand(String command) {
        return command != null
                && command.matches("cardAction:[A-Za-z0-9_]{1,32}:(popup|new|desktop:[A-Za-z0-9_-]{1,64})");
    }

    /** `drivingSet:&lt;group&gt;:&lt;value&gt;`, letters/digits/underscore only. */
    private boolean isDrivingSetCommand(String command) {
        if (!command.startsWith(DRIVING_SET_PREFIX)) return false;
        String rest = command.substring(DRIVING_SET_PREFIX.length());
        int cut = rest.indexOf(':');
        if (cut <= 0 || cut >= rest.length() - 1) return false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            boolean ok = c == ':' || c == '_' || (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!ok) return false;
        }
        return true;
    }

    private static final String DRIVING_SET_PREFIX = "drivingSet:";

    /** Rail cards that draw a driving mode glyph and accept an icon quick action. */
    private static final java.util.Set<String> DRIVING_CARD_IDS =
            new java.util.HashSet<>(java.util.Arrays.asList("driveMode", "powerMode", "regen"));

    /**
     * Mode identity for the rail graphic. Mirrors CAR_DRIVE_MODE_CARD_STATES /
     * CAR_POWER_MODE_CARD_STATES in index.html; an unrecognised value draws the
     * neutral glyph rather than guessing at a mode.
     */
    private String sanitizeDrivingState(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        switch (normalized) {
            case "eco":
            case "normal":
            case "sport":
            case "snow":
            case "sand":
            case "mud":
            case "awd":
            case "hev":
            case "evp":
            case "ev":
            case "level1":
            case "level2":
            case "level3":
            case "onepedal":
                return normalized;
            default:
                return "unknown";
        }
    }

    private String sanitizeTiresState(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        switch (normalized) {
            case "live":
            case "demo":
            case "stale":
            case "warning":
            case "unavailable":
                return normalized;
            default:
                return "unavailable";
        }
    }

    private String sanitizeNavigationState(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US)
                .replace('-', '_');
        switch (normalized) {
            case "idle":
            case "demo":
            case "unavailable":
            case "turn_left":
            case "turn_right":
            case "straight":
            case "uturn":
            case "roundabout":
            case "fork":
            case "merge":
            case "exit":
            case "destination":
                return normalized;
            default:
                return "unavailable";
        }
    }

    private String sanitizePowerDirections(String value) {
        // Reject the whole token, including trailing content; never truncate it into valid data.
        return value != null && value.matches("(?:-1|0|1),(?:-1|0|1),[01]") ? value : "";
    }

    private String sanitizePowerVariant(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        if ("phev34".equals(normalized) || "phev19".equals(normalized)
                || "hev2".equals(normalized)) return normalized;
        // Invalid data must never invent a rear motor.
        return "phev19";
    }

    private String sanitizePowerState(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        switch (normalized) {
            case "live":
            case "partial":
            case "stale":
            case "unavailable":
            case "demo":
            case "ev":
            case "hybrid":
            case "ice":
            case "regen":
            case "charge":
            case "idle":
                return normalized;
            default:
                return "unavailable";
        }
    }

    private String[] sanitizeWheelStates(String value) {
        String[] sanitized = {"unavailable", "unavailable", "unavailable", "unavailable"};
        if (value == null || value.trim().isEmpty()) return sanitized;
        String[] raw = value.split(",", -1);
        for (int i = 0; i < sanitized.length && i < raw.length; i++) {
            String state = raw[i].trim().toLowerCase(java.util.Locale.US);
            if ("normal".equals(state) || "warning".equals(state)
                    || "unavailable".equals(state)) {
                sanitized[i] = state;
            }
        }
        return sanitized;
    }

    private String sanitizeStatusState(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        switch (normalized) {
            case "live": case "demo": case "partial": case "stale": case "unavailable":
                return normalized;
            default: return "unavailable";
        }
    }

    /** Defensive parser for the web's physically ordered FL/FR/RL/RR/tailgate payload. */
    private String[] sanitizeOpeningStates(String value) {
        String[] sanitized = {"unknown", "unknown", "unknown", "unknown", "unknown"};
        if (value == null || value.trim().isEmpty()) return sanitized;
        String[] raw = value.split(",", -1);
        for (int i = 0; i < sanitized.length && i < raw.length; i++) {
            String state = raw[i].trim().toLowerCase(java.util.Locale.US);
            if ("open".equals(state) || "closed".equals(state) || "unknown".equals(state)) {
                sanitized[i] = state;
            }
        }
        return sanitized;
    }

    private String[] sanitizeSeatBeltStates(String value) {
        String[] sanitized = {"unknown", "unknown", "unknown", "unknown", "unknown"};
        if (value == null || value.trim().isEmpty()) return sanitized;
        String[] raw = value.split(",", -1);
        for (int i = 0; i < sanitized.length && i < raw.length; i++) {
            String state = raw[i].trim().toLowerCase(java.util.Locale.US);
            if ("fastened".equals(state) || "unfastened".equals(state)
                    || "unknown".equals(state)) {
                sanitized[i] = state;
            }
        }
        return sanitized;
    }

    private boolean sameBottomCardStructure(List<BottomCardDescriptor> a,
            List<BottomCardDescriptor> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            BottomCardDescriptor left = a.get(i);
            BottomCardDescriptor right = b.get(i);
            if (!left.id.equals(right.id) || !left.title.equals(right.title)
                    || !left.action.equals(right.action)
                    || ("clock".equals(left.id) && (!left.clockFace.equals(right.clockFace)
                    || !left.clockHourFormat.equals(right.clockHourFormat)
                    || !left.dialMarks.equals(right.dialMarks)
                    || !left.splitPlates.equals(right.splitPlates)
                    || !left.dateSpineFormat.equals(right.dateSpineFormat)
                    || !left.dateWording.equals(right.dateWording)))) {
                return false;
            }
        }
        return true;
    }

    private void updateBottomCardValues(List<BottomCardDescriptor> cards) {
        for (BottomCardDescriptor card : cards) {
            android.widget.TextView value = quickCardValues.get(card.id);
            String primary = quickVisualPrimary(card);
            if (value != null && !primary.equals(value.getText().toString())) {
                value.setText(primary);
            }
            android.widget.TextView detail = quickCardDetails.get(card.id);
            String detailText = quickVisualDetail(card);
            if (detail != null && !detailText.equals(detail.getText().toString())) {
                detail.setText(detailText);
            }
            android.widget.TextView demoBadge = quickCardDemoBadges.get(card.id);
            if (demoBadge != null) demoBadge.setVisibility(card.demo ? View.VISIBLE : View.GONE);
            QuickCardGraphicView graphic = quickCardGraphics.get(card.id);
            if (graphic != null) graphic.setDescriptor(card);
            applyNavigationSourceChip(card);
            QuickClockCardView clock = quickClockCards.get(card.id);
            if (clock != null) clock.setDescriptor(card);
            // The rail is patched in place rather than rebuilt, so a mode change
            // has to repaint the card's own background too.
            View host = quickCardHosts.get(card.id);
            int wash = drivingWashColor(card.state);
            if (host != null && wash != lastDrivingWash.getOrDefault(card.id, -1)) {
                lastDrivingWash.put(card.id, wash);
                float density = getResources().getDisplayMetrics().density;
                host.setBackground(makeFrostStateDrawable(false, density, wash));
            }
            updateBottomCardAccessibility(value != null ? value : (clock != null ? clock : graphic), card);
        }
    }

    private void updateBottomCardAccessibility(View child, BottomCardDescriptor descriptor) {
        if (child == null) return;
        View current = child;
        while (current != null) {
            Object tag = current.getTag();
            if (tag instanceof String && ((String) tag).startsWith("bottomCard:")) {
                current.setContentDescription(bottomCardAccessibilityDescription(descriptor));
                return;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
    }

    private String bottomCardAccessibilityDescription(BottomCardDescriptor descriptor) {
        if ("power".equals(descriptor.id)) {
            StringBuilder description = new StringBuilder("Power flow. ");
            description.append(descriptor.primary.isEmpty() ? "Power flow unavailable" : descriptor.primary);
            if (!descriptor.metricA.isEmpty()) description.append(". ").append(descriptor.metricA);
            if (!descriptor.metricB.isEmpty()) description.append(". ").append(descriptor.metricB);
            if (!descriptor.secondary.isEmpty()) description.append(". Source ").append(descriptor.secondary);
            description.append(". Opens power flow details.");
            return description.toString();
        }
        if ("status".equals(descriptor.id)) {
            StringBuilder description = new StringBuilder("Vehicle status. ");
            description.append(descriptor.primary.isEmpty() ? "Status unavailable" : descriptor.primary);
            if (!descriptor.secondary.isEmpty()) description.append(". Source ").append(descriptor.secondary);
            String[] labels = {"driver door", "front passenger door", "rear left door",
                    "rear right door", "tailgate"};
            for (int i = 0; i < labels.length; i++) {
                String state = descriptor.openingStates != null && i < descriptor.openingStates.length
                        ? descriptor.openingStates[i] : "unknown";
                description.append(". ").append(labels[i]).append(" ").append(state);
            }
            description.append(". Opens vehicle status details.");
            return description.toString();
        }
        if ("navigation".equals(descriptor.id)) {
            StringBuilder description = new StringBuilder("Navigation. ");
            description.append(descriptor.primary.isEmpty() ? "No route data" : descriptor.primary);
            if (!descriptor.navIdleCity.isEmpty()) description.append(". ").append(descriptor.navIdleCity);
            if (!descriptor.secondary.isEmpty()) description.append(". ").append(descriptor.secondary);
            if (!descriptor.metricA.isEmpty()) description.append(". ").append(descriptor.metricA);
            if (!descriptor.navIdleAction.isEmpty()) description.append(". ").append(descriptor.navIdleAction);
            description.append(". Opens navigation.");
            return description.toString();
        }
        if (!"tires".equals(descriptor.id)) {
            return descriptor.title + (descriptor.value.isEmpty() ? "" : ": " + descriptor.value);
        }
        String[] readings = tireReadings(descriptor);
        StringBuilder description = new StringBuilder("Tires. ");
        description.append("Data state ").append(descriptor.state);
        if (!descriptor.secondary.isEmpty()) {
            description.append(". Source ").append(descriptor.secondary);
        }
        String[] positions = {"front left", "front right", "rear left", "rear right"};
        for (int i = 0; i < positions.length; i++) {
            description.append(". ").append(positions[i]).append(" ").append(readings[i]);
            if (descriptor.wheelStates != null && i < descriptor.wheelStates.length) {
                description.append(", ").append(descriptor.wheelStates[i]);
            }
        }
        return description.toString();
    }

    private String[] tireReadings(BottomCardDescriptor descriptor) {
        String[] readings = {"unavailable", "unavailable", "unavailable", "unavailable"};
        // An explicit payload wins over recovering numbers from display prose.
        if (!descriptor.tirePressures.isEmpty()) {
            String[] corners = descriptor.tirePressures.split("\\s*/\\s*", -1);
            if (corners.length >= 4) {
                for (int i = 0; i < readings.length; i++) {
                    String corner = corners[i].trim();
                    readings[i] = corner.isEmpty() ? "unavailable" : corner;
                }
                return readings;
            }
        }
        String[] front = descriptor.primary.split("\\s*[·/]\\s*", -1);
        String[] rear = descriptor.metricA.split("\\s*[·/]\\s*", -1);
        if (front.length >= 2) {
            readings[0] = front[0].isEmpty() ? "unavailable" : front[0];
            readings[1] = front[1].isEmpty() ? "unavailable" : front[1];
        }
        if (rear.length >= 2) {
            readings[2] = rear[0].isEmpty() ? "unavailable" : rear[0];
            readings[3] = rear[1].isEmpty() ? "unavailable" : rear[1];
        }
        if (front.length < 2 || rear.length < 2) {
            String[] legacy = descriptor.value.split("\\s*/\\s*", -1);
            if (legacy.length >= 4) {
                for (int i = 0; i < readings.length; i++) {
                    readings[i] = legacy[i].isEmpty() ? "unavailable" : legacy[i];
                }
            }
        }
        return readings;
    }

    private void rebuildQuickCardsRow() {
        if (quickCardsRow == null) return;
        // The views an open menu is anchored to are about to be replaced.
        dismissQuickMenu();
        populateQuickCardsRow(quickCardsRow, getResources().getDisplayMetrics().density);
        bindRailEditLongPress();
        refreshQuickCardsTheme();
        // A reorder or remove comes back from the page as a new card list; the
        // rebuilt tiles have to rejoin edit mode.
        if (railEditMode) applyRailEdit(true);
    }

    /**
     * Layout manager → Cards, mirrored onto the real rail. While it is open the
     * cards jiggle, carry a remove badge, and can be dragged into a new order.
     * The page owns the order: a drop or a remove is reported to it, and the rail
     * is rebuilt from its answer rather than reordered here.
     */
    private void setRailEditMode(boolean on) {
        if (railEditMode == on) return;
        railEditMode = on;
        refreshDockSurfaceUi(true);
        if (on) {
            applyRailEdit(true);
        } else {
            applyRailEdit(false);
            // Rebuilding restores every click listener edit mode took away.
            rebuildQuickCardsRow();
        }
    }

    /**
     * A downscaled copy of this window for the Desktops strip. PixelCopy reads
     * the composited surface, so the WebGL car, the HTML widgets and the native
     * rail come out as drawn; other apps' freeform windows are separate windows
     * and do not. The render thread scales straight into a thumbnail-sized
     * bitmap, and the JPEG encode runs off the UI thread. Cost on the MMI is
     * not measured yet -- it runs a few seconds after a desktop settles, never
     * per frame.
     */
    private void captureDesktopSnapshotNow(String token, int width, int height) {
        final String safeToken = token == null ? "" : token.replaceAll("[^A-Za-z0-9_-]", "");
        if (safeToken.isEmpty() || webView == null
                || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        View decor = getWindow().getDecorView();
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return;
        final int w = Math.max(64, Math.min(960, width));
        final int h = Math.max(24, Math.min(360, height));
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(getWindow(), new Rect(0, 0, decor.getWidth(), decor.getHeight()), bmp,
                    result -> {
                        if (result != android.view.PixelCopy.SUCCESS) {
                            bmp.recycle();
                            Log.w(TAG, "desktop snapshot failed: " + result);
                            return;
                        }
                        new Thread(() -> {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.JPEG, 72, out);
                            bmp.recycle();
                            final String js = "try{window.__app&&window.__app.onDesktopSnapshot('" + safeToken
                                    + "','data:image/jpeg;base64,"
                                    + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) + "');}catch(e){}";
                            webView.post(() -> webView.evaluateJavascript(js, null));
                        }, "desktop-snapshot").start();
                    }, mainHandler);
        } catch (RuntimeException e) {
            bmp.recycle();
            Log.w(TAG, "desktop snapshot failed", e);
        }
    }

    private void scrollRailToCard(String id) {
        if (!(cardsScrollView instanceof android.widget.HorizontalScrollView) || quickCardsRow == null) return;
        final android.widget.HorizontalScrollView scroll = (android.widget.HorizontalScrollView) cardsScrollView;
        // Posted: a rebuild this payload triggered has not been laid out yet.
        scroll.post(() -> {
            View card = quickCardsRow.findViewWithTag("bottomCard:" + id);
            if (card == null) return;
            scroll.smoothScrollTo(Math.max(0, card.getLeft() + card.getWidth() / 2 - scroll.getWidth() / 2), 0);
            // A short pulse says which card just arrived.
            card.animate().scaleX(1.07f).scaleY(1.07f).setDuration(170).withEndAction(() ->
                    card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()).start();
        });
    }

    private BottomCardDescriptor bottomCardById(String id) {
        for (BottomCardDescriptor card : bottomCards) {
            if (card.id.equals(id)) return card;
        }
        return null;
    }

    private List<View> railCardViews() {
        List<View> out = new ArrayList<>();
        if (quickCardsRow == null) return out;
        for (int i = 0; i < quickCardsRow.getChildCount(); i++) {
            View child = quickCardsRow.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && ((String) tag).startsWith("bottomCard:")) out.add(child);
        }
        return out;
    }

    private void applyRailEdit(boolean on) {
        for (android.animation.Animator a : railJiggles.values()) a.cancel();
        railJiggles.clear();
        float density = getResources().getDisplayMetrics().density;
        List<View> cards = railCardViews();
        for (int i = 0; i < cards.size(); i++) {
            final View card = cards.get(i);
            card.getOverlay().clear();
            card.setRotation(0f);
            card.setTranslationX(0f);
            card.setAlpha(1f);
            if (!on) {
                card.setOnTouchListener(null);
                continue;
            }
            final String id = ((String) card.getTag()).substring("bottomCard:".length());
            setSubtreeClickable(card, false);
            final RailBadge badge = new RailBadge(density, false);
            BottomCardDescriptor desc = bottomCardById(id);
            // ⋯ only on cards that have something to choose.
            final RailBadge more = desc != null && !desc.editMenu.isEmpty() ? new RailBadge(density, true) : null;
            final int size = Math.round(26 * density);
            final int moreSize = Math.round(30 * density);
            final int inset = Math.round(4 * density);
            card.getOverlay().add(badge);
            if (more != null) card.getOverlay().add(more);
            card.post(() -> {
                int w = card.getWidth();
                int hh = card.getHeight();
                badge.setBounds(w - size - inset, inset, w - inset, inset + size);
                if (more != null) more.setBounds(w - moreSize - inset, hh - moreSize - inset, w - inset, hh - inset);
                card.invalidate();
            });
            android.animation.ObjectAnimator jiggle =
                    android.animation.ObjectAnimator.ofFloat(card, View.ROTATION, -1.1f, 1.1f);
            jiggle.setDuration(i % 2 == 0 ? 130 : 150);
            jiggle.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            jiggle.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            jiggle.setStartDelay((i * 37L) % 90);
            jiggle.start();
            railJiggles.put(card, jiggle);
            card.setOnTouchListener(new RailEditTouch(id, card, badge, more, density));
        }
    }

    private static void setSubtreeClickable(View v, boolean clickable) {
        v.setClickable(clickable);
        v.setLongClickable(clickable);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setSubtreeClickable(g.getChildAt(i), clickable);
        }
    }

    /** Index the dragged card would take once removed and re-inserted. */
    private int railDropIndex(View dragged) {
        float center = dragged.getLeft() + dragged.getTranslationX() + dragged.getWidth() / 2f;
        int index = 0;
        for (View c : railCardViews()) {
            if (c == dragged) continue;
            if (center > c.getLeft() + c.getWidth() / 2f) index++;
        }
        return index;
    }

    /**
     * Long press on any rail card opens card edit. Set after every build, so it
     * replaces a card's own longAction; edit mode clears it again.
     */
    private void bindRailEditLongPress() {
        for (View card : railCardViews()) {
            card.setLongClickable(true);
            card.setOnLongClickListener(v -> {
                if (railEditMode) return false;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                callViewerDock("openRailEdit");
                return true;
            });
        }
    }

    /**
     * Edit-mode gesture. A quick swipe is left to the scroll view, which
     * intercepts it past touch slop. Only a hold arms the drag; once armed the
     * rail may no longer intercept, and holding the card near either edge
     * scrolls the rail so it can travel past the visible cards.
     */
    private final class RailEditTouch implements View.OnTouchListener {
        private final String id;
        private final View card;
        private final RailBadge badge;
        private final RailBadge more;
        private final float density;
        private final int slop;
        private float downX;
        private float lastRawX;
        private int downScrollX;
        private boolean dragging;
        private boolean armed;
        private final Runnable arm = this::armDrag;
        private final Runnable autoScroll = new Runnable() {
            @Override
            public void run() {
                if (!dragging) return;
                android.widget.HorizontalScrollView scroll = railScroll();
                if (scroll == null) return;
                int[] loc = new int[2];
                scroll.getLocationOnScreen(loc);
                float edge = 90f * density;
                float left = lastRawX - loc[0];
                float right = loc[0] + scroll.getWidth() - lastRawX;
                int step = 0;
                if (left < edge) step = -Math.round(Math.min(1f, (edge - left) / edge) * 22f * density);
                else if (right < edge) step = Math.round(Math.min(1f, (edge - right) / edge) * 22f * density);
                if (step != 0) {
                    scroll.scrollBy(step, 0);
                    follow();
                }
                card.postOnAnimation(this);
            }
        };

        RailEditTouch(String id, View card, RailBadge badge, RailBadge more, float density) {
            this.id = id;
            this.card = card;
            this.badge = badge;
            this.more = more;
            this.density = density;
            this.slop = android.view.ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        }

        private android.widget.HorizontalScrollView railScroll() {
            return cardsScrollView instanceof android.widget.HorizontalScrollView
                    ? (android.widget.HorizontalScrollView) cardsScrollView : null;
        }

        private int scrollX() {
            android.widget.HorizontalScrollView s = railScroll();
            return s == null ? 0 : s.getScrollX();
        }

        /** Finger offset plus however far the rail has scrolled under it. */
        private void follow() {
            card.setTranslationX(lastRawX - downX + (scrollX() - downScrollX));
            shiftNeighbours(railDropIndex(card));
        }

        private int shownTo = -1;
        /** Where the dragged card lands if dropped now: its offset to the open slot. */
        private float slotOffset;

        /**
         * Live swap feedback: the cards between the dragged card's origin and
         * its drop index slide over by one slot. railDropIndex reads getLeft(),
         * which translation does not move, so this cannot feed back on itself.
         */
        private void shiftNeighbours(int to) {
            if (to == shownTo) return;
            shownTo = to;
            List<View> cards = railCardViews();
            int from = cards.indexOf(card);
            float slot = card.getWidth() + marginRight(card);
            slotOffset = 0f;
            for (int i = 0; i < cards.size(); i++) {
                View c = cards.get(i);
                if (c == card) continue;
                float shift = 0f;
                if (from < to && i > from && i <= to) {
                    shift = -slot;
                    slotOffset += c.getWidth() + marginRight(c);
                } else if (to < from && i >= to && i < from) {
                    shift = slot;
                    slotOffset -= c.getWidth() + marginRight(c);
                }
                c.animate().translationX(shift).setDuration(160)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
        }

        private int marginRight(View v) {
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            return lp instanceof android.view.ViewGroup.MarginLayoutParams
                    ? ((android.view.ViewGroup.MarginLayoutParams) lp).rightMargin : 0;
        }

        private void armDrag() {
            armed = true;
            dragging = true;
            if (card.getParent() != null) card.getParent().requestDisallowInterceptTouchEvent(true);
            card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            android.animation.Animator jiggle = railJiggles.get(card);
            if (jiggle != null) jiggle.pause();
            card.setRotation(0f);
            card.setAlpha(0.92f);
            card.setScaleX(1.04f);
            card.setScaleY(1.04f);
            card.setTranslationZ(8f * density);
            card.postOnAnimation(autoScroll);
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastRawX = e.getRawX();
                    downScrollX = scrollX();
                    dragging = false;
                    armed = false;
                    card.postDelayed(arm, android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    lastRawX = e.getRawX();
                    if (!armed) {
                        // Moved before the hold completed: a swipe, not a drag.
                        if (Math.abs(lastRawX - downX) > slop) card.removeCallbacks(arm);
                        return true;
                    }
                    follow();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    card.removeCallbacks(arm);
                    if (dragging) {
                        int to = railDropIndex(card);
                        int from = railCardViews().indexOf(card);
                        shiftNeighbours(to);
                        settle(to >= 0 && to != from);
                        if (to >= 0 && to != from) callViewerDock("railMoveCard:" + id + ":" + to);
                    } else {
                        int x = Math.round(e.getX());
                        int y = Math.round(e.getY());
                        int slack = -Math.round(10 * density);
                        Rect hit = new Rect(badge.getBounds());
                        hit.inset(slack, slack);
                        if (hit.contains(x, y)) {
                            callViewerDock("railRemoveCard:" + id);
                        } else if (more != null) {
                            Rect moreHit = new Rect(more.getBounds());
                            moreHit.inset(slack, slack);
                            BottomCardDescriptor d = bottomCardById(id);
                            if (moreHit.contains(x, y) && d != null) showQuickMenuRows(card, d.editMenu, false);
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    // Also what the rail sends when it takes a quick swipe.
                    card.removeCallbacks(arm);
                    if (dragging) settle();
                    return true;
                default:
                    return false;
            }
        }

        private void settle() {
            settle(false);
        }

        /** moved: glide into the open slot and leave neighbours shifted until the rebuild. */
        private void settle(boolean moved) {
            dragging = false;
            armed = false;
            card.removeCallbacks(autoScroll);
            if (!moved) {
                for (View c : railCardViews()) {
                    if (c != card) c.animate().translationX(0f).setDuration(160).start();
                }
            }
            float target = moved ? slotOffset : 0f;
            shownTo = -1;
            card.animate().translationX(target).translationZ(0f).alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(120).start();
            android.animation.Animator jiggle = railJiggles.get(card);
            if (jiggle != null) jiggle.resume();
        }
    }

    /** × (remove) or ⋯ (options) on a rail card in edit mode, drawn on the view overlay. */
    private static final class RailBadge extends Drawable {
        private final boolean dots;
        private final android.graphics.Paint fill =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint stroke =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint dot =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        RailBadge(float density, boolean dots) {
            this.dots = dots;
            fill.setColor(0xF03B4148);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStrokeWidth(2f * density);
            stroke.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            dot.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = b.width() / 2f;
            c.drawCircle(cx, cy, r, fill);
            if (dots) {
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * r * .42f, cy, Math.max(1.5f, r * .12f), dot);
                return;
            }
            float k = r * 0.36f;
            c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke);
            c.drawLine(cx + k, cy - k, cx - k, cy + k, stroke);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private void applyQuickCardIndicators(JSONObject o) {
        if (o == null) return;
        String climate = cleanIndicator(o.optString("climateSummary", ""));
        JSONObject climateObj = o.optJSONObject("climate");
        if (climate.isEmpty() && climateObj != null) {
            String temp = cleanIndicator(climateObj.optString("temperature",
                    climateObj.optString("temp", "")));
            String fan = cleanIndicator(climateObj.optString("fan", ""));
            String auto = climateObj.has("auto")
                    ? (climateObj.optBoolean("auto", false) ? "AUTO ON" : "AUTO OFF") : "";
            StringBuilder b = new StringBuilder();
            if (!temp.isEmpty()) b.append(temp).append(temp.contains("°") ? "" : " °C");
            if (!fan.isEmpty()) {
                if (b.length() > 0) b.append("  ·  ");
                b.append("Fan ").append(fan);
            }
            if (!auto.isEmpty()) {
                if (b.length() > 0) b.append("  ·  ");
                b.append(auto);
            }
            climate = b.toString();
        }
        if (quickClimateValue != null && (!climate.isEmpty() || o.has("climateSummary"))) {
            String display = climate.isEmpty() ? "— °C  ·  Fan —  ·  AUTO —" : climate;
            quickClimateValue.setText(display);
            View parent = quickClimateValue.getParent() instanceof View
                    ? (View) quickClimateValue.getParent() : null;
            if (parent != null) parent.setContentDescription("Climate summary: " + display);
        }

        String consumption = cleanIndicator(o.optString("consumptionSummary", ""));
        Object rawConsumption = o.opt("consumption");
        if (consumption.isEmpty() && rawConsumption instanceof JSONObject) {
            JSONObject c = (JSONObject) rawConsumption;
            String value = cleanIndicator(c.optString("value", ""));
            String unit = cleanIndicator(c.optString("unit", ""));
            if (!value.isEmpty()) consumption = value + (unit.isEmpty() ? "" : " " + unit);
        } else if (consumption.isEmpty() && rawConsumption != null
                && rawConsumption != JSONObject.NULL) {
            consumption = cleanIndicator(String.valueOf(rawConsumption));
        }
        if (quickConsumptionValue != null && (!consumption.isEmpty() || o.has("consumptionSummary"))) {
            String display = consumption.isEmpty() ? "—" : consumption;
            quickConsumptionValue.setText(display);
            View parent = quickConsumptionValue.getParent() instanceof View
                    ? (View) quickConsumptionValue.getParent() : null;
            if (parent != null) parent.setContentDescription("Consumption summary: " + display);
        }
    }

    private String cleanIndicator(String value) {
        if (value == null) return "";
        String clean = value.trim().replace('\n', ' ').replace('\r', ' ');
        return clean.length() > 48 ? clean.substring(0, 48) : clean;
    }

    private void refreshDockToolGlyph(String cmd, String state) {
        android.widget.ImageView iv = dockToolIcons.get(cmd);
        if (iv == null) return;
        int size = iv.getLayoutParams() != null ? iv.getLayoutParams().width : 0;
        if (size <= 0) size = dockToolGlyphPx > 0 ? dockToolGlyphPx : 48;
        iv.setImageDrawable(dockToolGlyph(cmd, size, state));
    }

    /**
     * The card's quick menu: the mode list, then a way through to the full page.
     *
     * Anchored above the card rather than centred, so the thumb that opened it
     * is not covering the choices — the rail sits at the bottom of a 720px panel
     * and a centred dialog would land under the hand.
     */
    private void showQuickMenu(View anchor, BottomCardDescriptor descriptor) {
        showQuickMenuRows(anchor, descriptor.menu, true);
    }

    /** `lastIsExit`: the last row leaves the menu (the driving cards) and is styled apart. */
    private void showQuickMenuRows(View anchor, java.util.List<QuickMenuRow> rows, boolean lastIsExit) {
        if (rows == null || rows.isEmpty()) return;
        dismissQuickMenu();
        float density = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(6 * density);
        list.setPadding(pad, pad, pad, pad);
        list.setBackground(makeDockPopupPanel(density));
        list.setElevation(12f * density);

        for (QuickMenuRow row : rows) {
            final String command = row.command;
            boolean last = lastIsExit && row == rows.get(rows.size() - 1);
            android.widget.TextView item = new android.widget.TextView(this);
            item.setText(row.label);
            item.setTextSize(last ? 13f : 15f);
            item.setTypeface(android.graphics.Typeface.create(
                    row.selected ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
            item.setTextColor(row.selected ? dockAccentColor
                    : (last ? dockLabelColorMuted() : dockLabelColor()));
            item.setGravity(android.view.Gravity.CENTER);
            item.setBackground(makeQuickMenuItemBackground(row.selected, last, density));
            int ipad = Math.round(12 * density);
            item.setPadding(ipad, Math.round(12 * density), ipad, Math.round(12 * density));
            item.setClickable(true);
            item.setOnClickListener(v -> { dismissQuickMenu(); callViewerDock(command); });
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            Math.round(200 * density),
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(6 * density);
            item.setLayoutParams(lp);
            if (last) {
                // Still a button, but the rule above it says this one leaves the
                // menu rather than picking a mode.
                android.view.View rule = new android.view.View(this);
                android.widget.LinearLayout.LayoutParams rlp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                Math.max(1, Math.round(density)));
                rlp.topMargin = Math.round(2 * density);
                rlp.bottomMargin = Math.round(8 * density);
                rule.setLayoutParams(rlp);
                rule.setBackgroundColor(dockUiLight ? 0x1A25303B : 0x1FFFFFFF);
                list.addView(rule);
            }
            list.addView(item);
        }

        quickMenuWindow = new android.widget.PopupWindow(list,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        quickMenuWindow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        quickMenuWindow.setOutsideTouchable(true);
        list.measure(android.view.View.MeasureSpec.UNSPECIFIED,
                android.view.View.MeasureSpec.UNSPECIFIED);
        int gap = Math.round(8 * density);
        // Offset by the measured height plus the anchor's own, because showAsDropDown
        // measures DOWN from the anchor's bottom edge.
        int dy = -(list.getMeasuredHeight() + anchor.getHeight() + gap);
        quickMenuWindow.showAsDropDown(anchor, 0, dy);
    }

    /**
     * A menu row's pill. Selected carries the accent the way every other control
     * in this card does; the last row is the way out, so it stays outlined
     * rather than filled and does not compete with the modes.
     */
    private android.graphics.drawable.Drawable makeQuickMenuItemBackground(
            boolean selected, boolean muted, float density) {
        float radius = 10f * density;
        int stroke = Math.max(1, Math.round(density));
        android.graphics.drawable.GradientDrawable rest =
                new android.graphics.drawable.GradientDrawable();
        rest.setCornerRadius(radius);
        if (selected) {
            rest.setColor(withAlpha(dockAccentColor, 0x2E));
            rest.setStroke(stroke, dockAccentColor);
        } else if (muted) {
            rest.setColor(0x00000000);
            rest.setStroke(stroke, dockUiLight ? 0x2225303B : 0x22FFFFFF);
        } else {
            rest.setColor(dockUiLight ? 0x0F25303B : 0x14FFFFFF);
            rest.setStroke(stroke, dockUiLight ? 0x1A25303B : 0x1AFFFFFF);
        }
        android.graphics.drawable.GradientDrawable pressed =
                new android.graphics.drawable.GradientDrawable();
        pressed.setCornerRadius(radius);
        pressed.setColor(withAlpha(dockAccentColor, 0x40));
        pressed.setStroke(stroke, dockAccentColor);
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] { android.R.attr.state_pressed }, pressed);
        states.addState(new int[0], rest);
        return states;
    }

    private android.graphics.drawable.GradientDrawable makeDockPopupPanel(float density) {
        android.graphics.drawable.GradientDrawable panel =
                new android.graphics.drawable.GradientDrawable();
        panel.setCornerRadius(14f * density);
        panel.setColor(dockUiLight ? 0xFCF7FAFC : 0xFA0B1016);
        panel.setStroke(Math.max(1, Math.round(density)),
                dockUiLight ? 0x2225303B : 0x26FFFFFF);
        return panel;
    }

    private android.graphics.drawable.Drawable makeDockPopupFieldBackground(float density) {
        android.graphics.drawable.GradientDrawable field =
                new android.graphics.drawable.GradientDrawable();
        field.setCornerRadius(10f * density);
        field.setColor(dockUiLight ? 0x0F25303B : 0x14FFFFFF);
        field.setStroke(Math.max(1, Math.round(density)),
                dockUiLight ? 0x1A25303B : 0x1AFFFFFF);
        return field;
    }

    private android.widget.TextView makeDockPopupKicker(String text, float density) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(text);
        t.setTextColor(dockLabelColorMuted());
        t.setTextSize(11f);
        t.setLetterSpacing(0.08f);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        t.setPadding(0, 0, 0, Math.round(6 * density));
        return t;
    }

    private android.widget.TextView makeDockPopupAction(String label, boolean primary,
            float density, Runnable action) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(label);
        t.setTextSize(14f);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(Math.round(16 * density), Math.round(12 * density),
                Math.round(16 * density), Math.round(12 * density));
        t.setTextColor(primary ? dockAccentColor : dockLabelColor());
        t.setBackground(makeQuickMenuItemBackground(primary, !primary, density));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> action.run());
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Math.round(4 * density), 0, Math.round(4 * density), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void dismissQuickMenu() {
        if (quickMenuWindow == null) return;
        try { quickMenuWindow.dismiss(); } catch (RuntimeException ignored) {}
        quickMenuWindow = null;
    }

    private android.widget.PopupWindow quickMenuWindow;
    /**
     * Theme the open quick menu was built for. refreshQuickCardsTheme runs on
     * every dock update (applyDockIndicators → refreshDockSurfaceUi), so it may
     * only close the menu when the theme it baked in has really changed.
     */
    private String quickMenuThemeSig = "";

    private void callViewerDock(String cmd) {
        if (webView == null || cmd == null) return;
        final String safe = cmd.replace("'", "").replace("\\", "").replace("\"", "");
        final String js = "try{if(window.__app&&typeof window.__app.dockCommand==='function'){"
                + "window.__app.dockCommand('" + safe + "');}}catch(e){}";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    /** Two-line caption band — every dock tile reserves the same height. */
    private void styleDockCaption(android.widget.TextView labelView, float density) {
        labelView.setTextSize(11f);
        labelView.setTextColor(0xE6FFFFFF);
        labelView.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        labelView.setMaxLines(2);
        labelView.setMinLines(2);
        labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelView.setLineSpacing(0f, 0.92f);
        labelView.setIncludeFontPadding(false);
        labelView.setShadowLayer(3f, 0f, 1f, 0xCC000000);
        labelView.setPadding(Math.round(2 * density), Math.round(4 * density),
                Math.round(2 * density), 0);
    }

    private MotionTrailLayout makeDockItem(int itemWidthPx, int iconSizePx, float density) {
        MotionTrailLayout item = new MotionTrailLayout(this);
        item.setOrientation(android.widget.LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP);
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
        android.widget.FrameLayout iconBox = wrapLauncherIconPlate(iconView, iconSizePx, density);
        int platePx = dockPlatePx(iconSizePx, density);
        iconBox.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, platePx));
        item.addView(iconBox);

        // Caption under every icon — fixed two-line band so icon plates stay aligned.
        android.widget.TextView labelView = new android.widget.TextView(this);
        labelView.setTag("label");
        styleDockCaption(labelView, density);
        item.addView(labelView);
        return item;
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

    /**
     * Structurally absent from the scrolling app row — pinned apps live in their
     * own slots, the rest are never launcher material. Unlike
     * {@link #skipInAppRow} this ignores the user's hide list: hidden apps still
     * get a (GONE) item built at setup so "Unhide" can just flip visibility
     * instead of splicing a new view into the row.
     */
    private boolean excludedFromAppRow(String pkg) {
        return pkg.equals(getPackageName())
                || IGNORED_PACKAGES.contains(pkg)
                || isPinnedPackage(pkg)
                || ProjectionPresence.isProjectionPackage(pkg);
    }

    /** Hidden from the scrolling app row (pinned apps live in their own slots). */
    private boolean skipInAppRow(String pkg) {
        return excludedFromAppRow(pkg) || isUserHidden(pkg);
    }

    /**
     * APP+APP catalog: the same packages the launcher actually shows — scroll-row
     * apps plus visible GWM hub destinations — with the same hide list. Pinned
     * packages stay in the catalog because they are on the launcher (hub flyout);
     * ignored / projection / this viewer / user-hidden stay out.
     */
    private boolean skipInAppsCatalog(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(getPackageName())) return true;
        if (IGNORED_PACKAGES.contains(pkg)) return true;
        if (ProjectionPresence.isProjectionPackage(pkg)) return true;
        return isUserHidden(pkg);
    }

    private JSONObject appsCatalogEntry(PackageManager pm, ResolveInfo info, String pkg)
            throws JSONException {
        String stockLabel = isPinnedPackage(pkg) ? pinnedLabel(pkg, pm, info) : launcherLabel(pm, info);
        Drawable stockIcon = isPinnedPackage(pkg) ? flyoutIconForPinned(pkg) : iconForLauncherApp(pm, info);
        return appsCatalogObject(pkg, resolveDockLabel(pkg, stockLabel), resolveDockIcon(pkg, stockIcon));
    }

    private JSONObject appsCatalogStubEntry(String pkg, String stubLabel) throws JSONException {
        return appsCatalogObject(pkg, resolveDockLabel(pkg, stubLabel),
                resolveDockIcon(pkg, launcherIconForPackage(pkg)));
    }

    private JSONObject appsCatalogObject(String pkg, String label, Drawable icon) throws JSONException {
        JSONObject appObj = new JSONObject();
        appObj.put("packageName", pkg);
        appObj.put("label", label != null ? label : "");
        try {
            Drawable drawn = normalizeAdaptiveIcon(icon, 96);
            String dataUrl = encodeIconDataUrl(drawn, 96, appsCatalogPlateColor(pkg));
            if (dataUrl != null) appObj.put("icon", dataUrl);
        } catch (Exception e) {
            Log.e(TAG, "Error drawing icon for " + pkg, e);
        }
        return appObj;
    }

    private boolean skipInRecents(String pkg) {
        return skipInAppRow(pkg);
    }

    /**
     * The pinned GWM shortcuts all share one emblem, and the OEM labels
     * ("VehicleCenter", "Settings") say nothing about which is which on a car
     * screen. These are the names drawn on the icons.
     */
    private String pinnedLabel(String pkg, PackageManager pm, ResolveInfo info) {
        if ("com.beantechs.vehiclecenter".equals(pkg)) return "Configurações do Veículo";
        if ("com.beantechs.settings".equals(pkg)) return "Configurações do Sistema";
        if ("com.beantechs.energyassistant".equals(pkg)) return "Energy Assistant";
        if ("com.beantechs.launcher".equals(pkg)) return "GWM Home";
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

    /**
     * Our bundled mark for a projection package, or null for anything else.
     *
     * CarPlay is matched first because ProjectionPresence.isProjectionPackage
     * answers true for both families, so an unordered test would hand a CarPlay
     * package the Android Auto drawable.
     */
    private Bitmap projectionIconBitmap(String packageName) {
        if (!ProjectionPresence.isProjectionPackage(packageName)) return null;
        boolean carPlay = packageName.toLowerCase().contains("carplay");
        Drawable icon = null;
        if (projectionPresence != null) {
            icon = projectionPresence.iconFor(
                    carPlay ? ProjectionPresence.Kind.CARPLAY
                            : ProjectionPresence.Kind.ANDROID_AUTO);
        }
        if (icon == null) {
            try {
                icon = getDrawable(carPlay ? R.drawable.ic_carplay_default
                        : R.drawable.ic_android_auto_default);
            } catch (Exception ignored) {}
        }
        if (icon == null) return null;
        try {
            Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            icon.setBounds(0, 0, 96, 96);
            icon.draw(canvas);
            return bmp;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap iconBitmapForPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return null;
        Bitmap hit = packageIconBitmaps.get(packageName);
        if (hit != null) return hit;
        if (packageIconMiss.contains(packageName)) return null;
        // Our own branding wins for projection packages, BEFORE the package
        // manager is asked. The OEM Autolink icon resolves perfectly well, so
        // it used to win everywhere this is called and the bundled drawable was
        // only ever reached when the lookup FAILED. That put the OEM badge on
        // the navigation card while the media card — which resolves through
        // ProjectionPresence — showed ours, two different Android Auto marks on
        // one rail. This is also what AppLauncherBridge.getAppIcon serves, so
        // the web widget and popup pick the same icon up for free.
        Bitmap branded = projectionIconBitmap(packageName);
        if (branded != null) {
            packageIconBitmaps.put(packageName, branded);
            return branded;
        }
        try {
            Drawable icon = getPackageManager().getApplicationIcon(packageName);
            icon = normalizeAdaptiveIcon(icon, 96);
            if (icon != null) {
                int w = Math.max(1, icon.getIntrinsicWidth());
                int h = Math.max(1, icon.getIntrinsicHeight());
                if (w > 128 || h > 128 || w <= 0 || h <= 0) { w = 96; h = 96; }
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                icon.draw(canvas);
                packageIconBitmaps.put(packageName, bmp);
                return bmp;
            }
        } catch (Exception ignored) {}
        if (ProjectionPresence.isProjectionPackage(packageName)
                && !packageName.toLowerCase().contains("carplay")) {
            try {
                Drawable fallback = getDrawable(R.drawable.ic_android_auto);
                if (fallback != null) {
                    Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                    fallback.setBounds(0, 0, 96, 96);
                    fallback.draw(canvas);
                    packageIconBitmaps.put(packageName, bmp);
                    return bmp;
                }
            } catch (Exception ignored) {}
        }
        packageIconMiss.add(packageName);
        return null;
    }

    private Drawable launcherIconForPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        if ("com.beantechs.energyassistant".equalsIgnoreCase(pkg)) {
            try {
                Drawable energy = getDrawable(R.drawable.ic_energy_assistant);
                if (energy != null) return energy;
            } catch (Exception ignored) {}
        } else if (usesGwmEmblem(pkg)) {
            try {
                Drawable gwm = getDrawable(R.drawable.ic_gwm);
                if (gwm != null) return gwm;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Drawable iconForLauncherApp(PackageManager pm, ResolveInfo info) {
        if (info == null || info.activityInfo == null) return null;
        String pkg = info.activityInfo.packageName;
        Drawable branded = launcherIconForPackage(pkg);
        if (branded != null) return branded;
        return info.loadIcon(pm);
    }

    /**
     * Adaptive icons (API 26+; what {@code info.loadIcon(pm)} returns for almost
     * every real installed app) keep their glyph inside a safe zone that is only
     * 72/108 of the drawable's own canvas — the rest is transparent margin a real
     * launcher crops away when it scales the icon to fill its slot. Drawn at
     * native scale instead, the glyph reads smaller than our plate and the
     * plate's own near-white background shows through the margin as a visible
     * border around it. {@link #adaptiveIconFillScale} scales the layers up to
     * close that margin — a compliant icon exactly at the 72/108 safe zone
     * gets ~1.23x, well under the naive 108/72 = 1.5x unwrap, and even a glyph
     * padded far past the safe zone (a small centered mark like Chrome's) is
     * capped at 1.6x so it can't balloon past the plate and get sliced by its
     * rounded corners.
     * <p>
     * Only real installed apps (i.e. the car) hit this — the emulator's pinned
     * stubs draw our own bundled vector icons ({@code ic_gwm},
     * {@code ic_energy_assistant}), which are already full-bleed.
     */
    private Drawable normalizeAdaptiveIcon(Drawable icon, int sizePx) {
        if (icon == null || sizePx <= 0
                || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O
                || !(icon instanceof android.graphics.drawable.AdaptiveIconDrawable)) {
            return icon;
        }
        try {
            android.graphics.drawable.AdaptiveIconDrawable adaptive =
                    (android.graphics.drawable.AdaptiveIconDrawable) icon;
            Drawable bg = adaptive.getBackground();
            Drawable fg = adaptive.getForeground();
            float scale = adaptiveIconFillScale(fg);
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            int inset = -Math.round(sizePx * (scale - 1f) / 2f);
            int far = sizePx - inset;
            if (bg != null) {
                bg.setBounds(inset, inset, far, far);
                bg.draw(canvas);
            }
            if (fg != null) {
                fg.setBounds(inset, inset, far, far);
                fg.draw(canvas);
            }
            return new BitmapDrawable(getResources(), bmp);
        } catch (Exception e) {
            Log.e(TAG, "Error normalizing adaptive icon", e);
            return icon;
        }
    }

    /**
     * How far to scale an adaptive icon's layers so the glyph reaches a
     * consistent visual weight. Icon packs bake wildly different amounts of
     * padding into the foreground layer's own 108dp canvas — a flat Material
     * glyph (Settings, Camera) already fills nearly edge to edge, while a mark
     * like Chrome's occupies a much smaller circle in the middle, sized for a
     * circular launcher mask we never apply. A single fixed crop cannot fit
     * both: measure each icon's real opaque footprint and scale to a fixed
     * target fill, the same idea a real launcher's icon normalizer uses.
     */
    private float adaptiveIconFillScale(Drawable fg) {
        float targetFill = 0.82f;
        float contentFrac = measureForegroundContentFraction(fg);
        float scale = targetFill / Math.max(contentFrac, 0.35f);
        return Math.max(1f, Math.min(scale, 1.6f));
    }

    /** Fraction of the foreground layer's own canvas its opaque glyph occupies. */
    private float measureForegroundContentFraction(Drawable fg) {
        if (fg == null) return 1f;
        Bitmap bmp = null;
        try {
            int probe = 96;
            Drawable.ConstantState cs = fg.getConstantState();
            Drawable copy = cs != null ? cs.newDrawable().mutate() : fg;
            bmp = Bitmap.createBitmap(probe, probe, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            copy.setBounds(0, 0, probe, probe);
            copy.draw(canvas);
            int minX = probe, minY = probe, maxX = -1, maxY = -1;
            int[] row = new int[probe];
            for (int y = 0; y < probe; y++) {
                bmp.getPixels(row, 0, probe, 0, y, probe, 1);
                for (int x = 0; x < probe; x++) {
                    if ((row[x] >>> 24) > 16) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < minX || maxY < minY) return 1f;
            float w = (maxX - minX + 1) / (float) probe;
            float h = (maxY - minY + 1) / (float) probe;
            return Math.max(w, h);
        } catch (Exception e) {
            return 1f;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private boolean isEmulatorDevice() {
        if (emulatorDevice != null) return emulatorDevice;
        String fp = android.os.Build.FINGERPRINT != null ? android.os.Build.FINGERPRINT : "";
        String model = android.os.Build.MODEL != null ? android.os.Build.MODEL : "";
        String product = android.os.Build.PRODUCT != null ? android.os.Build.PRODUCT : "";
        String hardware = android.os.Build.HARDWARE != null ? android.os.Build.HARDWARE : "";
        emulatorDevice = fp.startsWith("generic")
                || fp.contains("emulator")
                || model.contains("sdk_gphone")
                || model.contains("Android SDK built for")
                || product.contains("sdk")
                || product.contains("emulator")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu");
        return emulatorDevice;
    }

    /**
     * GWM / BeanTechs / Autolink apps share {@code ic_gwm} from the Impulse
     * launcher assets. App names are drawn under the icon as captions.
     */
    private boolean usesGwmEmblem(String pkg) {
        return isGwmApp(pkg) && !"com.beantechs.energyassistant".equalsIgnoreCase(pkg);
    }

    /** Rounded plate behind launcher icons — dark for GWM emblem + Energy + gwm substitute. */
    private boolean usesDarkIconPlate(String pkg) {
        if (DockAppOverrides.GWM_HUB_PKG.equals(pkg)) {
            String slug = dockAppOverrides != null ? dockAppOverrides.icon(pkg) : null;
            return slug == null || DockAppOverrides.isDarkPlateSlug(slug);
        }
        String slug = dockAppOverrides != null ? dockAppOverrides.icon(pkg) : null;
        if (slug != null) return DockAppOverrides.isDarkPlateSlug(slug);
        return usesGwmEmblem(pkg)
                || "com.beantechs.energyassistant".equalsIgnoreCase(pkg);
    }

    private String resolveDockLabel(String pkg, String stock) {
        if (pkg == null) return stock != null ? stock : "";
        String custom = dockAppOverrides != null ? dockAppOverrides.name(pkg) : null;
        if (custom != null) return custom;
        if (DockAppOverrides.GWM_HUB_PKG.equals(pkg)) return "GWM";
        return stock != null ? stock : "";
    }

    private Drawable resolveDockIcon(String pkg, Drawable stock) {
        if (pkg == null || dockAppOverrides == null) return stock;
        String slug = dockAppOverrides.icon(pkg);
        if (slug == null) {
            if (DockAppOverrides.GWM_HUB_PKG.equals(pkg)) {
                try {
                    return getDrawable(R.drawable.ic_gwm);
                } catch (Exception e) {
                    return stock;
                }
            }
            return stock;
        }
        Drawable sub = DockAppOverrides.drawableFor(this, slug, dockAppOverrides.color(pkg));
        return sub != null ? sub : stock;
    }

    private void bindGwmHub() {
        if (gwmHubItem == null) return;
        Drawable icon = resolveDockIcon(DockAppOverrides.GWM_HUB_PKG, launcherIconForPackage("com.beantechs.vehiclecenter"));
        if (icon == null) {
            try { icon = getDrawable(R.drawable.ic_gwm); } catch (Exception ignored) {}
        }
        String label = resolveDockLabel(DockAppOverrides.GWM_HUB_PKG, "GWM");
        bindDockItem(gwmHubItem, icon, label,
                v -> showGwmHubMenu(v), DockAppOverrides.GWM_HUB_PKG);
    }

    /**
     * "Side by Side" launcher tile. The two-app split used to be one of three
     * layout choices; now it opens like an app, and is left from its own
     * floating menu (Exit Side by Side).
     */
    private void bindSideBySideItem() {
        if (sideBySideItem == null) return;
        float density = getResources().getDisplayMetrics().density;
        int iconPx = dockIconPx > 0 ? dockIconPx : Math.round(60 * density);
        bindDockItem(sideBySideItem, makeSideBySideGlyph(iconPx), "Side by Side",
                v -> applyShellMode(SHELL_APPS));
        // Dark plate, light glyph: the same treatment as the GWM hub tile, so it
        // reads on either dock theme.
        View plate = sideBySideItem.findViewWithTag("iconPlate");
        if (plate != null) {
            sideBySideItem.setTag(Boolean.TRUE);
            plate.setBackground(makeLauncherIconPlateDrawable(density, true));
        }
        sideBySideItem.setContentDescription("Side by Side: two apps split the screen");
    }

    private Drawable makeSideBySideGlyph(int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFEAF2F8);
        float s = sizePx;
        float padX = s * 0.2f;
        float padY = s * 0.27f;
        float gap = s * 0.07f;
        float w = (s - 2f * padX - gap) / 2f;
        float r = s * 0.05f;
        c.drawRoundRect(padX, padY, padX + w, s - padY, r, r, paint);
        paint.setAlpha(190);
        c.drawRoundRect(padX + w + gap, padY, s - padX, s - padY, r, r, paint);
        return new BitmapDrawable(getResources(), bmp);
    }

    private void showGwmHubMenu(View anchor) {
        if (anchor == null) return;
        dismissGwmHubMenu();
        dismissDockEditMenu();
        float d = getResources().getDisplayMetrics().density;
        PackageManager pm = getPackageManager();
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xF2141820);
        box.setElevation(14f * d);
        int pad = Math.round(18 * d);
        box.setPadding(pad, Math.round(12 * d), pad, Math.round(12 * d));
        box.setMinimumWidth(Math.round(280 * d));

        int shown = 0;
        int iconPx = Math.round(40 * d);
        for (int i = 0; i < PINNED_PACKAGES.length; i++) {
            if (!pinnedBound[i]) continue;
            String pkg = PINNED_PACKAGES[i];
            if (isUserHidden(pkg)) continue;
            String label = resolveDockLabel(pkg, pinnedLabel(pkg, pm, null));
            Drawable icon = flyoutIconForPinned(pkg);

            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(8 * d), Math.round(14 * d), Math.round(8 * d), Math.round(14 * d));
            row.setMinimumHeight(Math.round(56 * d));
            row.setClickable(true);
            row.setFocusable(true);

            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageDrawable(icon);
            android.widget.LinearLayout.LayoutParams ilp =
                    new android.widget.LinearLayout.LayoutParams(iconPx, iconPx);
            ilp.rightMargin = Math.round(14 * d);
            row.addView(iv, ilp);

            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(label);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(17f);
            row.addView(tv);

            final String targetPkg = pkg;
            final String targetLabel = label;
            row.setOnClickListener(v -> {
                dismissGwmHubMenu();
                launchAppForPackage(targetPkg, targetLabel);
            });
            box.addView(row);
            shown++;
        }
        if (shown == 0) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("Nenhum app GWM");
            empty.setTextColor(0x99FFFFFF);
            empty.setTextSize(15f);
            empty.setPadding(Math.round(8 * d), Math.round(10 * d), Math.round(8 * d), Math.round(10 * d));
            box.addView(empty);
        }

        android.widget.PopupWindow popup = new android.widget.PopupWindow(
                box,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        popup.setOnDismissListener(() -> {
            if (gwmHubMenu == popup) gwmHubMenu = null;
        });
        gwmHubMenu = popup;
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int xOff = (anchor.getWidth() - box.getMeasuredWidth()) / 2;
        int yOff = -(box.getMeasuredHeight() + anchor.getHeight() + Math.round(10 * d));
        popup.showAsDropDown(anchor, xOff, yOff);
    }
    private void dismissGwmHubMenu() {
        if (gwmHubMenu == null) return;
        try { gwmHubMenu.dismiss(); } catch (Exception ignored) {}
        gwmHubMenu = null;
    }

    /** Small leading glyph for each GWM flyout row. */
    private Drawable flyoutIconForPinned(String pkg) {
        if ("com.beantechs.energyassistant".equalsIgnoreCase(pkg)) {
            try {
                Drawable d = getDrawable(R.drawable.ic_energy_assistant);
                if (d != null) return d;
            } catch (Exception ignored) {}
        }
        if ("com.beantechs.settings".equals(pkg)) {
            Drawable sub = DockAppOverrides.drawableFor(this, "settings", "#FFFFFF");
            if (sub != null) return sub;
        }
        try {
            Drawable emblem = getDrawable(R.drawable.ic_gwm_emblem);
            if (emblem != null) return emblem;
        } catch (Exception ignored) {}
        try {
            return getDrawable(R.drawable.ic_gwm);
        } catch (Exception e) {
            return null;
        }
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
        float density = getResources().getDisplayMetrics().density;
        int iconPx = dockIconPx > 0 ? dockIconPx : Math.round(60 * density);
        Drawable drawn = editPackage != null ? resolveDockIcon(editPackage, icon) : icon;
        String caption = editPackage != null ? resolveDockLabel(editPackage, label) : (label != null ? label : "");
        if (iv != null) iv.setImageDrawable(normalizeAdaptiveIcon(drawn, iconPx));
        View plate = item.findViewWithTag("iconPlate");
        if (plate != null) {
            Integer plateColor = editPackage != null ? dockOverridePlateColor(editPackage) : null;
            if (plateColor != null) {
                item.setTag(plateColor);
                plate.setBackground(makeLauncherIconPlateDrawable(density, plateColor.intValue()));
            } else {
                boolean darkPlate = editPackage != null && usesDarkIconPlate(editPackage);
                item.setTag(Boolean.valueOf(darkPlate));
                plate.setBackground(makeLauncherIconPlateDrawable(density, darkPlate));
            }
            int platePx = dockPlatePx(iconPx, density);
            int inset = Math.round(platePx * launcherIconInsetFrac());
            plate.setPadding(inset, inset, inset, inset);
        }
        if (tv != null) {
            tv.setText(caption);
            tv.setVisibility(caption.isEmpty() ? View.GONE : View.VISIBLE);
        }
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
            return;
        }
        Drawable icon = projectionPresence != null ? projectionPresence.iconFor(kind) : null;
        String label = projectionPresence != null ? projectionPresence.labelFor(kind) : "";
        bindDockItem(projectionItem, icon, label, v -> launchProjection(kind));
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

    private void loadWindowApps() {
        windowPackages.clear();
        String raw = getSharedPreferences(PREFS_SHELL, MODE_PRIVATE).getString("window_apps", "");
        if (raw == null || raw.isEmpty()) return;
        for (String pkg : raw.split(",")) {
            if (pkg == null) continue;
            pkg = pkg.trim();
            if (pkg.isEmpty()) continue;
            windowPackages.add(pkg);
        }
    }

    private void saveWindowApps() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String pkg : windowPackages) {
            if (!first) sb.append(',');
            first = false;
            sb.append(pkg);
        }
        getSharedPreferences(PREFS_SHELL, MODE_PRIVATE)
                .edit()
                .putString("window_apps", sb.toString())
                .apply();
    }

    private void toggleOpenAsWindow(String pkg) {
        if (!canToggleOpenAsWindow(pkg)) return;
        if (windowPackages.contains(pkg)) windowPackages.remove(pkg);
        else windowPackages.add(pkg);
        saveWindowApps();
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
        dismissUnhidePicker();
        dismissDockCustomizeSheet();
        if (dockEditMenu == null) return;
        try {
            dockEditMenu.dismiss();
        } catch (Exception ignored) {}
        dockEditMenu = null;
    }

    private void showDockEditMenu(View anchor, String pkg) {
        if (anchor == null || pkg == null || pkg.isEmpty()) return;
        dismissDockEditMenu();
        dismissGwmHubMenu();
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xF2141820);
        box.setElevation(12f * d);
        int padH = Math.round(18 * d);
        int padV = Math.round(12 * d);
        box.setPadding(padH, padV, padH, padV);
        box.setMinimumWidth(Math.round(220 * d));

        final boolean isHub = DockAppOverrides.GWM_HUB_PKG.equals(pkg);
        box.addView(makeDockMenuRow("Customize…", () -> {
            dismissDockEditMenu();
            showDockCustomizeSheet(anchor, pkg);
        }));
        box.addView(makeDockMenuRow("Hide", () -> {
            dismissDockEditMenu();
            if (isHub) {
                hideGwmHub();
            } else {
                removeDockPackage(pkg, true);
            }
        }));
        if (!isHub && canToggleOpenAsWindow(pkg)) {
            boolean on = prefersWindow(pkg);
            box.addView(makeDockMenuRow(on ? "✓ Open as window" : "Open as window", () -> {
                dismissDockEditMenu();
                toggleOpenAsWindow(pkg);
            }));
        }
        if (!hiddenPackages.isEmpty()) {
            box.addView(makeDockMenuRow("Unhide selected…", () -> {
                dismissDockEditMenu();
                showUnhidePicker();
            }));
        }
        if (!isHub && canUninstallPackage(pkg)) {
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

    private void hideGwmHub() {
        for (String pinned : PINNED_PACKAGES) {
            hiddenPackages.add(pinned);
        }
        saveHiddenApps();
        updatePinnedVisibility();
        refreshRecentSlots();
        notifyAppsCatalogChanged();
    }

    private void dismissDockCustomizeSheet() {
        if (dockCustomizeSheet == null) return;
        try { dockCustomizeSheet.dismiss(); } catch (Exception ignored) {}
        dockCustomizeSheet = null;
    }

    private void addDockIconGridCell(
            android.widget.LinearLayout grid,
            android.widget.LinearLayout[] currentRow,
            int[] col,
            java.util.List<View> iconCells,
            int cols, int cellPx, int gap, View cell) {
        if (currentRow[0] == null || col[0] >= cols) {
            currentRow[0] = new android.widget.LinearLayout(this);
            currentRow[0].setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.LinearLayout.LayoutParams rowLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = gap;
            currentRow[0].setLayoutParams(rowLp);
            grid.addView(currentRow[0]);
            col[0] = 0;
        }
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(cellPx, cellPx);
        lp.rightMargin = gap;
        cell.setLayoutParams(lp);
        currentRow[0].addView(cell);
        col[0]++;
        iconCells.add(cell);
    }

    private void addDockColorSwatches(android.widget.LinearLayout row, int swatchPx, int gapPx, float d) {
        String[] colors = DockAppOverrides.COLORS;
        for (int i = 0; i < colors.length; i++) {
            String hex = colors[i];
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(swatchPx, swatchPx);
            if (i < colors.length - 1) lp.rightMargin = gapPx;
            cell.setLayoutParams(lp);
            cell.setTag(hex);
            android.graphics.drawable.GradientDrawable oval =
                    new android.graphics.drawable.GradientDrawable();
            oval.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            oval.setColor(DockAppOverrides.parseColor(hex, 0xFFFFFFFF));
            oval.setStroke(Math.round(d), 0x38FFFFFF);
            cell.setBackground(oval);
            row.addView(cell);
        }
    }

    /**
     * Rename + substitute icon + tint + plate. Saves into {@link DockAppOverrides}.
     */
    private void showDockCustomizeSheet(View anchor, String pkg) {
        if (anchor == null || pkg == null || pkg.isEmpty()) return;
        dismissDockCustomizeSheet();
        float d = getResources().getDisplayMetrics().density;
        String stockLabel;
        if (DockAppOverrides.GWM_HUB_PKG.equals(pkg)) {
            stockLabel = "GWM";
        } else if (isPinnedPackage(pkg)) {
            stockLabel = pinnedLabel(pkg, getPackageManager(), null);
        } else {
            String fromCaption = null;
            MotionTrailLayout row = dockItemsByPackage.get(pkg);
            if (row != null) {
                android.widget.TextView tv = (android.widget.TextView) row.findViewWithTag("label");
                if (tv != null && tv.getText() != null) {
                    fromCaption = tv.getText().toString().trim();
                }
            }
            String resolved = fromCaption != null ? fromCaption : pkg;
            try {
                PackageManager pm = getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence lab = pm.getApplicationLabel(ai);
                if (lab != null) resolved = lab.toString();
            } catch (Exception ignored) {}
            stockLabel = resolved;
        }
        final String curName = dockAppOverrides != null ? dockAppOverrides.name(pkg) : null;
        final String[] selectedIcon = {
                dockAppOverrides != null ? dockAppOverrides.icon(pkg) : null
        };
        final String[] selectedColor = {
                dockAppOverrides != null && dockAppOverrides.color(pkg) != null
                        ? dockAppOverrides.color(pkg) : DockAppOverrides.COLOR_DEFAULT
        };
        final String[] selectedBg = {
                dockAppOverrides != null && dockAppOverrides.bg(pkg) != null
                        ? dockAppOverrides.bg(pkg) : DockAppOverrides.BG_DEFAULT
        };

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackground(makeDockPopupPanel(d));
        scroll.setClipToOutline(true);
        scroll.setElevation(12f * d);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(16 * d);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(makeDockPopupKicker("PERSONALIZAR", d));

        android.widget.EditText nameField = new android.widget.EditText(this);
        nameField.setHint(stockLabel);
        nameField.setText(curName != null ? curName : "");
        nameField.setHintTextColor(dockLabelColorMuted());
        nameField.setTextColor(dockLabelColor());
        nameField.setTextSize(16f);
        nameField.setSingleLine(true);
        nameField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        nameField.setBackground(makeDockPopupFieldBackground(d));
        nameField.setPadding(Math.round(12 * d), Math.round(10 * d),
                Math.round(12 * d), Math.round(10 * d));
        box.addView(nameField);

        android.widget.TextView iconLabel = makeDockPopupKicker("ÍCONE", d);
        iconLabel.setPadding(0, Math.round(12 * d), 0, Math.round(6 * d));
        box.addView(iconLabel);

        final int cellPx = Math.round(48 * d);
        final int cellPad = Math.round(7 * d);
        final int gap = Math.round(6 * d);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int availInner = screenW - Math.round(24 * d) - 2 * pad;
        final int cols = Math.max(7, Math.min(14, (availInner + gap) / (cellPx + gap)));
        final int maxW = 2 * pad + cols * (cellPx + gap);
        android.widget.LinearLayout iconGrid = new android.widget.LinearLayout(this);
        iconGrid.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(iconGrid);

        final java.util.List<View> iconCells = new ArrayList<>();
        final android.widget.LinearLayout[] currentRow = { null };
        final int[] col = { 0 };

        Runnable paintIconCells = () -> {
            int plate = DockAppOverrides.parseColor(selectedBg[0], 0xFF3D4650);
            for (View cell : iconCells) {
                Object tag = cell.getTag();
                boolean on = (tag == null && selectedIcon[0] == null)
                        || (tag != null && tag.equals(selectedIcon[0]));
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(10f * d);
                bg.setColor(tag == null
                        ? (dockUiLight ? 0x0F25303B : 0x14FFFFFF)
                        : plate);
                bg.setStroke(Math.round((on ? 2.5f : 1f) * d), on ? dockAccentColor
                        : (dockUiLight ? 0x2225303B : 0x33FFFFFF));
                cell.setBackground(bg);
                if (tag instanceof String && cell instanceof android.widget.FrameLayout) {
                    android.widget.FrameLayout fl = (android.widget.FrameLayout) cell;
                    if (fl.getChildCount() > 0) {
                        View child = fl.getChildAt(0);
                        if (child instanceof android.widget.ImageView) {
                            ((android.widget.ImageView) child).setImageDrawable(
                                    DockAppOverrides.drawableFor(this, (String) tag, selectedColor[0]));
                        }
                    }
                }
            }
        };

        {
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            cell.setPadding(cellPad, cellPad, cellPad, cellPad);
            cell.setTag(null);
            android.widget.TextView t = new android.widget.TextView(this);
            t.setText("Padrão");
            t.setTextColor(dockLabelColor());
            t.setTextSize(8f);
            t.setGravity(android.view.Gravity.CENTER);
            cell.addView(t, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            cell.setOnClickListener(v -> {
                selectedIcon[0] = null;
                paintIconCells.run();
                View host = box.findViewWithTag("dockColorHost");
                View tint = box.findViewWithTag("dockTintBlock");
                View bgv = box.findViewWithTag("dockBgBlock");
                int vis = View.GONE;
                if (host != null) host.setVisibility(vis);
                if (tint != null) tint.setVisibility(vis);
                if (bgv != null) bgv.setVisibility(vis);
            });
            addDockIconGridCell(iconGrid, currentRow, col, iconCells, cols, cellPx, gap, cell);
        }
        for (String[] pair : DockAppOverrides.SUBSTITUTE_ICONS) {
            final String slug = pair[0];
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            cell.setPadding(cellPad, cellPad, cellPad, cellPad);
            cell.setTag(slug);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            Drawable preview = DockAppOverrides.drawableFor(this, slug, selectedColor[0]);
            if (preview == null && "gwm".equals(slug)) {
                try { preview = getDrawable(R.drawable.ic_gwm); } catch (Exception ignored) {}
            }
            iv.setImageDrawable(preview);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            cell.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            cell.setOnClickListener(v -> {
                selectedIcon[0] = slug;
                paintIconCells.run();
                View host = box.findViewWithTag("dockColorHost");
                View tint = box.findViewWithTag("dockTintBlock");
                View bgv = box.findViewWithTag("dockBgBlock");
                int vis = View.VISIBLE;
                if (host != null) host.setVisibility(vis);
                if (tint != null) tint.setVisibility(vis);
                if (bgv != null) bgv.setVisibility(vis);
            });
            addDockIconGridCell(iconGrid, currentRow, col, iconCells, cols, cellPx, gap, cell);
        }
        paintIconCells.run();

        android.widget.LinearLayout colorHost = new android.widget.LinearLayout(this);
        colorHost.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        colorHost.setTag("dockColorHost");
        colorHost.setPadding(0, Math.round(8 * d), 0, 0);
        box.addView(colorHost);

        android.widget.LinearLayout tintBlock = new android.widget.LinearLayout(this);
        tintBlock.setOrientation(android.widget.LinearLayout.VERTICAL);
        tintBlock.setTag("dockTintBlock");
        android.widget.LinearLayout.LayoutParams tintLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tintLp.rightMargin = Math.round(12 * d);
        colorHost.addView(tintBlock, tintLp);
        android.widget.TextView colorLabel = makeDockPopupKicker("COR", d);
        tintBlock.addView(colorLabel);
        android.widget.LinearLayout colorRow = new android.widget.LinearLayout(this);
        colorRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        tintBlock.addView(colorRow);

        android.widget.LinearLayout bgBlock = new android.widget.LinearLayout(this);
        bgBlock.setOrientation(android.widget.LinearLayout.VERTICAL);
        bgBlock.setTag("dockBgBlock");
        android.widget.LinearLayout.LayoutParams bgLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colorHost.addView(bgBlock, bgLp);
        android.widget.TextView bgLabel = makeDockPopupKicker("FUNDO", d);
        bgBlock.addView(bgLabel);
        android.widget.LinearLayout bgRow = new android.widget.LinearLayout(this);
        bgRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bgBlock.addView(bgRow);

        int colorVis = selectedIcon[0] == null ? View.GONE : View.VISIBLE;
        colorHost.setVisibility(colorVis);
        tintBlock.setVisibility(colorVis);
        bgBlock.setVisibility(colorVis);

        int swatchCount = DockAppOverrides.COLORS.length;
        int swatchGap = Math.round(5 * d);
        int halfInner = Math.max(1, (maxW - 2 * pad - Math.round(12 * d)) / 2);
        int swatchPx = Math.min(Math.round(28 * d),
                (halfInner - (swatchCount - 1) * swatchGap) / swatchCount);
        swatchPx = Math.max(Math.round(20 * d), swatchPx);
        addDockColorSwatches(colorRow, swatchPx, swatchGap, d);
        addDockColorSwatches(bgRow, swatchPx, swatchGap, d);

        Runnable refreshSwatchSel = () -> {
            for (int i = 0; i < colorRow.getChildCount(); i++) {
                View cell = colorRow.getChildAt(i);
                boolean on = selectedColor[0] != null
                        && selectedColor[0].equalsIgnoreCase(String.valueOf(cell.getTag()));
                cell.setAlpha(on ? 1f : 0.5f);
                if (cell instanceof android.widget.FrameLayout) {
                    ((android.widget.FrameLayout) cell).setForeground(
                            on ? new android.graphics.drawable.ColorDrawable(0x66FFFFFF) : null);
                }
            }
            for (int i = 0; i < bgRow.getChildCount(); i++) {
                View cell = bgRow.getChildAt(i);
                boolean on = selectedBg[0] != null
                        && selectedBg[0].equalsIgnoreCase(String.valueOf(cell.getTag()));
                cell.setAlpha(on ? 1f : 0.5f);
                if (cell instanceof android.widget.FrameLayout) {
                    ((android.widget.FrameLayout) cell).setForeground(
                            on ? new android.graphics.drawable.ColorDrawable(0x66FFFFFF) : null);
                }
            }
        };
        for (int i = 0; i < colorRow.getChildCount(); i++) {
            View cell = colorRow.getChildAt(i);
            cell.setOnClickListener(v -> {
                selectedColor[0] = String.valueOf(v.getTag());
                refreshSwatchSel.run();
                paintIconCells.run();
            });
        }
        for (int i = 0; i < bgRow.getChildCount(); i++) {
            View cell = bgRow.getChildAt(i);
            cell.setOnClickListener(v -> {
                selectedBg[0] = String.valueOf(v.getTag());
                refreshSwatchSel.run();
                paintIconCells.run();
            });
        }
        refreshSwatchSel.run();

        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actions.setPadding(0, Math.round(14 * d), 0, 0);
        box.addView(actions);

        actions.addView(makeDockPopupAction("RESET", false, d, () -> {
            if (dockAppOverrides != null) {
                dockAppOverrides.clear(pkg);
                dockAppOverrides.save(getSharedPreferences(PREFS_SHELL, MODE_PRIVATE));
            }
            dismissDockCustomizeSheet();
            rebindDockPackage(pkg);
        }));
        actions.addView(makeDockPopupAction("SAVE", true, d, () -> {
            if (dockAppOverrides == null) {
                dockAppOverrides = new DockAppOverrides(null);
            }
            String name = nameField.getText() != null ? nameField.getText().toString() : "";
            dockAppOverrides.put(pkg, name, selectedIcon[0], selectedColor[0], selectedBg[0]);
            dockAppOverrides.save(getSharedPreferences(PREFS_SHELL, MODE_PRIVATE));
            dismissDockCustomizeSheet();
            rebindDockPackage(pkg);
        }));

        android.widget.PopupWindow popup = new android.widget.PopupWindow(
                scroll, maxW, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        popup.setFocusable(true);
        popup.setOnDismissListener(() -> {
            if (dockCustomizeSheet == popup) dockCustomizeSheet = null;
        });
        dockCustomizeSheet = popup;
        scroll.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED);
        int yOff = -(Math.min(scroll.getMeasuredHeight(), Math.round(520 * d))
                + anchor.getHeight() + Math.round(8 * d));
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int margin = Math.round(12 * d);
        int xOff = 0;
        if (loc[0] + maxW > screenW - margin) {
            xOff = (screenW - margin) - maxW - loc[0];
        }
        if (loc[0] + xOff < margin) {
            xOff = margin - loc[0];
        }
        popup.showAsDropDown(anchor, xOff, yOff);
    }

    /** Re-apply icon/label after a customize save (hub or a scroll-row package). */
    private void rebindDockPackage(String pkg) {
        if (DockAppOverrides.GWM_HUB_PKG.equals(pkg)) {
            bindGwmHub();
            updatePinnedVisibility();
            notifyAppsCatalogChanged();
            return;
        }
        MotionTrailLayout row = dockItemsByPackage.get(pkg);
        if (row != null) {
            PackageManager pm = getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            ResolveInfo info = null;
            List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
            if (apps != null) {
                for (ResolveInfo ri : apps) {
                    if (ri != null && ri.activityInfo != null
                            && pkg.equals(ri.activityInfo.packageName)) {
                        info = ri;
                        break;
                    }
                }
            }
            String label = info != null ? launcherLabel(pm, info) : pinnedLabel(pkg, pm, null);
            Drawable icon = info != null ? iconForLauncherApp(pm, info) : launcherIconForPackage(pkg);
            final String targetPkg = pkg;
            final String targetLabel = label;
            bindDockItem(row, icon, label,
                    v -> launchAppForPackage(targetPkg, targetLabel), targetPkg);
            if (isUserHidden(pkg)) row.setVisibility(View.GONE);
        }
        // Recents may show the same package.
        refreshRecentSlots();
        // MEDIA chip may be showing this package — refresh so a substitute
        // icon/name lands without waiting for the next track change.
        if (pkg != null && pkg.equals(quickMediaPackage)) replayMediaPayload();
        notifyAppsCatalogChanged();
    }

    private void notifyAppsCatalogChanged() {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(
                "try{if(window.__app&&typeof window.__app._invalidateAppsCatalog==='function'){"
                        + "window.__app._invalidateAppsCatalog();}}catch(e){}",
                null));
    }

    /**
     * "Unhide selected" — every package the user has hidden, icon + label, tap
     * to bring it back. Hidden apps keep a GONE item in the strip (see
     * {@link #excludedFromAppRow}), so restoring one is a visibility flip.
     */
    private void showUnhidePicker() {
        if (hiddenPackages.isEmpty()) return;
        dismissUnhidePicker();
        float d = getResources().getDisplayMetrics().density;
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.Map<String, ResolveInfo> byPkg = new java.util.HashMap<>();
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        if (apps != null) {
            for (ResolveInfo info : apps) {
                if (info == null || info.activityInfo == null) continue;
                byPkg.put(info.activityInfo.packageName, info);
            }
        }

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xF2141820);
        box.setElevation(12f * d);
        int pad = Math.round(14 * d);
        box.setPadding(pad, Math.round(10 * d), pad, Math.round(10 * d));

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Hidden apps");
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(14f);
        title.setPadding(Math.round(6 * d), 0, Math.round(6 * d), Math.round(6 * d));
        box.addView(title);

        // Sorted so the list does not reshuffle between openings (hiddenPackages
        // is a HashSet).
        List<String> pkgs = new ArrayList<>(hiddenPackages);
        java.util.Collections.sort(pkgs);
        int iconPx = Math.round(36 * d);
        for (String hidden : pkgs) {
            final String target = hidden;
            ResolveInfo info = byPkg.get(hidden);
            String label = info != null ? pinnedLabel(hidden, pm, info) : hidden;
            Drawable icon = info != null ? iconForLauncherApp(pm, info) : null;

            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(8 * d), Math.round(12 * d), Math.round(8 * d), Math.round(12 * d));
            row.setMinimumHeight(Math.round(52 * d));
            row.setClickable(true);
            row.setFocusable(true);

            android.widget.ImageView iv = new android.widget.ImageView(this);
            android.widget.FrameLayout iconPlate =
                    wrapLauncherIconPlate(iv, iconPx, d, usesDarkIconPlate(hidden));
            android.widget.LinearLayout.LayoutParams plateLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            plateLp.rightMargin = Math.round(10 * d);
            iconPlate.setLayoutParams(plateLp);
            iv.setImageDrawable(normalizeAdaptiveIcon(icon, iconPx));
            row.addView(iconPlate);

            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(label);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(16f);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setMaxWidth(Math.round(240 * d));
            row.addView(tv);

            row.setOnClickListener(v -> {
                dismissUnhidePicker();
                restoreDockPackage(target);
            });
            box.addView(row);
        }

        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(box);

        android.widget.PopupWindow popup = new android.widget.PopupWindow(
                scroller,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        popup.setOnDismissListener(() -> {
            if (unhidePicker == popup) unhidePicker = null;
        });
        unhidePicker = popup;
        View anchor = stripContainer != null ? stripContainer : webView;
        if (anchor == null) {
            unhidePicker = null;
            return;
        }
        // Centred over the shell: the list can outgrow any single icon's anchor,
        // and the ScrollView caps it at the screen rather than the strip band.
        popup.showAtLocation(anchor, android.view.Gravity.CENTER, 0, 0);
    }

    private void dismissUnhidePicker() {
        if (unhidePicker == null) return;
        try {
            unhidePicker.dismiss();
        } catch (Exception ignored) {}
        unhidePicker = null;
    }

    /** Reverse of {@link #removeDockPackage}: drop the hide flag and re-show. */
    private void restoreDockPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (!hiddenPackages.remove(pkg)) return;
        saveHiddenApps();
        updatePinnedVisibility();
        // Rebinds recents and settles app-row visibility from the hide list.
        refreshRecentSlots();
        Log.w(TAG, "Launcher unhidden " + pkg);
        notifyAppsCatalogChanged();
    }

    /** Hub follows the hide list; gap follows the hub. */
    private void updatePinnedVisibility() {
        boolean anyVisible = false;
        for (int i = 0; i < PINNED_PACKAGES.length; i++) {
            if (!pinnedBound[i]) continue;
            if (isUserHidden(PINNED_PACKAGES[i])) continue;
            anyVisible = true;
            break;
        }
        if (gwmHubItem != null) {
            if (anyVisible) {
                gwmHubItem.setVisibility(View.VISIBLE);
                if (launcherRevealed) {
                    gwmHubItem.setAlpha(1f);
                    gwmHubItem.setTranslationX(0f);
                    gwmHubItem.setTrailPx(0f);
                }
            } else {
                gwmHubItem.setVisibility(View.GONE);
            }
        }
    }

    private android.widget.TextView makeDockMenuRow(String title, Runnable action) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.TextView row = new android.widget.TextView(this);
        row.setText(title);
        row.setTextColor(0xFFFFFFFF);
        row.setTextSize(16f);
        row.setMinHeight(Math.round(48 * d));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(10 * d), Math.round(12 * d), Math.round(10 * d), Math.round(12 * d));
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
        if (!persistHide) {
            // Uninstalled, not merely hidden: drop the views outright, or
            // hideRecentDuplicatesFromStrip / updatePinnedVisibility would keep
            // reviving a shortcut with nothing behind it.
            if (row != null) {
                dockItemsByPackage.remove(pkg);
                launcherItems.remove(row);
                if (row.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) row.getParent()).removeView(row);
                }
            }
            for (int i = 0; i < PINNED_PACKAGES.length; i++) {
                if (!PINNED_PACKAGES[i].equals(pkg)) continue;
                pinnedBound[i] = false;
            }
            // Also drop it from the hide list so it stops haunting the unhide picker.
            if (hiddenPackages.remove(pkg)) saveHiddenApps();
            if (windowPackages.remove(pkg)) saveWindowApps();
        }
        updatePinnedVisibility();
        refreshRecentSlots();
        notifyAppsCatalogChanged();
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
        // Permission may have been granted via adb while we were paused; retry.
        mainHandler.removeCallbacks(mediaVizPoll);
        mainHandler.post(mediaVizPoll);
        notifyViewerShellLayout();
        if (desktopHitWanted) {
            DesktopSwitcherHitService.applyFromViewer(this,
                    desktopHitX, desktopHitY, desktopHitW, desktopHitH, true);
        }
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
        DesktopSwitcherHitService.applyFromViewer(this, 0, 0, 0, 0, false);
        dismissAllOverlays();
    }

    @Override
    protected void onDestroy() {
        stopOverlayWatchdog();
        unregisterOverlayTaskListener();
        if (mediaAudioViz != null) {
            mediaAudioViz.release();
            mediaAudioViz = null;
        }
        mainHandler.removeCallbacks(mediaVizPoll);
        mediaNowPlaying.stop();
        dismissDockEditMenu();
        if (projectionPresence != null) projectionPresence.stop();
        if (placeGlance != null) placeGlance.stop();
        // Not stopped: the recorder outlives this activity. An activity
        // recreation (measured on the emulator 2026-09-13) must not reset the
        // open trip; only make sure what it holds is on disk.
        if (tripRecorder != null) tripRecorder.persistSoon();
        if (pinBoundsRunnable != null) mainHandler.removeCallbacks(pinBoundsRunnable);
        if (pinMediaBoundsRunnable != null) mainHandler.removeCallbacks(pinMediaBoundsRunnable);
        if (raiseOverlayRunnable != null) mainHandler.removeCallbacks(raiseOverlayRunnable);
        dismissAppsFabOverlay();
        DesktopSwitcherHitService.applyFromViewer(this, 0, 0, 0, 0, false);
        try {
            unregisterReceiver(telemetryReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(clockEnvironmentReceiver);
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
