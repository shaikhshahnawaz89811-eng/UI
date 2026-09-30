package com.neonhud.app.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.neonhud.app.MainActivity;
import com.neonhud.app.R;
import com.neonhud.app.core.coder.CoderSpec;
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleSnapshot;
import com.neonhud.app.core.module.ModuleState;

/**
 * Foreground service that keeps the process (and so the loaded models / a running reply) alive while the app is in the
 * background. It watches BOTH modules (Gemma and the coder) and stops itself as soon as neither needs it:
 * nothing loaded, nothing importing, no reply running.
 */
public final class ModelService extends Service implements ModuleManager.Listener {

    private static final String CHANNEL = "gemma_runtime";     // id kept so an existing channel is reused
    private static final int NOTIFICATION_ID = 7;

    private PowerManager.WakeLock wakeLock;
    private boolean foregroundStarted;
    private String shownText = "";

    /** Call from the UI AFTER an action was accepted (the app is in the foreground then, which Android requires). */
    public static void ensureRunning(Context ctx) {
        Intent i = new Intent(ctx, ModelService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (RuntimeException ignored) {
            // e.g. started while the app is not allowed to: the work still runs while the process lives
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        App app = App.get(this);
        app.modules().addListener(this);
        app.coderModules().addListener(this);
        evaluate();
        return START_NOT_STICKY;
    }

    private String describe() {
        App app = App.get(this);
        boolean gemma = app.modules().state() == ModuleState.LOADED;
        boolean coder = app.coderModules().state() == ModuleState.LOADED;
        if (gemma && coder) return "Gemma 4 E2B + " + CoderSpec.SHORT_NAME + " are running offline";
        if (coder) return CoderSpec.SHORT_NAME + " is running offline";
        if (gemma) return "Gemma 4 E2B is running offline";
        return "Offline AI is working on this phone";
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Neon HUD")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Offline AI runtime", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        shownText = describe();
        Notification n = buildNotification(shownText);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        foregroundStarted = true;
    }

    @Override public void onModuleChanged(ModuleSnapshot s) { evaluate(); }

    private synchronized void evaluate() {
        App app = App.get(this);
        ModuleSnapshot g = app.modules().snapshot();
        ModuleSnapshot c = app.coderModules().snapshot();
        boolean busy = g.inFlight != null || g.replyActive || c.inFlight != null || c.replyActive;
        boolean needed = busy || g.state == ModuleState.LOADED || c.state == ModuleState.LOADED;
        if (busy) acquireWake(); else releaseWake();
        if (!needed && foregroundStarted) {
            foregroundStarted = false;
            app.modules().removeListener(this);
            app.coderModules().removeListener(this);
            releaseWake();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf();
            return;
        }
        if (needed && foregroundStarted) {
            String text = describe();
            if (!text.equals(shownText)) {          // a module was loaded / unloaded: keep the notification text true
                shownText = text;
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    try { nm.notify(NOTIFICATION_ID, buildNotification(text)); } catch (RuntimeException ignored) { }
                }
            }
        }
    }

    private void acquireWake() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neonhud:ai");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(30L * 60 * 1000);   // safety timeout: 30 min
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public void onDestroy() {
        App app = App.get(this);
        app.modules().removeListener(this);
        app.coderModules().removeListener(this);
        releaseWake();
        super.onDestroy();
    }
}
