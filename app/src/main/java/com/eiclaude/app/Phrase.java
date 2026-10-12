package com.eiclaude.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Frases de ativação: normalização e comparação com o texto reconhecido. */
final class Phrase {

    /** Tentadas em ordem no modo automático; usa a primeira que o modelo de voz conhece. */
    static final String[] CANDIDATES = {
            "ei claude",
            "ei cláudio",
            "ei claudio",
            "oi claude",
            "oi cláudio",
            "ok claude",
            "hey claude"
    };

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Phrase() {
    }

    /** Minúsculas, sem pontuação, espaços simples. Mantém os acentos. */
    static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(PT_BR);
        t = t.replaceAll("[^\\p{L}\\p{Nd}' ]", " ");
        return t.trim().replaceAll("\\s+", " ");
    }

    /** "Ei, Claude; oi claude" -> ["ei claude", "oi claude"]. */
    static List<String> variants(String entry) {
        List<String> out = new ArrayList<>();
        if (entry == null) return out;
        for (String part : entry.split("[,;\\n]")) {
            String n = normalize(part);
            if (!n.isEmpty() && !out.contains(n)) out.add(n);
        }
        return out;
    }

    static String[] words(String phrase) {
        String p = phrase == null ? "" : phrase.trim();
        return p.isEmpty() ? new String[0] : p.split(" ");
    }

    /** Forma para comparar rótulos e falas: sem acentos, minúsculas, sem pontuação. */
    static String fold(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd} ]", " ");
        return t.trim().replaceAll("\\s+", " ");
    }

    /** Texto do reconhecedor sem os marcadores [unk]. */
    static String clean(String text) {
        if (text == null) return "";
        return text.replace("[unk]", " ").trim().replaceAll("\\s+", " ");
    }

    /** O texto contém a frase como palavras inteiras? */
    static boolean contains(String cleanText, String phrase) {
        return (" " + cleanText + " ").contains(" " + phrase + " ");
    }
}
