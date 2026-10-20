package com.eiclaude.ritmo;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/**
 * Fim do foco ou da pausa: um alarme exato acorda o celular e mostra uma
 * notificação com som, mesmo com a tela apagada. Com o app aberto na tela,
 * quem avisa é a própria página (som e vibração), então aqui não faz nada.
 */
public class TimerReceiver extends BroadcastReceiver {

    static final String CHANNEL = "ritmo_timer";
    static final int NOTIFICATION_ID = 42;

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Timer de foco", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Avisa quando o foco ou a pausa terminam");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 300, 150, 300});
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs);
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent alarmIntent(Context c, String title, String text) {
        Intent i = new Intent(c, TimerReceiver.class);
        i.putExtra("title", title);
        i.putExtra("text", text);
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c, long at, String title, String text) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent p = alarmIntent(c, title, text);
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
                return;
            }
        } catch (SecurityException ignored) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
    }

    static void cancel(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(alarmIntent(c, "", ""));
        clear(c);
    }

    static void clear(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (MainActivity.visible) return;
        ensureChannel(c);
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 8, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title != null && !title.isEmpty() ? title : "Feito")
                .setContentText(text != null ? text : "")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try {
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        } catch (SecurityException ignored) {
        }
    }
}
