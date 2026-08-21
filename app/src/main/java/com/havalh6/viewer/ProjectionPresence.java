package com.havalh6.viewer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * Whether a phone is actually linked for Android Auto or CarPlay.
 *
 * <p>Both stacks are always installed on the MMI, so package presence is not
 * enough. Impulse's projection layer already talks to these binders; we reuse
 * the same link-status transactions and treat a live now-playing track as a
 * fallback when the transact fails.
 */
final class ProjectionPresence {
    enum Kind { NONE, ANDROID_AUTO, CARPLAY }

    interface Listener {
        void onProjectionChanged(Kind kind);
    }

    private static final String TAG = "H6Viewer";

    private static final String AA_APP = "com.ts.androidauto.app";
    private static final String AA_ACTIVITY = "com.ts.androidauto.app.display.AapActivity";
    private static final String AA_SERVICE_PACKAGE = "com.ts.androidauto.projectionservice";
    private static final String AA_SERVICE_CLASS =
            "com.ts.androidauto.projectionservice.AndroidAutoService";
    private static final String AA_SERVICE_ACTION = "com.ts.androidauto.action.AndroidAutoService";
    private static final String AA_DESCRIPTOR = "com.ts.androidauto.sdk.aidl.LinkCommand";
    private static final int AA_TX_GET_LINK_STATUS = 0x15;

    private static final String CP_APP = "com.ts.carplay.app";
    private static final String CP_ACTIVITY =
            "com.ts.carplay.app.ui.display.view.CarPlayDisplayActivity";
    private static final String CP_HOST = "com.ts.carplay";
    private static final String CP_SERVICE_CLASS = "com.ts.carplay.CarPlayService";
    private static final String CP_DESCRIPTOR = "com.ts.carplay.common.aidl.ICarPlayService";
    private static final int CP_TX_GET_LINK_STATUS = 29;
    private static final int CP_LINK_ACTIVATED = 2;

    private static final long POLL_MS = 2000;

    private final Context app;
    private final Handler main;
    private final MediaNowPlaying media;
    private Listener listener;
    private Handler worker;
    private ServiceConnection aaConnection;
    private ServiceConnection cpConnection;
    private volatile IBinder aaBinder;
    private volatile IBinder cpBinder;
    private volatile Kind lastKind = Kind.NONE;
    private boolean started;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Kind next = detect();
            if (next != lastKind) {
                lastKind = next;
                Log.w(TAG, "Projection slot " + next);
                final Kind publish = next;
                main.post(() -> {
                    if (listener != null) listener.onProjectionChanged(publish);
                });
            }
            if (worker != null) worker.postDelayed(this, POLL_MS);
        }
    };

    ProjectionPresence(Context context, Handler mainHandler, MediaNowPlaying media) {
        this.app = context.getApplicationContext();
        this.main = mainHandler;
        this.media = media;
    }

    void start(Listener listener) {
        if (started) return;
        started = true;
        this.listener = listener;
        HandlerThread t = new HandlerThread("H6Projection");
        t.start();
        worker = new Handler(t.getLooper());
        worker.post(tick);
    }

    void stop() {
        started = false;
        if (worker != null) worker.removeCallbacks(tick);
        unbind(aaConnection);
        unbind(cpConnection);
        aaConnection = null;
        cpConnection = null;
        aaBinder = null;
        cpBinder = null;
    }

    Kind current() {
        return lastKind;
    }

    static boolean isProjectionPackage(String packageName) {
        if (packageName == null) return false;
        String p = packageName.toLowerCase();
        return p.equals(AA_APP)
                || p.equals("com.ts.androidauto")
                || p.equals(AA_SERVICE_PACKAGE)
                || p.equals(CP_APP)
                || p.equals(CP_HOST)
                || p.contains("androidauto")
                || p.contains("carplay")
                || p.contains("projection.gearhead");
    }

    static ComponentName componentFor(Kind kind) {
        if (kind == Kind.ANDROID_AUTO) return new ComponentName(AA_APP, AA_ACTIVITY);
        if (kind == Kind.CARPLAY) return new ComponentName(CP_APP, CP_ACTIVITY);
        return null;
    }

    Drawable iconFor(Kind kind) {
        if (kind == Kind.NONE) return null;
        // Display apps ship a car glyph; the projection services have no launcher
        // icon (generic Android). Use Impulse's branded defaults instead.
        int res = kind == Kind.ANDROID_AUTO
                ? R.drawable.ic_android_auto_default
                : R.drawable.ic_carplay_default;
        try {
            Drawable branded = app.getDrawable(res);
            if (branded != null) return branded;
        } catch (Throwable ignored) {
        }
        int fallback = kind == Kind.ANDROID_AUTO ? R.drawable.ic_android_auto : R.drawable.ic_carplay;
        try {
            return app.getDrawable(fallback);
        } catch (Throwable ignored) {
            return null;
        }
    }

    String labelFor(Kind kind) {
        if (kind == Kind.ANDROID_AUTO) return "Android Auto";
        if (kind == Kind.CARPLAY) return "CarPlay";
        return "";
    }

    void launch(Kind kind) {
        ComponentName cn = componentFor(kind);
        if (cn == null) return;
        try {
            Intent intent = new Intent();
            intent.setComponent(cn);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            app.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Projection launch failed for " + kind, e);
        }
    }

    private Kind detect() {
        boolean carPlay = carPlayLinked() || (media != null && media.hasCarPlayTrack());
        boolean androidAuto = androidAutoLinked() || (media != null && media.hasAndroidAutoTrack());
        if (carPlay && androidAuto) {
            if (media != null && media.hasCarPlayTrack() && !media.hasAndroidAutoTrack()) {
                return Kind.CARPLAY;
            }
            if (media != null && media.hasAndroidAutoTrack() && !media.hasCarPlayTrack()) {
                return Kind.ANDROID_AUTO;
            }
            return Kind.CARPLAY;
        }
        if (carPlay) return Kind.CARPLAY;
        if (androidAuto) return Kind.ANDROID_AUTO;
        return Kind.NONE;
    }

    private boolean androidAutoLinked() {
        IBinder binder = ensureAaBinder();
        Integer status = readInt(binder, AA_DESCRIPTOR, AA_TX_GET_LINK_STATUS);
        // Impulse: 3 ACTIVATED, 7 SHOW_VIDEO, 8 AAP_FRX.
        return status != null && (status == 3 || status == 7 || status == 8);
    }

    private boolean carPlayLinked() {
        IBinder binder = ensureCpBinder();
        Integer status = readInt(binder, CP_DESCRIPTOR, CP_TX_GET_LINK_STATUS);
        return status != null && status == CP_LINK_ACTIVATED;
    }

    private IBinder ensureAaBinder() {
        IBinder live = aaBinder;
        if (live != null && live.isBinderAlive()) return live;
        if (aaConnection != null) return aaBinder;
        aaConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                aaBinder = service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                aaBinder = null;
            }
        };
        try {
            Intent intent = new Intent(AA_SERVICE_ACTION);
            intent.setComponent(new ComponentName(AA_SERVICE_PACKAGE, AA_SERVICE_CLASS));
            if (!app.bindService(intent, aaConnection, Context.BIND_AUTO_CREATE)) {
                unbind(aaConnection);
                aaConnection = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Android Auto bind failed", t);
            aaConnection = null;
        }
        return aaBinder;
    }

    private IBinder ensureCpBinder() {
        IBinder live = cpBinder;
        if (live != null && live.isBinderAlive()) return live;
        if (cpConnection != null) return cpBinder;
        cpConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                cpBinder = service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                cpBinder = null;
            }
        };
        try {
            Intent intent = new Intent().setComponent(new ComponentName(CP_HOST, CP_SERVICE_CLASS));
            if (!app.bindService(intent, cpConnection, Context.BIND_AUTO_CREATE)) {
                unbind(cpConnection);
                cpConnection = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "CarPlay bind failed", t);
            cpConnection = null;
        }
        return cpBinder;
    }

    private void unbind(ServiceConnection connection) {
        if (connection == null) return;
        try {
            app.unbindService(connection);
        } catch (Throwable ignored) {}
    }

    private static Integer readInt(IBinder binder, String descriptor, int code) {
        if (binder == null || !binder.isBinderAlive()) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (!binder.transact(code, data, reply, 0)) return null;
            try {
                reply.readException();
            } catch (Throwable ignored) {}
            return reply.readInt();
        } catch (Throwable t) {
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
