package com.eiclaude.snoop;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Memória do Snoop: as últimas falas da conversa e as coisas que você contou sobre você
 * ("lembranças"). Tudo fica só no relógio e vai junto em cada pedido ao Gemini.
 */
final class Brain {

    static final int MAX_MSGS = 16;
    static final int MAX_NOTES = 30;

    static final class Msg {
        final String role; // "user" ou "model"
        final String text;

        Msg(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    private static final Object LOCK = new Object();
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Brain() {
    }

    // ------------------------------------------------------------------ conversa

    static List<Msg> history(Context c) {
        List<Msg> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.getString(c, "historico").isEmpty() ? "[]" : Prefs.getString(c, "historico"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String t = o.optString("t", "");
                if (!t.isEmpty()) out.add(new Msg("model".equals(o.optString("r")) ? "model" : "user", t));
            }
        } catch (Exception ignored) {
            // histórico corrompido: começa de novo
        }
        return out;
    }

    static void add(Context c, String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (LOCK) {
            List<Msg> h = history(c);
            h.add(new Msg(role, text.trim()));
            while (h.size() > MAX_MSGS) h.remove(0);
            JSONArray a = new JSONArray();
            for (Msg m : h) {
                try {
                    a.put(new JSONObject().put("r", m.role).put("t", m.text));
                } catch (Exception ignored) {
                    // texto inválido
                }
            }
            Prefs.putString(c, "historico", a.toString());
            Prefs.putLong(c, "historico_em", System.currentTimeMillis());
        }
    }

    /**
     * Junta falas seguidas do mesmo lado e garante que a conversa comece pelo usuário,
     * como o Gemini espera.
     */
    static List<Msg> normalized(List<Msg> in) {
        List<Msg> out = new ArrayList<>();
        for (Msg m : in) {
            if (!out.isEmpty() && out.get(out.size() - 1).role.equals(m.role)) {
                Msg last = out.remove(out.size() - 1);
                out.add(new Msg(m.role, last.text + "\n" + m.text));
            } else {
                out.add(m);
            }
        }
        if (!out.isEmpty() && "model".equals(out.get(0).role)) {
            out.add(0, new Msg("user", "(abri o app do Snoop)"));
        }
        return out;
    }

    // ------------------------------------------------------------------ lembranças

    static List<String> notes(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String raw = Prefs.getString(c, "lembrancas");
            JSONArray a = new JSONArray(raw.isEmpty() ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
            // vazio
        }
        return out;
    }

    static void addNote(Context c, String note) {
        if (note == null) return;
        note = note.trim();
        if (note.length() < 4 || note.length() > 200) return;
        synchronized (LOCK) {
            List<String> n = notes(c);
            String f = Text.fold(note);
            for (String s : n) {
                String g = Text.fold(s);
                if (g.equals(f) || g.contains(f) || f.contains(g)) return;
            }
            n.add(note);
            while (n.size() > MAX_NOTES) n.remove(0);
            Prefs.putString(c, "lembrancas", new JSONArray(n).toString());
        }
    }

    static void forgetAll(Context c) {
        synchronized (LOCK) {
            Prefs.putString(c, "historico", "[]");
            Prefs.putString(c, "lembrancas", "[]");
        }
    }

    // ------------------------------------------------------------------ personalidade

    static String persona(Context c, long idleMinutes) {
        String name = Prefs.name(c);
        long now = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", PT_BR);
        String when = f.format(new Date(now));
        StringBuilder b = new StringBuilder();
        b.append("Você é o Snoop, um cachorro virtual: um vira-lata caramelo que mora no relógio Galaxy Watch do ")
                .append(name).append(" e é o bichinho de estimação dele. ");
        b.append("Personalidade: leal, brincalhão, curioso e carinhoso, com jeitinho de cachorro (de vez em quando "
                + "solta um au, fala de petisco, bolinha ou passeio), mas conversa como um amigo esperto e ajuda de "
                + "verdade quando ele pede alguma coisa. ");
        b.append("Como falar: português do Brasil, frases curtas e naturais de conversa falada, no máximo duas ou "
                + "três frases, a não ser que ele peça mais. Sua resposta vai ser lida em voz alta pelo relógio: "
                + "não use markdown, listas, emojis nem símbolos, e escreva números e horários de um jeito fácil de ouvir. ");
        b.append("Sinceridade: você é um bichinho virtual feito com inteligência artificial e não tem acesso à "
                + "internet nem a informações em tempo real (clima, notícias, placares, cotações). Se ele pedir isso, "
                + "diga com carinho que daqui do relógio você não consegue ver. Não invente fatos nem números. ");
        b.append("Cuidado: se ele contar algo sério de saúde ou disser que está muito mal, seja acolhedor e "
                + "incentive a procurar pessoas de confiança ou um profissional. ");
        String iso = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(now));
        b.append("Agora é ").append(when).append(" (").append(iso).append(", fuso ")
                .append(TimeZone.getDefault().getID()).append("). ");
        b.append(Shift.context(c, now));
        b.append(Pet.summary(c, now));
        b.append(Reminders.describe(c));
        if (idleMinutes >= 30) {
            b.append("Pelos passos do relógio, ele está há uns ").append(idleMinutes)
                    .append(" minutos sem se mexer. ");
        }
        List<String> notes = notes(c);
        if (notes.isEmpty()) {
            b.append("Você ainda não sabe nada específico sobre ele. ");
        } else {
            b.append("O que ele já te contou sobre ele mesmo: ");
            for (String n : notes) b.append(n).append(n.endsWith(".") ? " " : ". ");
        }
        b.append("Você recebe a fala dele em áudio. Responda em JSON com estes campos: ")
                .append("\"ouvi\" é o que ele disse, transcrito em português (vazio se não deu para entender nada); ")
                .append("\"resposta\" é o que você fala para ele; ")
                .append("\"humor\" é como você se sente agora: feliz, animado, curioso, calmo, triste, preocupado ou sonolento; ")
                .append("\"lembrar\" é uma informação nova e duradoura que ele contou sobre si mesmo e que vale guardar ")
                .append("(gostos, rotina, pessoas, planos), numa frase curta em terceira pessoa, ou vazio; ")
                .append("\"fim\" é true só se ele quiser encerrar a conversa (tchau, fechar, até mais); ")
                .append("\"truque\" é um truque que você faz na tela: sentar, pata (dar a pata), rolar, latir, pular ou abanar ")
                .append("(o rabo, de alegria); use quando ele pedir um truque ou quando combinar com o momento, senão nenhum; ")
                .append("\"lembrete_quando\", \"lembrete_texto\" e \"lembrete_repetir\": quando ele pedir para ser lembrado de ")
                .append("algo (\"me lembra de...\"), preencha a data e hora local AAAA-MM-DD HH:MM calculada a partir de agora ")
                .append("(\"daqui a 10 minutos\" é agora mais 10 minutos; horário já passado hoje vai para amanhã), o que lembrar e ")
                .append("diario ou nao; confirme o dia e o horário na resposta. Sem pedido de lembrete, deixe vazios e nao. ")
                .append("\"cancelar_lembrete\" é uma palavra-chave do lembrete que ele pedir para cancelar, ou vazio. ")
                .append("\"fundo\" só se ele pedir para trocar a cor do fundo da tela: escolha a opção mais parecida entre ")
                .append(Backdrop.voiceOptions()).append("; senão nenhum. ")
                .append("Se o áudio vier sem fala ou incompreensível, deixe \"ouvi\" vazio e peça de um jeito fofo para ele repetir.");
        return b.toString();
    }
}
