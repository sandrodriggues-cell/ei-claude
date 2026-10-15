package com.eiclaude.snoop;

import android.content.Context;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Escala de trabalho em ciclo (por exemplo 6 dias de trabalho e 2 de folga) e horário do turno.
 * Uma data de referência marca o primeiro dia de um bloco de folgas. Um dia é "de trabalho"
 * quando o turno começa nele (turnos da noite terminam no dia seguinte).
 */
final class Shift {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Shift() {
    }

    static boolean enabled(Context c) {
        return Prefs.getInt(c, "escala_liga", 0) == 1 && reference(c) != null;
    }

    static void setEnabled(Context c, boolean on) {
        Prefs.putInt(c, "escala_liga", on ? 1 : 0);
    }

    static int workDays(Context c) {
        return Math.max(1, Prefs.getInt(c, "escala_trabalho", 6));
    }

    static int offDays(Context c) {
        return Math.max(1, Prefs.getInt(c, "escala_folga", 2));
    }

    static int start(Context c) {
        return Prefs.getInt(c, "turno_ini", 22 * 60);
    }

    static int end(Context c) {
        return Prefs.getInt(c, "turno_fim", 6 * 60);
    }

    static LocalDate reference(Context c) {
        String s = Prefs.getString(c, "escala_ref");
        if (s.isEmpty()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    static void configure(Context c, int work, int off, String referenceDate, int startMin, int endMin) {
        Prefs.putInt(c, "escala_trabalho", Math.max(1, Math.min(30, work)));
        Prefs.putInt(c, "escala_folga", Math.max(1, Math.min(30, off)));
        try {
            Prefs.putString(c, "escala_ref", LocalDate.parse(referenceDate).toString());
        } catch (Exception ignored) {
            // mantém a data anterior
        }
        if (startMin >= 0) Prefs.putInt(c, "turno_ini", startMin % 1440);
        if (endMin >= 0) Prefs.putInt(c, "turno_fim", endMin % 1440);
    }

    static boolean overnight(Context c) {
        return end(c) <= start(c);
    }

    static LocalDate date(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    static long at(LocalDate d, int minuteOfDay) {
        return d.atStartOfDay(ZoneId.systemDefault()).plusMinutes(minuteOfDay).toInstant().toEpochMilli();
    }

    /** O dia é de folga (nenhum turno começa nele)? */
    static boolean isOff(Context c, LocalDate d) {
        LocalDate ref = reference(c);
        if (ref == null) return false;
        int cycle = workDays(c) + offDays(c);
        long k = Math.floorMod(ChronoUnit.DAYS.between(ref, d), (long) cycle);
        return k < offDays(c);
    }

    /** Primeiro dia da próxima folga, a partir de amanhã. */
    static LocalDate nextOff(Context c, LocalDate from) {
        LocalDate d = from.plusDays(1);
        for (int i = 0; i < 60; i++, d = d.plusDays(1)) {
            if (isOff(c, d) && !isOff(c, d.minusDays(1))) return d;
        }
        return null;
    }

    static String dayName(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.FULL, PT_BR) + ", "
                + String.format(Locale.ROOT, "%02d/%02d", d.getDayOfMonth(), d.getMonthValue());
    }

    static String turnLabel(Context c) {
        return Prefs.hhmm(start(c)) + " às " + Prefs.hhmm(end(c));
    }

    /** Uma frase sobre hoje, para a saudação ("Hoje é folga!"). */
    static String todayLine(Context c, long now) {
        if (!enabled(c)) return "";
        LocalDate today = date(now);
        if (isOff(c, today)) return isOff(c, today.plusDays(1)) ? " Hoje é folga, aproveita!" : " Hoje é folga, e amanhã tem turno.";
        String line = " Hoje tem turno às " + Prefs.hhmm(start(c)) + ".";
        if (isOff(c, today.plusDays(1))) line += " E depois dele vem a folga!";
        return line;
    }

    /** Para o Gemini saber da escala. */
    static String context(Context c, long now) {
        if (!enabled(c)) return "";
        LocalDate today = date(now);
        StringBuilder b = new StringBuilder("Escala dele: ");
        b.append(workDays(c)).append(" dias de trabalho e ").append(offDays(c)).append(" de folga, turno das ")
                .append(turnLabel(c)).append(". ");
        if (isOff(c, today)) {
            b.append("Hoje é folga. ");
        } else {
            b.append("Hoje é dia de trabalho. ");
        }
        LocalDate next = nextOff(c, today);
        if (next != null && !isOff(c, today)) {
            b.append("Próxima folga começa ").append(dayName(next)).append(". ");
        } else if (isOff(c, today)) {
            LocalDate back = today.plusDays(1);
            for (int i = 0; i < 30 && isOff(c, back); i++) back = back.plusDays(1);
            b.append("Volta ao trabalho ").append(dayName(back)).append(". ");
        }
        return b.toString();
    }

    /** Hora do aviso de "bom turno" (30 min antes) do turno que começa hoje, ou -1. */
    static long startReminderToday(Context c, long now) {
        if (!enabled(c)) return -1;
        LocalDate today = date(now);
        if (isOff(c, today)) return -1;
        return at(today, start(c)) - 30L * 60 * 1000;
    }

    /** Hora em que termina, hoje, um turno (que começou ontem, se for da noite), ou -1. */
    static long endToday(Context c, long now) {
        if (!enabled(c)) return -1;
        LocalDate today = date(now);
        LocalDate began = overnight(c) ? today.minusDays(1) : today;
        if (isOff(c, began)) return -1;
        return at(today, end(c));
    }

    /** Depois desse turno vem folga? */
    static boolean offAfterEnd(Context c, long now) {
        LocalDate today = date(now);
        return overnight(c) ? isOff(c, today) : isOff(c, today.plusDays(1));
    }
}
