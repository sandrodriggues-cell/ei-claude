package com.eiclaude.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Configurações salvas no aparelho. */
final class Prefs {

    private static final String FILE = "ei_claude";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PHRASE = "phrase";
    private static final String KEY_SENSITIVITY = "sensitivity";

    static final int DEFAULT_SENSITIVITY = 50;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_ENABLED, value).apply();
    }

    /** Texto vazio = modo automático (primeira frase candidata que o modelo conhece). */
    static String getPhrase(Context c) {
        String v = sp(c).getString(KEY_PHRASE, "");
        return v == null ? "" : v;
    }

    static void setPhrase(Context c, String value) {
        sp(c).edit().putString(KEY_PHRASE, value == null ? "" : value).apply();
    }

    static int getSensitivity(Context c) {
        return sp(c).getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
    }

    static void setSensitivity(Context c, int value) {
        sp(c).edit().putInt(KEY_SENSITIVITY, value).apply();
    }

    /** Confiança mínima por palavra para disparar. Mais sensível = exige menos confiança. */
    static float thresholdFor(int sensitivity) {
        int s = Math.max(0, Math.min(100, sensitivity));
        return 0.95f - (s / 100f) * 0.6f;
    }

    static float threshold(Context c) {
        return thresholdFor(getSensitivity(c));
    }
}
