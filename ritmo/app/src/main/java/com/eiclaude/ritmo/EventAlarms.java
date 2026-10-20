package com.eiclaude.ritmo;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Alarmes e lembretes do Feito: eventos da agenda, lembretes de tarefas e de hábitos.
 *
 * A página manda a lista dos próximos avisos (setAll). Aqui fica guardada e
 * só o próximo aviso é agendado no Android, como um despertador. Quando ele
 * toca, os avisos vencidos são mostrados e o seguinte é agendado.
 *
 * Modo "alarm": notificação de alarme que toca sem parar (como uma ligação)
 * e abre a tela cheia por cima do bloqueio. Modo "notif": notificação comum.
 */
final class EventAlarms {

    static final String CH_ALARM = "ritmo_alarme_evento";
    static final String CH_NOTIF = "ritmo_aviso_evento";
    static final String CH_MISSED = "ritmo_evento_perdido";

    private static final String PREFS = "ritmo_alarmes";
    private static final long RING_MS = 2 * 60 * 1000L;
    private static final long LATE_OK_MS = 10 * 60 * 1000L;

    private EventAlarms() {
    }

    // ------------------------------------------------------------------ canais

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ALARM, "Alarmes", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Toca como um despertador na hora de um evento ou lembrete");
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ch.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 900, 500, 900, 500, 900});
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_NOTIF) == null) {
            NotificationChannel ch = new NotificationChannel(CH_NOTIF, "Avisos e lembretes", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Notificação simples na hora de um evento, tarefa ou hábito");
            ch.enableVibration(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_MISSED) == null) {
            NotificationChannel ch = new NotificationChannel(CH_MISSED, "Alarmes sem resposta", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Fica na barra quando o alarme tocou e ninguém respondeu");
            nm.createNotificationChannel(ch);
        }
    }

    // ------------------------------------------------------------------ dados

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray array(Context c, String name) {
        try {
            return new JSONArray(prefs(c).getString(name, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONObject fired(Context c) {
        try {
            return new JSONObject(prefs(c).getString("fired", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Lista nova vinda da página (os próximos avisos dos eventos). */
    static void setAll(Context c, String json) {
        JSONArray list;
        try {
            list = new JSONArray(json);
        } catch (Exception e) {
            return;
        }
        prefs(c).edit().putString("list", list.toString()).apply();
        scheduleNext(c);
    }

    private static List<JSONObject> entries(Context c) {
        List<JSONObject> out = new ArrayList<>();
        for (String name : new String[]{"list", "extra"}) {
            JSONArray a = array(c, name);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optString("k").length() > 0 && o.optLong("at") > 0) out.add(o);
            }
        }
        return out;
    }

    static JSONObject find(Context c, String key) {
        if (key == null) return null;
        for (JSONObject o : entries(c)) if (key.equals(o.optString("k"))) return o;
        return null;
    }

    private static void markFired(Context c, String key, long at) {
        JSONObject f = fired(c);
        long limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        Iterator<String> it = f.keys();
        List<String> old = new ArrayList<>();
        while (it.hasNext()) {
            String k = it.next();
            if (f.optLong(k) < limit) old.add(k);
        }
        for (String k : old) f.remove(k);
        try {
            f.put(key, at);
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("fired", f.toString()).commit();
    }

    /** Aviso extra criado aqui mesmo (adiar ou testar). */
    private static void addExtra(Context c, JSONObject o) {
        JSONArray a = array(c, "extra"), keep = new JSONArray();
        long limit = System.currentTimeMillis() - 3600 * 1000L;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && x.optLong("at") > limit) keep.put(x);
        }
        keep.put(o);
        prefs(c).edit().putString("extra", keep.toString()).commit();
    }

    // ------------------------------------------------------------------ agendamento

    private static PendingIntent fireIntent(Context c) {
        Intent i = new Intent(c, EventAlarmReceiver.class).setAction(EventAlarmReceiver.ACTION_FIRE);
        return PendingIntent.getBroadcast(c, 100, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Agenda o próximo aviso que ainda não tocou. */
    static void scheduleNext(Context c) {
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        JSONObject f = fired(c);
        for (JSONObject o : entries(c)) {
            long at = o.optLong("at");
            if (f.has(o.optString("k")) || at < now - LATE_OK_MS) continue;
            if (at < next) next = at;
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = fireIntent(c);
        if (next == Long.MAX_VALUE) {
            am.cancel(pi);
            return;
        }
        long when = Math.max(next, now + 1000);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                // Como um despertador: o jeito mais confiável de tocar na hora, mesmo em repouso.
                PendingIntent show = PendingIntent.getActivity(c, 101, new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), pi);
            }
        } catch (Throwable t) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } catch (Throwable t2) {
                am.set(AlarmManager.RTC_WAKEUP, when, pi);
            }
        }
    }

    /** Hora do alarme: mostra todos os avisos vencidos e agenda o seguinte. */
    static void fireDue(Context c) {
        long now = System.currentTimeMillis();
        JSONObject f = fired(c);
        for (JSONObject o : entries(c)) {
            long at = o.optLong("at");
            String k = o.optString("k");
            if (f.has(k) || at > now + 30000 || at < now - LATE_OK_MS) continue;
            markFired(c, k, at);
            show(c, o);
        }
        scheduleNext(c);
    }

    // ------------------------------------------------------------------ avisos

    static int notifId(String key) {
        return 2000 + (key.hashCode() & 0x3fffffff) % 100000;
    }

    static boolean fullScreenAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try {
            return nm != null && nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            return false;
        }
    }

    private static PendingIntent broadcast(Context c, String action, String key, int code) {
        Intent i = new Intent(c, EventAlarmReceiver.class).setAction(action)
                .setData(Uri.parse("ritmo://aviso/" + Uri.encode(key)))
                .putExtra(EventAlarmReceiver.EXTRA_KEY, key);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Intent screenIntent(Context c, JSONObject o) {
        return new Intent(c, AlarmActivity.class)
                .setData(Uri.parse("ritmo://alarme/" + Uri.encode(o.optString("k"))))
                .putExtra(AlarmActivity.EXTRA_KEY, o.optString("k"))
                .putExtra(AlarmActivity.EXTRA_TITLE, o.optString("t", "Evento"))
                .putExtra(AlarmActivity.EXTRA_SUB, o.optString("s"))
                .putExtra(AlarmActivity.EXTRA_START, o.optString("start"))
                .putExtra(AlarmActivity.EXTRA_LEAD, o.optInt("lead", 0))
                .putExtra(AlarmActivity.EXTRA_HEAD, o.optString("h"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    }

    private static String headline(JSONObject o) {
        int lead = o.optInt("lead", 0);
        String start = o.optString("start");
        String head = o.optString("h");
        if (head.length() > 0) return start.length() > 0 ? head + ", " + start : head;
        if (lead > 0 && start.length() > 0) return "Começa às " + start + " (em " + lead + " min)";
        return start.length() > 0 ? "Começando agora · " + start : "Começando agora";
    }

    static void show(Context c, JSONObject o) {
        ensureChannels(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String key = o.optString("k"), title = o.optString("t", "Evento"), sub = o.optString("s");
        int id = notifId(key);
        String text = headline(o) + (sub.length() > 0 ? "\n" + sub : "");
        PendingIntent openApp = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (!"alarm".equals(o.optString("m"))) {
            Notification n = new Notification.Builder(c, CH_NOTIF)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setColor(0xFFF2A417)
                    .setContentTitle(title)
                    .setContentText(headline(o))
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_EVENT)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setContentIntent(openApp)
                    .build();
            notify(nm, id, n);
            return;
        }

        PendingIntent full = PendingIntent.getActivity(c, id, screenIntent(c, o),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFF2A417)
                .setContentTitle(title)
                .setContentText(headline(o))
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(full)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Adiar 5 min",
                        broadcast(c, EventAlarmReceiver.ACTION_SNOOZE, key, id + 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Dispensar",
                        broadcast(c, EventAlarmReceiver.ACTION_DISMISS, key, id + 2)).build());
        if (fullScreenAllowed(c)) b.setFullScreenIntent(full, true);
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;
        notify(nm, id, n);

        // Depois de 2 minutos sem resposta, o som para e fica um aviso quieto na barra.
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) {
            PendingIntent stop = broadcast(c, EventAlarmReceiver.ACTION_SILENCE, key, id + 3);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RING_MS, stop);
            } catch (Throwable t) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RING_MS, stop);
            }
        }
    }

    private static void notify(NotificationManager nm, int id, Notification n) {
        try {
            nm.notify(id, n);
        } catch (SecurityException ignored) {
        }
    }

    private static void stopRinging(Context c, String key) {
        int id = notifId(key);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(broadcast(c, EventAlarmReceiver.ACTION_SILENCE, key, id + 3));
    }

    static void dismiss(Context c, String key) {
        if (key == null) return;
        stopRinging(c, key);
        AlarmActivity.closeIfShowing(key);
    }

    static void snooze(Context c, String key, int minutes) {
        if (key == null) return;
        JSONObject o = find(c, key);
        stopRinging(c, key);
        AlarmActivity.closeIfShowing(key);
        if (o == null) return;
        try {
            long at = System.currentTimeMillis() + minutes * 60000L;
            JSONObject n = new JSONObject(o.toString());
            String base = key.contains("~") ? key.substring(0, key.indexOf('~')) : key;
            n.put("k", base + "~" + at);
            n.put("at", at);
            n.put("lead", 0);
            n.put("m", "alarm");
            addExtra(c, n);
        } catch (Exception ignored) {
        }
        scheduleNext(c);
    }

    /** Ninguém respondeu: troca o alarme por um aviso quieto. */
    static void silence(Context c, String key) {
        if (key == null) return;
        int id = notifId(key);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        boolean ringing = false;
        try {
            for (StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() == id) ringing = true;
        } catch (Throwable ignored) {
        }
        AlarmActivity.closeIfShowing(key);
        if (!ringing) return;
        nm.cancel(id);
        ensureChannels(c);
        JSONObject o = find(c, key);
        String title = o != null ? o.optString("t", "Evento") : "Evento";
        String start = o != null ? o.optString("start") : "";
        PendingIntent openApp = PendingIntent.getActivity(c, id, new Intent(c, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH_MISSED)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFFF2A417)
                .setContentTitle(title)
                .setContentText(start.length() > 0 ? "Alarme das " + start + " sem resposta" : "Alarme sem resposta")
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build();
        notify(nm, id, n);
    }

    /** Alarme de teste em 5 segundos. */
    static void test(Context c) {
        try {
            long at = System.currentTimeMillis() + 5000;
            JSONObject o = new JSONObject();
            o.put("k", "teste~" + at);
            o.put("at", at);
            o.put("t", "Teste do alarme");
            o.put("s", "Se você está vendo esta tela, os alarmes do Feito estão funcionando.");
            o.put("start", "");
            o.put("lead", 0);
            o.put("m", "alarm");
            addExtra(c, o);
        } catch (Exception ignored) {
        }
        scheduleNext(c);
    }

    // ------------------------------------------------------------------ permissões

    static String status(Context c) {
        JSONObject s = new JSONObject();
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            boolean notif = nm != null && nm.areNotificationsEnabled();
            NotificationChannel ch = nm != null ? nm.getNotificationChannel(CH_ALARM) : null;
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) notif = false;
            AlarmManager am = c.getSystemService(AlarmManager.class);
            boolean exact = Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms());
            PowerManager pm = c.getSystemService(PowerManager.class);
            boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
            s.put("notif", notif);
            s.put("full", fullScreenAllowed(c));
            s.put("exact", exact);
            s.put("battery", battery);
        } catch (Exception ignored) {
        }
        return s.toString();
    }
}
