package com.eiclaude.snoop;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.Locale;

/** Voz do Snoop pelo leitor de texto do próprio relógio (rápida e sem gastar o plano grátis). */
final class Speaker implements TextToSpeech.OnInitListener {

    interface Listener {
        /** Terminou de falar (ou não havia como falar). Sempre na thread principal. */
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
                Prefs.setTtsStatus(context, "leitor de texto indisponível neste relógio");
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
                    ? "leitor do relógio em português"
                    : "leitor do relógio sem português");
            try {
                // Um tom um pouco mais agudo e animado, de cachorro novo.
                tts.setPitch(1.2f);
                tts.setSpeechRate(1.05f);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } catch (Throwable ignored) {
                // usa o padrão
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

    /** Dá para usar a voz do relógio em português? (Enquanto inicia, considera que sim.) */
    boolean isGood() {
        return !broken && (!ready || portuguese);
    }

    void speak(String text) {
        generation++;
        active = true;
        if (broken) {
            main.post(this::finishNow);
            return;
        }
        if (!ready) {
            pending = text; // o leitor ainda está iniciando
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
                tts.speak(parts.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, new Bundle(), id);
            }
            lastUtterance = "u" + generation + "_" + (parts.size() - 1);
        } catch (Throwable t) {
            main.post(this::finishNow);
            return;
        }
        // Segurança: se o leitor nunca avisar que terminou, a conversa não trava.
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

    /** Para de falar sem avisar. */
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
