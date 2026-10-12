package com.eiclaude.relogio;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes guardados no próprio relógio (arquivo privado do app, fora de backups). */
final class Prefs {

    static final String MODEL_HAIKU = "claude-haiku-5-5";
    static final String MODEL_SONNET = "claude-sonnet-5-5";

    private static final String FILE = "relogio";
    private static final String API_KEY = "api_key";
    private static final String NAME = "nome";
    private static final String MODEL = "modelo";
    private static final String OFFLINE_ONLY = "voz_offline";
    private static final String ENGINE_ID = "motor";
    private static final String ENGINE_LABEL = "motor_nome";
    private static final String TTS_STATUS = "tts_status";
    private static final String TOKENS_IN = "tokens_in";
    private static final String TOKENS_OUT = "tokens_out";
    private static final String GREET = "saudar";
    private static final String NEW_CHAT = "nova_conversa";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ chave e modelo

    static String apiKey(Context c) {
        return sp(c).getString(API_KEY, "");
    }

    static boolean hasKey(Context c) {
        return looksLikeKey(apiKey(c));
    }

    static boolean looksLikeKey(String k) {
        return k != null && k.startsWith("sk-ant-") && k.length() > 20 && !k.contains(" ");
    }

    static void setApiKey(Context c, String key) {
        sp(c).edit().putString(API_KEY, key.trim()).commit();
    }

    static String model(Context c) {
        String m = sp(c).getString(MODEL, MODEL_HAIKU);
        return MODEL_SONNET.equals(m) ? MODEL_SONNET : MODEL_HAIKU;
    }

    static void setModel(Context c, String model) {
        if (MODEL_HAIKU.equals(model) || MODEL_SONNET.equals(model)) {
            sp(c).edit().putString(MODEL, model).commit();
        }
    }

    static String modelLabel(String model) {
        return MODEL_SONNET.equals(model) ? "Sonnet 5.5" : "Haiku 5.5";
    }

    static String name(Context c) {
        String n = sp(c).getString(NAME, "Sandro").trim();
        return n.isEmpty() ? "Sandro" : n;
    }

    static void setName(Context c, String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() > 40) n = n.substring(0, 40);
        if (!n.isEmpty()) sp(c).edit().putString(NAME, n).commit();
    }

    // ------------------------------------------------------------------ voz

    static boolean offlineOnly(Context c) {
        return sp(c).getBoolean(OFFLINE_ONLY, false);
    }

    static void setOfflineOnly(Context c, boolean on) {
        sp(c).edit().putBoolean(OFFLINE_ONLY, on).apply();
    }

    /** Motor de reconhecimento que funcionou da última vez (para tentar primeiro). */
    static String engineId(Context c) {
        return sp(c).getString(ENGINE_ID, "");
    }

    static String engineLabel(Context c) {
        return sp(c).getString(ENGINE_LABEL, "ainda não usado");
    }

    static void setEngine(Context c, String id, String label) {
        sp(c).edit().putString(ENGINE_ID, id).putString(ENGINE_LABEL, label).apply();
    }

    static String ttsStatus(Context c) {
        return sp(c).getString(TTS_STATUS, "ainda não testada");
    }

    static void setTtsStatus(Context c, String status) {
        sp(c).edit().putString(TTS_STATUS, status).apply();
    }

    // ------------------------------------------------------------------ uso e avisos

    static void addTokens(Context c, long in, long out) {
        SharedPreferences p = sp(c);
        p.edit()
                .putLong(TOKENS_IN, p.getLong(TOKENS_IN, 0) + Math.max(0, in))
                .putLong(TOKENS_OUT, p.getLong(TOKENS_OUT, 0) + Math.max(0, out))
                .apply();
    }

    static long tokensIn(Context c) {
        return sp(c).getLong(TOKENS_IN, 0);
    }

    static long tokensOut(Context c) {
        return sp(c).getLong(TOKENS_OUT, 0);
    }

    /** Pede uma saudação falada na próxima vez que a tela de conversa abrir. */
    static void requestGreeting(Context c) {
        sp(c).edit().putBoolean(GREET, true).commit();
    }

    static boolean consumeGreeting(Context c) {
        boolean g = sp(c).getBoolean(GREET, false);
        if (g) sp(c).edit().putBoolean(GREET, false).apply();
        return g;
    }

    static void requestNewChat(Context c) {
        sp(c).edit().putBoolean(NEW_CHAT, true).apply();
    }

    static boolean consumeNewChat(Context c) {
        boolean n = sp(c).getBoolean(NEW_CHAT, false);
        if (n) sp(c).edit().putBoolean(NEW_CHAT, false).apply();
        return n;
    }
}
