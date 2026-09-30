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
import com.neonhud.app.core.module.ModuleManager;
import com.neonhud.app.core.module.ModuleSnapshot;
import com.neonhud.app.core.module.ModuleState;

/**
 * Foreground service that keeps the process (and so the loaded model / running reply) alive while the app is in the
 * background. It stops itself as soon as nothing needs the model: not loaded, nothing importing, no reply running.
 */
public final class ModelService extends Service implements ModuleManager.Listener {

    private static final String CHANNEL = "gemma_runtime";
    private static final int NOTIFICATION_ID = 7;

    private PowerManager.WakeLock wakeLock;
    private boolean foregroundStarted;

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
        App.get(this).modules().addListener(this);
        evaluate(App.get(this).modules().snapshot());
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Gemma runtime", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Gemma 4 E2B")
                .setContentText("Offline AI is running on this phone")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        foregroundStarted = true;
    }

    @Override public void onModuleChanged(ModuleSnapshot s) { evaluate(s); }

    private synchronized void evaluate(ModuleSnapshot s) {
        boolean busy = s.inFlight != null || s.replyActive;
        boolean needed = busy || s.state == ModuleState.LOADED;
        if (busy) acquireWake(); else releaseWake();
        if (!needed && foregroundStarted) {
            foregroundStarted = false;
            App.get(this).modules().removeListener(this);
            releaseWake();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf();
        }
    }

    private void acquireWake() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neonhud:gemma");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(30L * 60 * 1000);   // safety timeout: 30 min
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public void onDestroy() {
        App.get(this).modules().removeListener(this);
        releaseWake();
        super.onDestroy();
    }
}
