package com.eiclaude.snoop;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Histórico curto de passos (últimas 4 horas) para saber há quanto tempo você está parado.
 * Conta como "se mexeu" quando aparecem pelo menos {@link #MOVE_STEPS} passos em 10 minutos.
 * Cada fonte tem a sua própria contagem: "hs" (Health Services, passos do dia) e
 * "sensor" (contador do relógio desde que ligou).
 */
final class Moves {

    static final int MOVE_STEPS = 30;
    private static final long WINDOW_MS = 10L * 60 * 1000;
    private static final long KEEP_MS = 4L * 60 * 60 * 1000;
    private static final int MAX_SAMPLES = 160;

    private static final class Sample {
        final long t;
        final long v;

        Sample(long t, long v) {
            this.t = t;
            this.v = v;
        }
    }

    private Moves() {
    }

    static synchronized void add(Context c, String source, long time, long value) {
        if (time <= 0 || value < 0) return;
        List<Sample> list = load(c, source);
        if (!list.isEmpty()) {
            Sample last = list.get(list.size() - 1);
            if (time <= last.t || value == last.v) return; // fora de ordem ou sem passos novos
        }
        list.add(new Sample(time, value));
        long cut = System.currentTimeMillis() - KEEP_MS;
        while (list.size() > 2 && (list.get(0).t < cut || list.size() > MAX_SAMPLES)) list.remove(0);
        save(c, source, list);
        Prefs.putLong(c, "passos_ultimo_dado", Math.max(Prefs.getLong(c, "passos_ultimo_dado"), time));
    }

    /**
     * Minutos desde o último movimento, ou -1 se ainda não há dados de passos.
     * Sem movimento em todo o histórico guardado, conta desde a primeira amostra.
     */
    static synchronized long idleMinutes(Context c, long now) {
        long lastMove = -1;
        long firstSeen = Long.MAX_VALUE;
        boolean any = false;
        for (String source : new String[]{"hs", "sensor"}) {
            List<Sample> s = load(c, source);
            if (s.isEmpty()) continue;
            any = true;
            firstSeen = Math.min(firstSeen, s.get(0).t);
            lastMove = Math.max(lastMove, lastMove(s));
        }
        if (!any) return -1;
        long since = lastMove > 0 ? lastMove : firstSeen;
        return Math.max(0, (now - since) / 60000);
    }

    /** Passos de hoje, pelo contador diário do Health Services; -1 se não houver dado de hoje. */
    static synchronized long stepsToday(Context c, long now) {
        List<Sample> s = load(c, "hs");
        if (s.isEmpty()) return -1;
        Sample last = s.get(s.size() - 1);
        if (!Prefs.today(last.t).equals(Prefs.today(now))) return 0;
        return last.v;
    }

    /** Passos dados desde {@code since} (somando as duas fontes, fica com a maior). */
    static synchronized long stepsSince(Context c, long since) {
        long best = 0;
        for (String source : new String[]{"hs", "sensor"}) {
            List<Sample> s = load(c, source);
            long sum = 0;
            for (int i = 1; i < s.size(); i++) {
                Sample a = s.get(i);
                Sample b = s.get(i - 1);
                if (a.t < since) continue;
                sum += a.v >= b.v ? a.v - b.v : a.v;
            }
            best = Math.max(best, sum);
        }
        return best;
    }

    /** Momento da última "rajada" de passos (≥ MOVE_STEPS em 10 min), ou -1. */
    private static long lastMove(List<Sample> s) {
        for (int i = s.size() - 1; i > 0; i--) {
            Sample cur = s.get(i);
            long steps = 0;
            for (int j = i; j > 0; j--) {
                Sample a = s.get(j);
                Sample b = s.get(j - 1);
                if (cur.t - b.t > WINDOW_MS && j != i) break;
                // Contador zerado (meia-noite ou relógio reiniciado): conta o valor novo inteiro.
                steps += a.v >= b.v ? a.v - b.v : a.v;
                if (cur.t - b.t > WINDOW_MS) break;
            }
            if (steps >= MOVE_STEPS) return cur.t;
        }
        return -1;
    }

    private static List<Sample> load(Context c, String source) {
        List<Sample> out = new ArrayList<>();
        String raw = Prefs.getString(c, "passos_" + source);
        if (raw.isEmpty()) return out;
        for (String part : raw.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            try {
                out.add(new Sample(Long.parseLong(part.substring(0, colon)), Long.parseLong(part.substring(colon + 1))));
            } catch (NumberFormatException ignored) {
                // amostra inválida
            }
        }
        return out;
    }

    private static void save(Context c, String source, List<Sample> list) {
        StringBuilder b = new StringBuilder();
        for (Sample s : list) {
            if (b.length() > 0) b.append(';');
            b.append(String.format(Locale.ROOT, "%d:%d", s.t, s.v));
        }
        Prefs.putString(c, "passos_" + source, b.toString());
    }
}
