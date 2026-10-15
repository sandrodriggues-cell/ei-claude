package com.eiclaude.snoop;

import android.content.Context;

/**
 * As necessidades do Snoop, como um bichinho virtual:
 * <ul>
 * <li><b>Barriga:</b> esvazia com o tempo; enche com petiscos, que você ganha andando
 * (um a cada {@link #STEPS_PER_TREAT} passos no dia).</li>
 * <li><b>Passeio:</b> quanto você andou nas últimas 3 horas.</li>
 * <li><b>Alegria:</b> sobe com conversa, carinho, truques e petiscos; cai com o tempo.</li>
 * </ul>
 */
final class Pet {

    static final int STEPS_PER_TREAT = 1500;
    static final int MAX_TREATS_PER_DAY = 6;

    private static final float BELLY_DRAIN_PER_HOUR = 4f;
    private static final float JOY_DRAIN_PER_HOUR = 3f;
    private static final long WALK_WINDOW_MS = 3L * 60 * 60 * 1000;
    private static final float STEPS_FOR_FULL_WALK = 2500f;

    private Pet() {
    }

    // ------------------------------------------------------------------ valores (0 a 100)

    static int belly(Context c, long now) {
        return Math.round(decayed(c, "barriga", BELLY_DRAIN_PER_HOUR, now));
    }

    static int joy(Context c, long now) {
        return Math.round(decayed(c, "alegria", JOY_DRAIN_PER_HOUR, now));
    }

    static int walk(Context c, long now) {
        long steps = Moves.stepsSince(c, now - WALK_WINDOW_MS);
        return Math.round(Math.min(100f, steps * 100f / STEPS_FOR_FULL_WALK));
    }

    static void addJoy(Context c, int amount) {
        add(c, "alegria", JOY_DRAIN_PER_HOUR, amount);
    }

    // ------------------------------------------------------------------ petiscos

    /** Petiscos ganhos hoje com os passos e ainda não dados. */
    static int treatsAvailable(Context c, long now) {
        long steps = Moves.stepsToday(c, now);
        if (steps <= 0) return 0;
        int earned = (int) Math.min(MAX_TREATS_PER_DAY, steps / STEPS_PER_TREAT);
        return Math.max(0, earned - treatsGivenToday(c, now));
    }

    /** Passos que faltam para o próximo petisco (ou -1 sem dados de passos). */
    static long stepsToNextTreat(Context c, long now) {
        long steps = Moves.stepsToday(c, now);
        if (steps < 0) return -1;
        if (steps / STEPS_PER_TREAT >= MAX_TREATS_PER_DAY) return 0;
        return STEPS_PER_TREAT - (steps % STEPS_PER_TREAT);
    }

    static boolean feed(Context c) {
        long now = System.currentTimeMillis();
        if (treatsAvailable(c, now) <= 0) return false;
        String day = Prefs.today(now);
        Prefs.putString(c, "petiscos_dia", day);
        Prefs.putInt(c, "petiscos_dados", treatsGivenToday(c, now) + 1);
        add(c, "barriga", BELLY_DRAIN_PER_HOUR, 30);
        addJoy(c, 5);
        return true;
    }

    private static int treatsGivenToday(Context c, long now) {
        return Prefs.today(now).equals(Prefs.getString(c, "petiscos_dia")) ? Prefs.getInt(c, "petiscos_dados", 0) : 0;
    }

    // ------------------------------------------------------------------ humor e resumo

    /** Humor do Snoop parado, pelas necessidades (null = tudo bem). */
    static DogView.Mood moodHint(Context c, long now) {
        if (belly(c, now) < 25) return DogView.Mood.TRISTE;
        if (joy(c, now) < 25) return DogView.Mood.PREOCUPADO;
        if (walk(c, now) < 10 && !Prefs.inQuiet(c, now)) return DogView.Mood.SONOLENTO;
        return null;
    }

    /** Para o Gemini saber como o Snoop está. */
    static String summary(Context c, long now) {
        StringBuilder b = new StringBuilder();
        b.append("Como você (Snoop) está agora, de 0 a 100: barriga ").append(belly(c, now))
                .append(", passeio ").append(walk(c, now))
                .append(", alegria ").append(joy(c, now)).append(". ");
        int treats = treatsAvailable(c, now);
        if (treats > 0) {
            b.append("Ele já ganhou ").append(treats).append(treats == 1 ? " petisco" : " petiscos")
                    .append(" andando e ainda não te deu (há um botão Dar petisco na tela). ");
        }
        b.append("Se a barriga estiver baixa, você pode comentar que está com fome; se o passeio estiver baixo, ")
                .append("pode pedir para andarem um pouco. Sem exagero: no máximo uma menção por conversa. ");
        return b.toString();
    }

    // ------------------------------------------------------------------ armazenamento

    private static float decayed(Context c, String key, float drainPerHour, long now) {
        long t = Prefs.getLong(c, key + "_t");
        if (t <= 0) return 70f; // começa satisfeito
        float v = Prefs.getInt(c, key + "_v", 70);
        float hours = Math.max(0, now - t) / 3_600_000f;
        return Math.max(0f, Math.min(100f, v - drainPerHour * hours));
    }

    private static synchronized void add(Context c, String key, float drainPerHour, int amount) {
        long now = System.currentTimeMillis();
        float v = decayed(c, key, drainPerHour, now) + amount;
        Prefs.putInt(c, key + "_v", Math.round(Math.max(0f, Math.min(100f, v))));
        Prefs.putLong(c, key + "_t", now);
    }
}
