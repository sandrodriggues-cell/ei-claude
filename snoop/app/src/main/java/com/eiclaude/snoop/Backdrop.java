package com.eiclaude.snoop;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import java.util.Calendar;
import java.util.Locale;

/**
 * Fundo da tela do Snoop: cores prontas, uma cor escolhida no celular ou automático (muda com a
 * hora do dia). Em fundo claro, os textos ficam escuros para continuar legíveis.
 */
final class Backdrop {

    static final String AUTO = "automatico";
    static final String CUSTOM = "personalizada";

    /** id, nome, cor do centro, cor da borda. */
    static final String[][] PRESETS = {
            {"preto", "Preto", "#000000", "#000000"},
            {"noite", "Noite", "#1B2A4A", "#070B16"},
            {"parque", "Parque", "#24452C", "#0A160D"},
            {"por_do_sol", "Pôr do sol", "#5A2A1A", "#160806"},
            {"lavanda", "Lavanda", "#3A2C55", "#120C1D"},
            {"oceano", "Oceano", "#0F4552", "#041418"},
            {"vinho", "Vinho", "#4A1A2C", "#14060C"},
            {"ceu", "Céu", "#D6ECFF", "#8FC1EE"},
            {"areia", "Areia", "#F6EBDA", "#E0C8A4"},
    };

    /** Cores já resolvidas para pintar a tela. */
    static final class Look {
        int center;
        int edge;
        boolean light;
        int ink;
        int soft;
        int muted;
        int warn;
        int accent;
        int track;
    }

    private Backdrop() {
    }

    static String current(Context c) {
        String id = Prefs.getString(c, "fundo");
        return id.isEmpty() ? "preto" : id;
    }

    static void set(Context c, String id) {
        String f = Text.fold(id).replace(' ', '_');
        if (f.equals("por_do_sol") || f.equals("pordosol")) f = "por_do_sol";
        if (f.equals("automatico") || f.equals(CUSTOM) || indexOf(f) >= 0) Prefs.putString(c, "fundo", f);
    }

    static String customColor(Context c) {
        String s = Prefs.getString(c, "fundo_cor");
        return s.matches("#[0-9a-fA-F]{6}") ? s : "#1B2A4A";
    }

    static void setCustom(Context c, String hex) {
        if (hex != null && hex.trim().matches("#[0-9a-fA-F]{6}")) {
            Prefs.putString(c, "fundo_cor", hex.trim().toUpperCase(Locale.ROOT));
            Prefs.putString(c, "fundo", CUSTOM);
        }
    }

    static String label(Context c) {
        String id = current(c);
        if (AUTO.equals(id)) return "Automático (muda com a hora)";
        if (CUSTOM.equals(id)) return "Personalizada " + customColor(c);
        int i = indexOf(id);
        return i >= 0 ? PRESETS[i][1] : "Preto";
    }

    /** Próxima opção (para o botão nos ajustes do relógio). */
    static void cycle(Context c) {
        String id = current(c);
        String next;
        if (AUTO.equals(id)) {
            next = PRESETS[0][0];
        } else if (CUSTOM.equals(id)) {
            next = AUTO;
        } else {
            int i = indexOf(id);
            if (i + 1 < PRESETS.length) {
                next = PRESETS[i + 1][0];
            } else {
                next = Prefs.getString(c, "fundo_cor").isEmpty() ? AUTO : CUSTOM;
            }
        }
        Prefs.putString(c, "fundo", next);
    }

    static int indexOf(String id) {
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i][0].equals(id)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ cores

    static Look look(Context c) {
        String id = current(c);
        int center;
        int edge;
        if (AUTO.equals(id)) {
            String[] p = PRESETS[indexOf(autoFor(System.currentTimeMillis()))];
            center = Color.parseColor(p[2]);
            edge = Color.parseColor(p[3]);
        } else if (CUSTOM.equals(id)) {
            int base = Color.parseColor(customColor(c));
            center = blend(base, 0xFFFFFFFF, 0.12f);
            edge = blend(base, 0xFF000000, 0.35f);
        } else {
            int i = Math.max(0, indexOf(id));
            center = Color.parseColor(PRESETS[i][2]);
            edge = Color.parseColor(PRESETS[i][3]);
        }
        Look l = new Look();
        l.center = center;
        l.edge = edge;
        l.light = luminance(blend(center, edge, 0.5f)) > 0.45;
        if (l.light) {
            l.ink = 0xFF2A1C14;
            l.soft = 0xFF4A3B30;
            l.muted = 0xFF6B5A4C;
            l.warn = 0xFFB45309;
            l.accent = 0xFF9A4A12;
            l.track = 0x33000000;
        } else {
            l.ink = Ui.INK;
            l.soft = Ui.SOFT;
            l.muted = Ui.MUTED;
            l.warn = Ui.WARN;
            l.accent = Ui.ACCENT;
            l.track = 0x40FFFFFF;
        }
        return l;
    }

    /** Fundo em degradê redondo, mais claro atrás do Snoop. */
    static GradientDrawable drawable(Look l, int width, int height) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{l.center, l.edge});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.4f);
        g.setGradientRadius(Math.max(width, height) * 0.62f);
        return g;
    }

    /** No automático: céu de dia, pôr do sol no fim da tarde, noite à noite. */
    private static String autoFor(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        if (h >= 6 && h < 17) return "ceu";
        if (h >= 17 && h < 19) return "por_do_sol";
        return "noite";
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private static double luminance(int color) {
        return (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0;
    }

    /** Opções para o Gemini (troca de fundo por voz). */
    static String voiceOptions() {
        StringBuilder b = new StringBuilder();
        for (String[] p : PRESETS) b.append(p[0]).append(" (").append(p[1]).append("), ");
        b.append(AUTO).append(" (muda com a hora)");
        return b.toString();
    }
}
