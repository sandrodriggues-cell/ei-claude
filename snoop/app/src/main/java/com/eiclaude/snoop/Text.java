package com.eiclaude.snoop;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tratamento de texto: comando "fechar", limpeza para leitura em voz alta e divisão em frases. */
final class Text {

    /** Falas que, sozinhas, encerram a conversa e fecham o app. */
    private static final Set<String> CLOSE_EXACT = new HashSet<>(Arrays.asList(
            "fechar", "fecha", "feche", "fecho", "encerrar", "encerra", "encerre", "sair",
            "tchau", "tchau tchau", "tchau snoop", "ate mais", "ate logo", "ate mais snoop", "ate logo snoop",
            "pode fechar", "pode encerrar", "pode sair", "fechar app", "fechar o app"));

    /** "fechar o Snoop", "fecha Snoop"…: começa assim e tem até 3 palavras. */
    private static final Set<String> CLOSE_START = new HashSet<>(Arrays.asList(
            "fechar", "fecha", "feche", "encerrar", "encerra", "encerre"));

    private Text() {
    }

    /** Sem acentos, minúsculas, sem pontuação, espaços simples. */
    static String fold(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd} ]", " ");
        return t.trim().replaceAll("\\s+", " ");
    }

    static boolean isClose(String said) {
        String f = fold(said);
        if (f.isEmpty()) return false;
        if (CLOSE_EXACT.contains(f)) return true;
        String[] words = f.split(" ");
        return words.length <= 3 && CLOSE_START.contains(words[0]);
    }

    /** Remove o que não soa bem em voz alta: markdown, links, emojis e símbolos. */
    static String forSpeech(String s) {
        if (s == null) return "";
        String t = s;
        t = t.replaceAll("```[\\s\\S]*?```", " ");
        t = t.replace("`", "");
        t = t.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("https?://\\S+", "o link");
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        t = t.replaceAll("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+", "");
        t = t.replaceAll("[*_~|>]+", "");
        t = t.replace("°", " graus ");
        StringBuilder b = new StringBuilder(t.length());
        int i = 0;
        while (i < t.length()) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean emoji = cp >= 0x1F000 || type == Character.OTHER_SYMBOL || type == Character.SURROGATE
                    || cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F);
            if (emoji) continue;
            b.appendCodePoint(cp);
        }
        t = b.toString();
        t = t.replaceAll("\\s*\\n+\\s*", "\n");
        // Quebra de linha sem pontuação vira pausa curta.
        t = t.replaceAll("([^.!?:;,])\\n", "$1. ");
        t = t.replace('\n', ' ');
        return t.trim().replaceAll("\\s+", " ");
    }

    /** Divide em trechos de até {@code max} caracteres, preferindo o fim das frases. */
    static List<String> chunks(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String rest = text.trim();
        while (rest.length() > max) {
            int cut = -1;
            for (int i = Math.min(max, rest.length() - 1); i > max / 3; i--) {
                char ch = rest.charAt(i);
                if ((ch == '.' || ch == '!' || ch == '?' || ch == ';') && i + 1 < rest.length()
                        && rest.charAt(i + 1) == ' ') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut < 0) cut = rest.lastIndexOf(' ', max);
            if (cut <= 0) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /** Frases curtas primeiro: o relógio começa a falar mais rápido. */
    static List<String> sentences(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder cur = new StringBuilder();
        String[] parts = text.trim().split("(?<=[.!?])\\s+");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + 1 + p.length() > max) {
                out.addAll(chunks(cur.toString(), max));
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(p);
            // A primeira frase sai sozinha, para a voz começar logo.
            if (out.isEmpty() && cur.length() >= 40) {
                out.addAll(chunks(cur.toString(), max));
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.addAll(chunks(cur.toString(), max));
        return out;
    }

    static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 12345 -> "12.345". */
    static String number(long n) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%,d", n);
    }
}
