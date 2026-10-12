package com.eiclaude.relogio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconhecimento do sistema (SpeechRecognizer), sem sair da tela do app.
 * Com {@code component == null} usa o serviço padrão; senão, um serviço específico — útil no
 * Galaxy Watch, onde às vezes nenhum serviço vem marcado como padrão.
 */
final class SystemVoice implements VoiceInput {

    private static final long START_TIMEOUT_MS = 7000;

    private final Context context;
    private final ComponentName component;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private Callback callback;
    private int generation;
    private boolean ready;

    SystemVoice(Context c, ComponentName component) {
        this.context = c.getApplicationContext();
        this.component = component;
    }

    /** Serviços de reconhecimento instalados, os do Google e da Samsung primeiro. */
    static List<ComponentName> services(Context c) {
        List<ComponentName> google = new ArrayList<>();
        List<ComponentName> samsung = new ArrayList<>();
        List<ComponentName> others = new ArrayList<>();
        try {
            List<ResolveInfo> list = c.getPackageManager()
                    .queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            for (ResolveInfo ri : list) {
                ServiceInfo si = ri.serviceInfo;
                if (si == null) continue;
                ComponentName cn = new ComponentName(si.packageName, si.name);
                if (si.packageName.contains("google")) google.add(cn);
                else if (si.packageName.contains("samsung")) samsung.add(cn);
                else others.add(cn);
            }
        } catch (Throwable ignored) {
            // sem lista
        }
        List<ComponentName> out = new ArrayList<>(google);
        out.addAll(samsung);
        out.addAll(others);
        return out;
    }

    @Override
    public String id() {
        return component == null ? "sistema" : "sistema:" + component.flattenToShortString();
    }

    @Override
    public String label() {
        return component == null
                ? "reconhecedor padrão do sistema"
                : "reconhecedor do sistema (" + component.getPackageName() + ")";
    }

    @Override
    public void start(Callback cb) {
        cancel();
        callback = cb;
        ready = false;
        final int gen = ++generation;
        try {
            if (recognizer == null) {
                recognizer = component == null
                        ? SpeechRecognizer.createSpeechRecognizer(context)
                        : SpeechRecognizer.createSpeechRecognizer(context, component);
            }
            recognizer.setRecognitionListener(new Listener(gen));
            recognizer.startListening(intent());
        } catch (Throwable t) {
            destroy();
            callback = null;
            cb.onUnavailable(String.valueOf(t.getMessage()));
            return;
        }
        main.postDelayed(() -> {
            if (gen == generation && !ready && callback != null) {
                Callback c = callback;
                callback = null;
                destroy();
                c.onUnavailable("o serviço de voz não respondeu");
            }
        }, START_TIMEOUT_MS);
    }

    private Intent intent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        return i;
    }

    @Override
    public void cancel() {
        generation++;
        callback = null;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Throwable t) {
                destroy();
            }
        }
    }

    @Override
    public void release() {
        cancel();
        destroy();
    }

    private void destroy() {
        if (recognizer == null) return;
        try {
            recognizer.destroy();
        } catch (Throwable ignored) {
            // já destruído
        }
        recognizer = null;
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0) == null) return "";
        return list.get(0).trim();
    }

    private final class Listener implements RecognitionListener {
        private final int gen;

        Listener(int gen) {
            this.gen = gen;
        }

        private Callback current() {
            return gen == generation ? callback : null;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            Callback c = current();
            if (c == null || ready) return;
            ready = true;
            c.onReady();
        }

        @Override
        public void onBeginningOfSpeech() {
            onReadyForSpeech(null);
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // sem uso
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
            // sem uso
        }

        @Override
        public void onEndOfSpeech() {
            // espera o resultado
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            Callback c = current();
            if (c == null) return;
            String t = first(partialResults);
            if (!t.isEmpty()) c.onPartial(t);
        }

        @Override
        public void onResults(Bundle results) {
            Callback c = current();
            if (c == null) return;
            callback = null;
            ready = true;
            String t = first(results);
            if (t.isEmpty()) c.onNoSpeech();
            else c.onText(t);
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
            // sem uso
        }

        @Override
        public void onError(int error) {
            Callback c = current();
            if (c == null) return;
            callback = null;
            boolean wasReady = ready;
            ready = true; // encerra o tempo-limite de início
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    c.onNoSpeech();
                    return;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    c.onError("Sem internet para entender a voz. Confira a conexão do relógio.");
                    return;
                case SpeechRecognizer.ERROR_AUDIO:
                    destroy();
                    c.onError("Não consegui usar o microfone agora.");
                    return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    destroy();
                    if (wasReady) c.onError("O reconhecimento de voz está ocupado. Toque para tentar de novo.");
                    else c.onUnavailable("ocupado");
                    return;
                default:
                    destroy();
                    if (wasReady) c.onError("Erro " + error + " no reconhecimento de voz.");
                    else c.onUnavailable("erro " + error);
            }
        }
    }
}
