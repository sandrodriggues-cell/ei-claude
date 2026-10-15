package com.eiclaude.snoop;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lembretes pedidos por voz ("Snoop, me lembra de tomar água às 15h"). */
final class Reminders {

    static final String ACTION_FIRE = "com.eiclaude.snoop.LEMBRETE";
    static final String EXTRA_ID = "id";
    private static final int MAX = 30;
    private static final Object LOCK = new Object();
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    static final class Item {
        long id;
        long at;
        String text;
        boolean daily;
    }

    private Reminders() {
    }

    static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            String raw = Prefs.getString(c, "lembretes");
            JSONArray a = new JSONArray(raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getLong("id");
                it.at = o.getLong("em");
                it.text = o.optString("texto", "");
                it.daily = o.optBoolean("diario", false);
                out.add(it);
            }
        } catch (Exception ignored) {
            // lista vazia
        }
        out.sort((x, y) -> Long.compare(x.at, y.at));
        return out;
    }

    private static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        for (Item it : items) {
            try {
                a.put(new JSONObject().put("id", it.id).put("em", it.at).put("texto", it.text).put("diario", it.daily));
            } catch (Exception ignored) {
                // item inválido
            }
        }
        Prefs.putString(c, "lembretes", a.toString());
    }

    /** Lê "AAAA-MM-DD HH:MM" (ou com T no meio); -1 se não der. */
    static long parseWhen(String s) {
        if (s == null) return -1;
        String t = s.trim().replace('T', ' ');
        if (t.length() > 16) t = t.substring(0, 16);
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
            f.setLenient(false);
            Date d = f.parse(t);
            return d == null ? -1 : d.getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Marca um lembrete; devolve null se o horário não servir. */
    static Item add(Context c, long at, String text, boolean daily) {
        long now = System.currentTimeMillis();
        if (at <= 0 || text == null || text.trim().isEmpty()) return null;
        if (at <= now) {
            if (!daily) return null;
            while (at <= now) at += 24L * 60 * 60 * 1000;
        }
        Item it = new Item();
        it.id = now;
        it.at = at;
        it.text = text.trim();
        it.daily = daily;
        synchronized (LOCK) {
            List<Item> items = list(c);
            items.add(it);
            while (items.size() > MAX) {
                cancelAlarm(c, items.get(0));
                items.remove(0);
            }
            save(c, items);
        }
        schedule(c, it);
        return it;
    }

    /** Cancela os lembretes cujo texto combina com a palavra-chave. Devolve quantos. */
    static int cancelMatching(Context c, String query) {
        String q = Text.fold(query);
        if (q.isEmpty()) return 0;
        int n = 0;
        synchronized (LOCK) {
            List<Item> items = list(c);
            List<Item> keep = new ArrayList<>();
            for (Item it : items) {
                String f = Text.fold(it.text);
                if (f.contains(q) || q.contains(f)) {
                    cancelAlarm(c, it);
                    n++;
                } else {
                    keep.add(it);
                }
            }
            save(c, keep);
        }
        return n;
    }

    static void clear(Context c) {
        synchronized (LOCK) {
            for (Item it : list(c)) cancelAlarm(c, it);
            save(c, new ArrayList<>());
        }
    }

    /** Reagenda tudo (depois de reiniciar o relógio ou atualizar o app). */
    static void rescheduleAll(Context c) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            List<Item> items = list(c);
            List<Item> keep = new ArrayList<>();
            for (Item it : items) {
                if (it.at <= now - 10 * 60 * 1000L) {
                    if (!it.daily) continue; // perdeu a hora com o relógio desligado
                    while (it.at <= now) it.at += 24L * 60 * 60 * 1000;
                }
                keep.add(it);
            }
            save(c, keep);
            for (Item it : keep) schedule(c, it);
        }
    }

    /** Chegou a hora: avisa e, se for diário, marca o de amanhã. */
    static void fire(Context c, long id) {
        Item found = null;
        synchronized (LOCK) {
            List<Item> items = list(c);
            List<Item> keep = new ArrayList<>();
            for (Item it : items) {
                if (it.id == id) {
                    found = it;
                    if (it.daily) {
                        long now = System.currentTimeMillis();
                        while (it.at <= now) it.at += 24L * 60 * 60 * 1000;
                        keep.add(it);
                    }
                } else {
                    keep.add(it);
                }
            }
            save(c, keep);
        }
        if (found == null) return;
        if (found.daily) schedule(c, found);
        final String text = Phrases.reminder(c, found.text);
        Brain.add(c, "model", text);
        // Com o Snoop aberto na tela, ele mesmo fala; senão, aviso que acende a tela e abre o Snoop.
        final java.util.concurrent.atomic.AtomicBoolean spoken = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            spoken.set(MainActivity.deliverIfOpen(text));
            done.countDown();
        });
        try {
            done.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // segue para o aviso
        }
        if (!spoken.get()) Notifier.showReminder(c, (int) (found.id % 100000) + 1000, text);
    }

    /** Marca um lembrete de teste para daqui a 1 minuto. */
    static Item test(Context c) {
        return add(c, System.currentTimeMillis() + 60_000L, "teste do Snoop (o lembrete funciona!)", false);
    }

    /** Diagnóstico para os ajustes. */
    static String status(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        boolean exact = am != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
        return (exact ? "alarme exato permitido" : "alarme exato bloqueado (pode atrasar)")
                + " · " + (Notifier.fullScreenAllowed(c) ? "abre a tela" : "só notificação");
    }

    /** Lembretes marcados, para o Gemini saber (e responder "quais lembretes eu tenho?"). */
    static String describe(Context c) {
        List<Item> items = list(c);
        if (items.isEmpty()) return "Ele não tem lembretes marcados. ";
        StringBuilder b = new StringBuilder("Lembretes marcados: ");
        SimpleDateFormat f = new SimpleDateFormat("EEEE dd/MM 'às' HH:mm", PT_BR);
        SimpleDateFormat h = new SimpleDateFormat("HH:mm", PT_BR);
        int n = 0;
        for (Item it : items) {
            if (n++ >= 8) break;
            b.append(it.text).append(" (")
                    .append(it.daily ? "todo dia às " + h.format(new Date(it.at)) : f.format(new Date(it.at)))
                    .append("); ");
        }
        return b.toString();
    }

    /** Texto curto para a tela de ajustes. */
    static String label(Item it) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(it.at);
        String time = String.format(Locale.ROOT, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        if (it.daily) return "todo dia " + time + " · " + it.text;
        String day = new SimpleDateFormat("dd/MM", PT_BR).format(new Date(it.at));
        return day + " " + time + " · " + it.text;
    }

    // ------------------------------------------------------------------ alarmes

    private static PendingIntent intent(Context c, Item it) {
        Intent i = new Intent(c, Pulse.class)
                .setAction(ACTION_FIRE)
                .setData(Uri.parse("snoop://lembrete/" + it.id))
                .putExtra(EXTRA_ID, it.id);
        return PendingIntent.getBroadcast(c, (int) (it.id % Integer.MAX_VALUE), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context c, Item it) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = intent(c, it);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, pi);
            } else {
                // Como um despertador: o jeito mais confiável de tocar na hora, mesmo com o relógio em repouso.
                PendingIntent show = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(it.at, show), pi);
            }
        } catch (Throwable t) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.at, pi);
            } catch (Throwable t2) {
                am.set(AlarmManager.RTC_WAKEUP, it.at, pi);
            }
        }
    }

    private static void cancelAlarm(Context c, Item it) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(intent(c, it));
    }
}
