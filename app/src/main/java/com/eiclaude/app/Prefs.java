package com.eiclaude.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Configurações salvas no aparelho. */
final class Prefs {

    private static final String FILE = "ei_claude";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PHRASE = "phrase";
    private static final String KEY_SENSITIVITY = "sensitivity";
    private static final String KEY_AUTO_VOICE = "auto_voice";
    private static final String KEY_CLOSE_BY_VOICE = "close_by_voice";
    private static final String KEY_CLOSE_PHRASES = "close_phrases";
    private static final String KEY_VOICE_BUTTON = "voice_button";
    private static final String KEY_STOP_BUTTON = "stop_button";

    static final int DEFAULT_SENSITIVITY = 50;
    static final String DEFAULT_CLOSE_PHRASES = "fechar, pode fechar, fechar o claude";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String str(Context c, String key, String def) {
        String v = sp(c).getString(key, def);
        return v == null ? def : v;
    }

    static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_ENABLED, value).apply();
    }

    /** Texto vazio = modo automático (primeira frase candidata que o modelo conhece). */
    static String getPhrase(Context c) {
        return str(c, KEY_PHRASE, "");
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

    // ------------------------------------------------------------ modo de voz e "fechar"

    static boolean isAutoVoice(Context c) {
        return sp(c).getBoolean(KEY_AUTO_VOICE, true);
    }

    static void setAutoVoice(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_AUTO_VOICE, value).apply();
    }

    static boolean isCloseByVoice(Context c) {
        return sp(c).getBoolean(KEY_CLOSE_BY_VOICE, true);
    }

    static void setCloseByVoice(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_CLOSE_BY_VOICE, value).apply();
    }

    static String getClosePhrases(Context c) {
        String v = str(c, KEY_CLOSE_PHRASES, DEFAULT_CLOSE_PHRASES);
        return v.trim().isEmpty() ? DEFAULT_CLOSE_PHRASES : v;
    }

    static void setClosePhrases(Context c, String value) {
        sp(c).edit().putString(KEY_CLOSE_PHRASES, value == null ? "" : value).apply();
    }

    /** Rótulo do botão do modo de voz escolhido à mão (vazio = descobrir sozinho). */
    static String getVoiceButton(Context c) {
        return str(c, KEY_VOICE_BUTTON, "");
    }

    static void setVoiceButton(Context c, String value) {
        sp(c).edit().putString(KEY_VOICE_BUTTON, value == null ? "" : value).apply();
    }

    /** Rótulo do botão que encerra o modo de voz (vazio = descobrir sozinho). */
    static String getStopButton(Context c) {
        return str(c, KEY_STOP_BUTTON, "");
    }

    static void setStopButton(Context c, String value) {
        sp(c).edit().putString(KEY_STOP_BUTTON, value == null ? "" : value).apply();
    }
}
