package com.eiclaude.snoop;

import android.content.Context;

import java.util.Calendar;
import java.util.Random;

/**
 * Frases que o Snoop usa quando chama você por conta própria. Ficam no relógio:
 * não gastam o plano grátis e funcionam até sem internet.
 */
final class Phrases {

    private static final String[] IDLE = {
            "{nome}, faz {min} minutos que você não se mexe. Bora dar uma voltinha comigo?",
            "Au! Tô com as patas formigando. Que tal levantar um pouquinho, {nome}?",
            "Ei, {nome}! Cachorro parado vira tapete. Vamos esticar as pernas?",
            "Snoop aqui! Já faz um tempão sentadinho. Uma caminhada curta faz bem pra nós dois.",
            "{nome}, levanta, pega uma água e dá uns passos. Eu fico abanando o rabo daqui!",
            "Hora do passeio! Nem precisa ser longe, {nome}, só uns minutinhos.",
            "Psiu, {nome}... faz {min} minutos sem movimento. Que tal uma pausa pra se mexer?",
            "Tô entediado! Bora andar um pouco, {nome}? Prometo não puxar a coleira.",
    };

    private static final String[] PRAISE = {
            "Isso aí, {nome}! Se mexeu! Tô abanando o rabo de alegria!",
            "Boa! Passeio aprovado pelo Snoop. Au au!",
            "Olha você aí andando! Orgulho do meu humano.",
            "Mandou bem, {nome}! Já me sinto mais animado também.",
    };

    private static final String[] GREETING = {
            "{saudacao}, {nome}! Como você tá hoje?",
            "{saudacao}, {nome}! Descansou bem? Me conta como vai ser o seu dia.",
            "Au au! {saudacao}, {nome}! Tava te esperando. Tudo certo por aí?",
            "{saudacao}! O Snoop já tá de pé e animado. E você, {nome}, como tá?",
    };

    private static final String[] MISS = {
            "Ei, {nome}, tô com saudade! Vem conversar comigo um pouquinho?",
            "Faz tempo que você não me chama, {nome}. Tô aqui balançando o rabo esperando!",
            "Snoop chamando! Bora bater um papo, {nome}?",
    };

    private static final Random RANDOM = new Random();

    private Phrases() {
    }

    static String idle(Context c, long minutes) {
        return pick(c, "idle", IDLE).replace("{min}", String.valueOf(minutes));
    }

    static String praise(Context c) {
        return pick(c, "praise", PRAISE);
    }

    static String greeting(Context c) {
        return pick(c, "greet", GREETING).replace("{saudacao}", salutation(System.currentTimeMillis()));
    }

    static String miss(Context c) {
        return pick(c, "miss", MISS);
    }

    static String salutation(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 12) return "Bom dia";
        if (h >= 12 && h < 18) return "Boa tarde";
        return "Boa noite";
    }

    /** Escolhe uma frase sem repetir a última do mesmo tipo. */
    private static String pick(Context c, String pool, String[] options) {
        int last = Prefs.getInt(c, "frase_" + pool, -1);
        int i = RANDOM.nextInt(options.length);
        if (i == last) i = (i + 1) % options.length;
        Prefs.putInt(c, "frase_" + pool, i);
        return options[i].replace("{nome}", Prefs.name(c));
    }
}
