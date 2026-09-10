package com.havalh6.viewer;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;

/**
 * Touchable window above the MMI StatusBar. TYPE_APPLICATION_OVERLAY can
 * <em>draw</em> in y=0..60 (Impulse's CPU pill does) but its layer is 21000 vs
 * StatusBar 211000, so the bar still eats every tap. Accessibility overlays
 * sit above that bar; this service's only job is a transparent hit proxy that
 * forwards MotionEvents into {@link MainActivity}'s WebView.
 */
public final class DesktopSwitcherHitService extends AccessibilityService {
    private static final String TAG = "H6HitProxy";
    private static volatile DesktopSwitcherHitService instance;
    private static volatile WeakReference<MainActivity> host = new WeakReference<>(null);
    private static int pendingX;
    private static int pendingY;
    private static int pendingW;
    private static int pendingH;
    private static boolean pendingWanted;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View proxy;
    private WindowManager.LayoutParams lp;
    private boolean attached;

    static void applyFromViewer(MainActivity activity, int x, int y, int w, int h,
            boolean visible) {
        host = new WeakReference<>(activity);
        pendingX = x;
        pendingY = y;
        pendingW = w;
        pendingH = h;
        pendingWanted = visible;
        DesktopSwitcherHitService svc = instance;
        if (svc != null) svc.handler.post(svc::syncProxy);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        handler.post(this::syncProxy);
        Log.w(TAG, "Desktop switcher hit service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        dismissProxy();
        super.onDestroy();
    }

    private void syncProxy() {
        if (!pendingWanted) {
            dismissProxy();
            return;
        }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        if (proxy == null) {
            View v = new View(this);
            v.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            v.setOnTouchListener((view, ev) -> {
                MainActivity activity = host.get();
                return activity != null && activity.dispatchDesktopSwitcherHit(ev);
            });
            proxy = v;
        }
        if (lp == null) {
            lp = new WindowManager.LayoutParams(
                    pendingW, pendingH,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
        }
        lp.x = pendingX;
        lp.y = pendingY;
        lp.width = pendingW;
        lp.height = pendingH;
        try {
            if (attached) {
                wm.updateViewLayout(proxy, lp);
            } else {
                wm.addView(proxy, lp);
                attached = true;
                Log.w(TAG, "Hit proxy overlay " + pendingX + "," + pendingY
                        + " " + pendingW + "x" + pendingH);
            }
        } catch (Exception e) {
            Log.w(TAG, "Hit proxy overlay failed", e);
            attached = false;
        }
    }

    private void dismissProxy() {
        if (!attached || proxy == null) {
            attached = false;
            return;
        }
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) wm.removeView(proxy);
        } catch (Exception ignored) {}
        attached = false;
    }
}
