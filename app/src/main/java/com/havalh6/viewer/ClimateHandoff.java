package com.havalh6.viewer;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * Asks Impulse to hold the car's own A/C popup back, so this app can show its own.
 *
 * Impulse does the actual work: while this lease is held it keeps {@code com.beantechs.hvac}
 * disabled. Measured on the car 2026-09-22, that stops the OEM popup without touching the A/C
 * itself — the physical buttons still actuate and {@code car.hvac.panel_display_notify} still
 * fires, which is the trigger the page uses to raise its own popup.
 *
 * Two things this class is careful about:
 *
 * <ul>
 *   <li><b>Never assume the lease was granted.</b> Impulse refuses it when the owner has not
 *       enabled the hand-off, when this build is too old to declare it, or when the signer is not
 *       accepted — and the owner can switch it off while we are bound. The popup must only be ours
 *       while {@link #isActive()} is true, or both popups would appear at once.</li>
 *   <li><b>Give Impulse a way to notice we died.</b> {@link #token} is handed over and Impulse
 *       links to its death, so a crash of this process restores the car's A/C screen without
 *       anything polling. That is why it is a plain {@link Binder} owned by this process.</li>
 * </ul>
 */
final class ClimateHandoff {

    private static final String TAG = "ClimateHandoff";

    private static final String IMPULSE_PACKAGE = "br.com.redesurftank.havalshisuku";
    private static final String ACTION_CLIMATE_BRIDGE =
            "br.com.redesurftank.havalshisuku.ACTION_CLIMATE_BRIDGE";

    /** Must match ViewerClimateBridgeService on the Impulse side. */
    private static final int MSG_TAKE_CLIMATE_CONTROL = 1;
    private static final int MSG_RELEASE_CLIMATE_CONTROL = 2;
    private static final int MSG_CLIMATE_CONTROL_STATE = 3;
    private static final String KEY_TOKEN = "token";
    private static final String EXTRA_CALLER = "caller";

    interface Listener {
        /** Called on the main thread whenever the OEM popup suppression starts or stops. */
        void onClimateHandoffChanged(boolean active);
    }

    private final Context context;
    private final PendingIntent callerToken;
    private final Listener listener;

    /** Handed to Impulse purely so it can watch this process die. */
    private final Binder token = new Binder();

    private final Messenger replyTo = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what != MSG_CLIMATE_CONTROL_STATE) return false;
        setActive(message.arg1 == 1);
        return true;
    }));

    private Messenger impulse;
    private boolean bound;
    private boolean active;

    ClimateHandoff(Context context, PendingIntent callerToken, Listener listener) {
        this.context = context.getApplicationContext();
        this.callerToken = callerToken;
        this.listener = listener;
    }

    /** True while Impulse is really holding the OEM A/C app back for us. */
    boolean isActive() {
        return active;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            impulse = new Messenger(service);
            requestControl();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Impulse died or is being upgraded. Its own recovery puts the A/C app back; we simply
            // stop believing we own the popup. Android re-binds and onServiceConnected runs again.
            impulse = null;
            setActive(false);
        }
    };

    void start() {
        if (bound) return;
        Intent intent = new Intent(ACTION_CLIMATE_BRIDGE).setPackage(IMPULSE_PACKAGE);
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            bound = false;
            Log.w(TAG, "bindService failed (" + e.getClass().getSimpleName() + ")");
        }
        if (!bound) {
            // No Impulse on this car, or a build without the bridge. The car keeps its own popup.
            Log.w(TAG, "Impulse climate bridge unavailable; leaving the OEM A/C popup alone");
            setActive(false);
        }
    }

    void stop() {
        if (impulse != null) {
            try {
                impulse.send(Message.obtain(null, MSG_RELEASE_CLIMATE_CONTROL));
            } catch (RemoteException e) {
                Log.w(TAG, "Could not release the climate lease: " + e.getMessage());
            }
        }
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (Exception e) {
                Log.w(TAG, "unbindService failed (" + e.getClass().getSimpleName() + ")");
            }
            bound = false;
        }
        impulse = null;
        setActive(false);
    }

    private void requestControl() {
        if (impulse == null) return;
        Message message = Message.obtain(null, MSG_TAKE_CLIMATE_CONTROL);
        Bundle data = new Bundle();
        data.putParcelable(EXTRA_CALLER, callerToken);
        data.putBinder(KEY_TOKEN, token);
        message.setData(data);
        message.replyTo = replyTo;
        try {
            impulse.send(message);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not request the climate lease: " + e.getMessage());
            setActive(false);
        }
    }

    private void setActive(boolean value) {
        if (active == value) return;
        active = value;
        Log.w(TAG, "Climate hand-off " + (value ? "ACTIVE (our popup)" : "off (the car's popup)"));
        if (listener != null) listener.onClimateHandoffChanged(value);
    }
}
