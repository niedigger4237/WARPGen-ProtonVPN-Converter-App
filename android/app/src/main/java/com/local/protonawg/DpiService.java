package com.local.protonawg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/** Служба переднего плана: показывает уведомление, пока работает ByeDPI, и не даёт системе убить процесс. */
public class DpiService extends Service {
    static final String CHANNEL = "byedpi";
    static final int NOTIFICATION_ID = 4711;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            NativeActions.killDpiStatic();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        String text = intent != null ? intent.getStringExtra("text") : null;
        if (text == null) {
            text = "";
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ByeDPI", NotificationManager.IMPORTANCE_LOW));
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ByeDPI запущен")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 0, open, piFlags));
        }
        Intent stop = new Intent(this, DpiService.class).setAction("STOP");
        b.addAction(0, "Остановить", PendingIntent.getService(this, 1, stop, piFlags));

        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, 0x40000000); // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        return START_NOT_STICKY;
    }
}
