package com.eiclaude.relogio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reserva: reconhecimento offline com o Vosk (modelo pequeno em português, o mesmo do app do
 * celular). Usado só se o relógio não tiver um reconhecedor do sistema que funcione.
 */
final class OfflineVoice implements VoiceInput {

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE / 5; // 0,2 s
    private static final long NO_SPEECH_MS = 8000;
    private static final long END_SILENCE_MS = 1000;
    private static final long MAX_UTTERANCE_MS = 30000;

    private static Model model;
    private static boolean loading;
    private static final List<Runnable> waiting = new ArrayList<>();
    private static String loadError;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder said = new StringBuilder();

    private Callback callback;
    private int generation;
    private CaptureThread capture;

    private final Runnable noSpeech = () -> finish(true);
    private final Runnable endOfSpeech = () -> finish(false);
    private final Runnable tooLong = () -> finish(false);

    OfflineVoice(Context c) {
        context = c.getApplicationContext();
    }

    @Override
    public String id() {
        return "offline";
    }

    @Override
    public String label() {
        return "voz offline (Vosk)";
    }

    @Override
    public void start(Callback cb) {
        cancel();
        callback = cb;
        final int gen = ++generation;
        said.setLength(0);
        if (model != null) {
            begin(gen);
            return;
        }
        if (loadError != null) {
            callback = null;
            cb.onUnavailable(loadError);
            return;
        }
        cb.onStatus("Preparando a voz offline… (só na primeira vez)");
        waiting.add(() -> {
            if (gen != generation || callback == null) return;
            if (model != null) {
                begin(gen);
            } else {
                Callback c = callback;
                callback = null;
                c.onUnavailable(loadError != null ? loadError : "modelo de voz indisponível");
            }
        });
        if (loading) return;
        loading = true;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (Throwable ignored) {
            // o erro, se houver, aparece ao carregar o modelo
        }
        try {
            StorageService.unpack(context, "model-pt", "model",
                    loaded -> {
                        loading = false;
                        model = loaded;
                        runWaiting();
                    },
                    error -> {
                        loading = false;
                        loadError = "não consegui preparar o modelo de voz: " + error.getMessage();
                        runWaiting();
                    });
        } catch (Throwable t) {
            loading = false;
            loadError = "não consegui preparar o modelo de voz: " + t.getMessage();
            runWaiting();
        }
    }

    private static void runWaiting() {
        List<Runnable> copy = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable r : copy) r.run();
    }

    @SuppressLint("MissingPermission")
    private void begin(int gen) {
        Recognizer recognizer;
        try {
            recognizer = new Recognizer(model, SAMPLE_RATE);
        } catch (IOException | RuntimeException e) {
            Callback c = callback;
            callback = null;
            if (c != null) c.onUnavailable("falha ao iniciar o reconhecimento offline: " + e.getMessage());
            return;
        }
        AudioRecord record;
        try {
            int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bytes = Math.max(minBytes, CHUNK_SAMPLES * 2 * 2);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                throw new IOException("microfone indisponível");
            }
        } catch (Throwable t) {
            recognizer.close();
            Callback c = callback;
            callback = null;
            if (c != null) c.onError("Não consegui usar o microfone agora.");
            return;
        }
        capture = new CaptureThread(gen, recognizer, record);
        capture.start();
        main.postDelayed(noSpeech, NO_SPEECH_MS);
        main.postDelayed(tooLong, MAX_UTTERANCE_MS);
        if (callback != null) callback.onReady();
    }

    private void onPartial(int gen, String json) {
        if (gen != generation || callback == null) return;
        String t = field(json, "partial");
        if (t.isEmpty()) return;
        main.removeCallbacks(noSpeech);
        main.removeCallbacks(endOfSpeech);
        callback.onPartial(joined(t));
    }

    private void onResult(int gen, String json) {
        if (gen != generation || callback == null) return;
        String t = field(json, "text");
        if (t.isEmpty()) return;
        if (said.length() > 0) said.append(' ');
        said.append(t);
        main.removeCallbacks(noSpeech);
        main.removeCallbacks(endOfSpeech);
        callback.onPartial(said.toString());
        main.postDelayed(endOfSpeech, END_SILENCE_MS);
    }

    private void onCaptureError(int gen, String message) {
        if (gen != generation || callback == null) return;
        Callback c = callback;
        stopCapture();
        callback = null;
        c.onError("Falha no microfone: " + message);
    }

    private String joined(String partial) {
        return said.length() == 0 ? partial : said + " " + partial;
    }

    private void finish(boolean timedOutWithoutSpeech) {
        Callback c = callback;
        if (c == null) return;
        stopCapture();
        callback = null;
        String text = said.toString().trim();
        if (timedOutWithoutSpeech || text.isEmpty()) c.onNoSpeech();
        else c.onText(text);
    }

    private static String field(String json, String name) {
        try {
            String t = new JSONObject(json).optString(name, "");
            return t.replace("[unk]", " ").trim().replaceAll("\\s+", " ");
        } catch (Exception e) {
            return "";
        }
    }

    private void stopCapture() {
        main.removeCallbacks(noSpeech);
        main.removeCallbacks(endOfSpeech);
        main.removeCallbacks(tooLong);
        CaptureThread c = capture;
        capture = null;
        if (c != null) c.requestStop();
    }

    @Override
    public void cancel() {
        generation++;
        callback = null;
        stopCapture();
    }

    @Override
    public void release() {
        cancel();
        // O modelo fica carregado enquanto o app estiver aberto, para a próxima fala ser rápida.
    }

    /** Lê o microfone em blocos de 0,2 s e entrega ao reconhecedor. Libera tudo ao terminar. */
    private final class CaptureThread extends Thread {
        private final int gen;
        private final Recognizer recognizer;
        private final AudioRecord record;
        private volatile boolean stopRequested;

        CaptureThread(int gen, Recognizer recognizer, AudioRecord record) {
            super("EiClaude-relogio-captura");
            this.gen = gen;
            this.recognizer = recognizer;
            this.record = record;
        }

        void requestStop() {
            stopRequested = true;
        }

        @Override
        public void run() {
            short[] buffer = new short[CHUNK_SAMPLES];
            try {
                record.startRecording();
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IOException("o microfone não começou a gravar");
                }
                while (!stopRequested) {
                    int n = record.read(buffer, 0, buffer.length);
                    if (stopRequested) break;
                    if (n < 0) throw new IOException("erro " + n + " ao ler o microfone");
                    if (n == 0) continue;
                    if (recognizer.acceptWaveForm(buffer, n)) {
                        final String result = recognizer.getResult();
                        main.post(() -> onResult(gen, result));
                    } else {
                        final String partial = recognizer.getPartialResult();
                        main.post(() -> onPartial(gen, partial));
                    }
                }
            } catch (Throwable t) {
                if (!stopRequested) {
                    final String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    main.post(() -> onCaptureError(gen, msg));
                }
            } finally {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                    // já parado
                }
                try {
                    record.release();
                } catch (Throwable ignored) {
                    // já liberado
                }
                try {
                    recognizer.close();
                } catch (Throwable ignored) {
                    // já fechado
                }
            }
        }
    }
}
