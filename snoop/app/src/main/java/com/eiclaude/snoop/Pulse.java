package com.eiclaude.snoop;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import androidx.wear.tiles.TileService;

import java.util.Calendar;

/**
 * O "relógio interno" do Snoop: acorda a cada ~15 minutos (e na hora da saudação), olha os passos
 * e o horário e decide se chama você. Respeita o horário de silêncio e o modo Não perturbe do relógio.
 */
public class Pulse extends BroadcastReceiver {

    static final String ACTION_TICK = "com.eiclaude.snoop.TICK";
    private static final long TICK_MS = 15L * 60 * 1000;
    private static final long GREET_WINDOW_MS = 3L * 60 * 60 * 1000;
    private static final long PRAISE_WINDOW_MS = 90L * 60 * 1000;
    private static final long MISS_AFTER_MS = 48L * 60 * 60 * 1000;
    private static final long SHIFT_WINDOW_MS = 45L * 60 * 1000;
    private static final long HUNGRY_EVERY_MS = 6L * 60 * 60 * 1000;

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final String action = intent == null ? "" : String.valueOf(intent.getAction());
        final long reminderId = intent == null ? -1 : intent.getLongExtra(Reminders.EXTRA_ID, -1);
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                if (Reminders.ACTION_FIRE.equals(action)) {
                    Reminders.fire(app, reminderId);
                    return; // o finally ainda reagenda e atualiza o bloco
                }
                if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                    Reminders.rescheduleAll(app);
                }
                Health.registerIfNeeded(app);
                check(app, System.currentTimeMillis());
            } catch (Throwable ignored) {
                // a próxima batida tenta de novo
            } finally {
                schedule(app);
                try {
                    TileService.getUpdater(app).requestUpdate(SnoopTile.class);
                } catch (Throwable ignored) {
                    // sem bloco adicionado
                }
                result.finish();
            }
        }, "Snoop-pulso").start();
    }

    /** Agenda a próxima batida (funciona mesmo com o relógio em repouso, com alguns minutos de folga). */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        long next = now + TICK_MS;
        long greet = nextGreeting(c, now);
        if (greet > now && greet < next) next = greet + 30_000;
        for (long event : new long[]{Shift.startReminderToday(c, now), Shift.endToday(c, now),
                Shift.startReminderToday(c, now + 24L * 60 * 60 * 1000), Shift.endToday(c, now + 24L * 60 * 60 * 1000)}) {
            if (event > now && event < next) next = event + 30_000;
        }
        Intent i = new Intent(c, Pulse.class).setAction(ACTION_TICK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        } catch (Throwable t) {
            am.set(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    private static long nextGreeting(Context c, long now) {
        int m = Prefs.greetMinute(c);
        if (m < 0) return -1;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, m / 60);
        cal.set(Calendar.MINUTE, m % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    private static long todayAt(long now, int minuteOfDay) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** Decide se o Snoop chama você agora. No máximo um aviso por batida. */
    static void check(Context c, long now) {
        // Passos na hora (reserva do Health Services).
        long counter = Health.readStepCounter(c, 4000);
        if (counter >= 0) Moves.add(c, "sensor", now, counter);

        boolean quiet = Prefs.inQuiet(c, now);
        boolean charging = isCharging(c);
        long idle = Moves.idleMinutes(c, now);
        long lastIdleAlert = Prefs.getLong(c, "aviso_parado_em");

        String today0 = Prefs.today(now);

        // 0. Turno: "bom turno" meia hora antes e "turno encerrado" no fim.
        long shiftStart = Shift.startReminderToday(c, now);
        if (shiftStart > 0 && now >= shiftStart && now - shiftStart < SHIFT_WINDOW_MS && !quiet
                && !today0.equals(Prefs.getString(c, "turno_ini_dia"))) {
            if (say(c, Notifier.ID_SHIFT, Phrases.shiftStart(c), "animado")) {
                Prefs.putString(c, "turno_ini_dia", today0);
                return;
            }
        }
        long shiftEnd = Shift.endToday(c, now);
        if (shiftEnd > 0 && now >= shiftEnd && now - shiftEnd < SHIFT_WINDOW_MS && !quiet
                && !today0.equals(Prefs.getString(c, "turno_fim_dia"))) {
            if (say(c, Notifier.ID_SHIFT, Phrases.shiftEnd(c, Shift.offAfterEnd(c, now)), "feliz")) {
                Prefs.putString(c, "turno_fim_dia", today0);
                return;
            }
        }

        // 1. Saudação do dia.
        int gm = Prefs.greetMinute(c);
        if (gm >= 0) {
            long at = todayAt(now, gm);
            String today = Prefs.today(now);
            if (!quiet && now >= at && now - at < GREET_WINDOW_MS
                    && !today.equals(Prefs.getString(c, "saudacao_dia"))) {
                if (say(c, Notifier.ID_GREETING, Phrases.greetingWithShift(c), "animado")) {
                    Prefs.putString(c, "saudacao_dia", today);
                    return;
                }
            }
        }

        // 2. Elogio: você se mexeu depois de um aviso de "parado".
        if (lastIdleAlert > 0 && now - lastIdleAlert < PRAISE_WINDOW_MS
                && Prefs.getLong(c, "elogio_para") != lastIdleAlert
                && idle >= 0 && idle * 60000 < now - lastIdleAlert) {
            Prefs.putLong(c, "elogio_para", lastIdleAlert);
            if (!quiet) {
                say(c, Notifier.ID_PRAISE, Phrases.praise(c), "feliz");
                return;
            }
        }

        // 3. Muito tempo parado.
        int limit = Prefs.idleMinutes(c);
        if (limit > 0 && idle >= limit && !quiet && !charging && now - lastIdleAlert >= limit * 60000L) {
            if (say(c, Notifier.ID_IDLE, Phrases.idle(c, idle), "animado")) {
                Prefs.putLong(c, "aviso_parado_em", now);
                return;
            }
        }

        // 4. Fome: barriga quase vazia (no máximo a cada 6 horas).
        if (!quiet && Pet.belly(c, now) < 20 && now - Prefs.getLong(c, "fome_em") > HUNGRY_EVERY_MS) {
            if (say(c, Notifier.ID_HUNGRY, Phrases.hungry(c, Pet.treatsAvailable(c, now) > 0), "triste")) {
                Prefs.putLong(c, "fome_em", now);
                return;
            }
        }

        // 5. Saudade: dois dias sem abrir o app.
        long opened = Prefs.getLong(c, "aberto_em");
        long missed = Prefs.getLong(c, "saudade_em");
        if (Prefs.missYou(c) && !quiet && opened > 0 && now - opened > MISS_AFTER_MS && now - missed > MISS_AFTER_MS) {
            Prefs.putLong(c, "saudade_em", now);
            say(c, Notifier.ID_MISS, Phrases.miss(c), "triste");
        }
    }

    /** Mostra o aviso e guarda a frase na conversa, para ele lembrar por que chamou. */
    static boolean say(Context c, int id, String text, String mood) {
        boolean shown = Notifier.show(c, id, text, mood);
        if (shown) Brain.add(c, "model", text);
        return shown;
    }

    private static boolean isCharging(Context c) {
        try {
            Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return false;
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return plugged != 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
