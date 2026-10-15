package com.eiclaude.snoop;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Ajustes e estado do Snoop, guardados só no relógio. */
final class Prefs {

    static final String MODEL_FAST = "gemini-3.5-flash-lite";
    static final String MODEL_SMART = "gemini-3.8-flash";

    static final String VOICE_WATCH = "relogio";
    static final String VOICE_GEMINI = "gemini";
    static final String[] GEMINI_VOICES = {"Puck", "Fenrir", "Leda", "Achird"};

    /** Opções do aviso "parado" (minutos); 0 = desligado. */
    static final int[] IDLE_OPTIONS = {60, 90, 120, 45, 0};
    /** Opções do horário da saudação (minutos do dia); -1 = desligada. */
    static final int[] GREET_OPTIONS = {14 * 60, 15 * 60, 16 * 60, 18 * 60, 21 * 60, 6 * 60, 7 * 60, 8 * 60,
            9 * 60, 10 * 60, 12 * 60, 13 * 60, -1};
    /** Horários de silêncio pré-definidos (início, fim em minutos do dia); iguais = sem silêncio. */
    static final int[][] QUIET_OPTIONS = {{8 * 60, 14 * 60}, {22 * 60, 6 * 60}, {23 * 60, 7 * 60}, {0, 0}};

    private static final String FILE = "snoop";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ chave e modelo

    static String apiKey(Context c) {
        return sp(c).getString("chave", "");
    }

    static void setApiKey(Context c, String key) {
        sp(c).edit().putString("chave", key == null ? "" : key.trim()).apply();
    }

    static boolean hasKey(Context c) {
        return looksLikeKey(apiKey(c));
    }

    /** As chaves do Gemini mudaram de formato com o tempo: só conferimos o básico. */
    static boolean looksLikeKey(String k) {
        return k != null && k.length() >= 20 && k.length() <= 300 && !k.matches(".*\\s.*");
    }

    static String model(Context c) {
        String m = sp(c).getString("modelo", MODEL_FAST);
        return m == null || m.isEmpty() ? MODEL_FAST : m;
    }

    static void setModel(Context c, String m) {
        if (m == null || m.trim().isEmpty()) return;
        sp(c).edit().putString("modelo", m.trim()).apply();
    }

    static String otherModel(String m) {
        return MODEL_SMART.equals(m) ? MODEL_FAST : MODEL_SMART;
    }

    static String modelLabel(String m) {
        if (MODEL_FAST.equals(m)) return "Rápido (Gemini 3.5 Flash-Lite)";
        if (MODEL_SMART.equals(m)) return "Esperto (Gemini 3.8 Flash)";
        return m;
    }

    // ------------------------------------------------------------------ jeito do Snoop

    static String name(Context c) {
        String n = sp(c).getString("nome", "Sandro");
        return n == null || n.trim().isEmpty() ? "Sandro" : n.trim();
    }

    static void setName(Context c, String n) {
        if (n == null) return;
        n = n.trim();
        if (n.length() > 40) n = n.substring(0, 40);
        sp(c).edit().putString("nome", n.isEmpty() ? "Sandro" : n).apply();
    }

    static String voice(Context c) {
        return sp(c).getString("voz", VOICE_WATCH);
    }

    static void setVoice(Context c, String v) {
        sp(c).edit().putString("voz", VOICE_GEMINI.equals(v) ? VOICE_GEMINI : VOICE_WATCH).apply();
    }

    static String geminiVoice(Context c) {
        return sp(c).getString("voz_gemini", GEMINI_VOICES[0]);
    }

    static void setGeminiVoice(Context c, String v) {
        sp(c).edit().putString("voz_gemini", v).apply();
    }

    static String ttsStatus(Context c) {
        return sp(c).getString("tts_status", "ainda não testado");
    }

    static void setTtsStatus(Context c, String s) {
        sp(c).edit().putString("tts_status", s).apply();
    }

    /** Depois de falar, o Snoop já volta a ouvir (conversa contínua). */
    static boolean keepListening(Context c) {
        return sp(c).getBoolean("conversa_continua", true);
    }

    static void setKeepListening(Context c, boolean on) {
        sp(c).edit().putBoolean("conversa_continua", on).apply();
    }

    // ------------------------------------------------------------------ avisos

    static int idleMinutes(Context c) {
        return sp(c).getInt("parado_min", 60);
    }

    static void setIdleMinutes(Context c, int m) {
        sp(c).edit().putInt("parado_min", Math.max(0, m)).apply();
    }

    static int greetMinute(Context c) {
        return sp(c).getInt("saudacao_min", 14 * 60);
    }

    static void setGreetMinute(Context c, int m) {
        sp(c).edit().putInt("saudacao_min", m < 0 ? -1 : m % (24 * 60)).apply();
    }

    static int quietStart(Context c) {
        return sp(c).getInt("silencio_ini", 8 * 60);
    }

    static int quietEnd(Context c) {
        return sp(c).getInt("silencio_fim", 14 * 60);
    }

    static void setQuiet(Context c, int start, int end) {
        sp(c).edit().putInt("silencio_ini", ((start % 1440) + 1440) % 1440)
                .putInt("silencio_fim", ((end % 1440) + 1440) % 1440).apply();
    }

    static boolean missYou(Context c) {
        return sp(c).getBoolean("saudade", true);
    }

    static void setMissYou(Context c, boolean on) {
        sp(c).edit().putBoolean("saudade", on).apply();
    }

    /** Está no horário de silêncio (sem avisos)? */
    static boolean inQuiet(Context c, long now) {
        int s = quietStart(c);
        int e = quietEnd(c);
        if (s == e) return false;
        int m = minuteOfDay(now);
        return s < e ? (m >= s && m < e) : (m >= s || m < e);
    }

    static int minuteOfDay(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
    }

    static String hhmm(int minuteOfDay) {
        if (minuteOfDay < 0) return "desligada";
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    static String quietLabel(Context c) {
        int s = quietStart(c);
        int e = quietEnd(c);
        return s == e ? "desligado" : hhmm(s) + "–" + hhmm(e);
    }

    static String today(long now) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(now));
    }

    // ------------------------------------------------------------------ estado dos avisos

    static long getLong(Context c, String k) {
        return sp(c).getLong(k, 0L);
    }

    static void putLong(Context c, String k, long v) {
        sp(c).edit().putLong(k, v).apply();
    }

    static String getString(Context c, String k) {
        return sp(c).getString(k, "");
    }

    static void putString(Context c, String k, String v) {
        sp(c).edit().putString(k, v == null ? "" : v).apply();
    }

    static int getInt(Context c, String k, int def) {
        return sp(c).getInt(k, def);
    }

    static void putInt(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).apply();
    }

    // ------------------------------------------------------------------ uso do plano grátis

    /** Conta um pedido ao Gemini (para mostrar quantos foram hoje). */
    static void countRequest(Context c, int tokensIn, int tokensOut) {
        SharedPreferences p = sp(c);
        String day = today(System.currentTimeMillis());
        int count = day.equals(p.getString("uso_dia", "")) ? p.getInt("uso_pedidos", 0) : 0;
        p.edit().putString("uso_dia", day)
                .putInt("uso_pedidos", count + 1)
                .putLong("uso_tokens_in", p.getLong("uso_tokens_in", 0) + Math.max(0, tokensIn))
                .putLong("uso_tokens_out", p.getLong("uso_tokens_out", 0) + Math.max(0, tokensOut))
                .apply();
    }

    static int requestsToday(Context c) {
        SharedPreferences p = sp(c);
        return today(System.currentTimeMillis()).equals(p.getString("uso_dia", "")) ? p.getInt("uso_pedidos", 0) : 0;
    }

    // ------------------------------------------------------------------ saudação depois de configurar

    static void requestGreeting(Context c) {
        sp(c).edit().putBoolean("saudar_ao_abrir", true).apply();
    }

    static boolean consumeGreeting(Context c) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("saudar_ao_abrir", false)) return false;
        p.edit().putBoolean("saudar_ao_abrir", false).apply();
        return true;
    }
}
