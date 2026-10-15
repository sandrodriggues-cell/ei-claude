package com.eiclaude.snoop;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;

/** Avisos do Snoop no relógio (vibram e abrem a conversa ao tocar). */
final class Notifier {

    static final String CHANNEL = "avisos";
    static final String CHANNEL_REMINDERS = "lembretes";
    static final int ID_IDLE = 11;
    static final int ID_PRAISE = 12;
    static final int ID_GREETING = 13;
    static final int ID_MISS = 14;
    static final int ID_TEST = 15;
    static final int ID_SHIFT = 16;
    static final int ID_HUNGRY = 17;

    private Notifier() {
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Avisos do Snoop", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Quando o Snoop chama você: bom dia, hora de se mexer, turno, fome, saudade.");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 120, 90, 120});
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_REMINDERS) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_REMINDERS, "Lembretes", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Os lembretes que você pediu ao Snoop por voz.");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 250, 120, 250, 120, 250});
            nm.createNotificationChannel(ch);
        }
    }

    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    /** Lembrete pedido por voz (canal próprio, vibração mais forte). */
    static boolean showReminder(Context c, int id, String text) {
        return post(c, CHANNEL_REMINDERS, id, text, "animado");
    }

    /** Mostra o aviso; ao tocar, o Snoop fala a frase e já ouve a sua resposta. */
    static boolean show(Context c, int id, String text, String mood) {
        return post(c, CHANNEL, id, text, mood);
    }

    private static boolean post(Context c, String channel, int id, String text, String mood) {
        ensureChannel(c);
        if (!allowed(c)) return false;
        Intent open = new Intent(c, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SAY, text)
                .putExtra(MainActivity.EXTRA_MOOD, mood)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_paw)
                .setContentTitle("Snoop")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setColor(0xFFD88C43)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_paw), "Conversar", pi).build());
        Bitmap face = face(c);
        if (face != null) b.setLargeIcon(face);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        nm.notify(id, b.build());
        return true;
    }

    private static Bitmap face(Context c) {
        try {
            Drawable d = c.getDrawable(R.drawable.snoop_face);
            if (d == null) return null;
            int size = Math.round(96 * c.getResources().getDisplayMetrics().density);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
