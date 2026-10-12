package com.eiclaude.relogio;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.Locale;

/** Leitura em voz alta das respostas (leitor de texto do próprio relógio). */
final class Speaker implements TextToSpeech.OnInitListener {

    interface Listener {
        /** Terminou de falar (ou não havia como falar). Sempre chamado na thread principal. */
        void onSpeakDone();
    }

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final int CHUNK = 280;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextToSpeech tts;

    private boolean ready;
    private boolean broken;
    private boolean portuguese;
    private String pending;
    private int generation;
    private String lastUtterance;
    private boolean active;

    private final Runnable watchdog = this::finishNow;

    Speaker(Context c, Listener l) {
        context = c.getApplicationContext();
        listener = l;
        tts = new TextToSpeech(context, this);
    }

    @Override
    public void onInit(int status) {
        main.post(() -> {
            if (status != TextToSpeech.SUCCESS) {
                broken = true;
                Prefs.setTtsStatus(context, "indisponível neste relógio");
                if (pending != null) {
                    pending = null;
                    finishNow();
                }
                return;
            }
            int r = tts.setLanguage(PT_BR);
            if (r < TextToSpeech.LANG_AVAILABLE) r = tts.setLanguage(Locale.forLanguageTag("pt"));
            portuguese = r >= TextToSpeech.LANG_AVAILABLE;
            Prefs.setTtsStatus(context, portuguese
                    ? "português"
                    : "sem voz em português (usando a voz padrão)");
            try {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } catch (Throwable ignored) {
                // usa os atributos padrão
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    // nada
                }

                @Override
                public void onDone(String id) {
                    main.post(() -> onUtteranceEnd(id));
                }

                @Override
                @Deprecated
                public void onError(String id) {
                    main.post(() -> onUtteranceEnd(id));
                }

                @Override
                public void onError(String id, int errorCode) {
                    main.post(() -> onUtteranceEnd(id));
                }
            });
            ready = true;
            if (pending != null) {
                String p = pending;
                pending = null;
                speakNow(p);
            }
        });
    }

    /** O leitor está funcionando (ou ainda iniciando)? */
    boolean isUsable() {
        return !broken;
    }

    boolean isPortuguese() {
        return portuguese;
    }

    boolean isSpeaking() {
        return active;
    }

    void speak(String text) {
        generation++;
        active = true;
        if (broken) {
            main.post(this::finishNow);
            return;
        }
        if (!ready) {
            pending = text; // o leitor ainda está iniciando (pode levar alguns segundos após ligar o relógio)
            main.removeCallbacks(watchdog);
            main.postDelayed(watchdog, 20000 + 120L * text.length());
            return;
        }
        speakNow(text);
    }

    private void speakNow(String text) {
        List<String> parts = Text.sentences(text, CHUNK);
        if (parts.isEmpty()) {
            main.post(this::finishNow);
            return;
        }
        try {
            tts.stop();
            for (int i = 0; i < parts.size(); i++) {
                String id = "u" + generation + "_" + i;
                Bundle params = new Bundle();
                tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, id);
            }
            lastUtterance = "u" + generation + "_" + (parts.size() - 1);
        } catch (Throwable t) {
            main.post(this::finishNow);
            return;
        }
        // Segurança: se o leitor nunca avisar que terminou, a conversa não fica travada.
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 8000 + 150L * text.length());
    }

    private void onUtteranceEnd(String id) {
        if (id == null || !id.equals(lastUtterance)) return;
        finishNow();
    }

    private void finishNow() {
        main.removeCallbacks(watchdog);
        lastUtterance = null;
        pending = null;
        if (!active) return;
        active = false;
        listener.onSpeakDone();
    }

    /** Para de falar sem avisar o ouvinte. */
    void stop() {
        generation++;
        main.removeCallbacks(watchdog);
        lastUtterance = null;
        pending = null;
        active = false;
        try {
            tts.stop();
        } catch (Throwable ignored) {
            // já parado
        }
    }

    void shutdown() {
        stop();
        try {
            tts.shutdown();
        } catch (Throwable ignored) {
            // já desligado
        }
    }
}
