package com.eiclaude.snoop;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;

/**
 * Grava a sua fala pelo microfone do relógio (AAC, leve para mandar pela conexão do celular)
 * e percebe sozinho quando você parou de falar, pelo volume do som.
 */
final class Recorder {

    interface Listener {
        /** Volume atual, de 0 a 1 (para as orelhas do Snoop reagirem). */
        void onLevel(float level);

        /** Terminou: {@code heard} diz se parece que houve fala. */
        void onDone(File file, boolean heard);

        void onError(String message);
    }

    static final String MIME = "audio/aac";

    private static final long TICK_MS = 70;
    private static final long CALIBRATE_MS = 350;
    private static final long END_SILENCE_MS = 1300;
    private static final long NO_SPEECH_MS = 7000;
    private static final long MAX_MS = 25000;
    private static final int MIN_THRESHOLD = 1500;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaRecorder recorder;
    private Listener listener;
    private File file;
    private long startedAt;
    private long lastLoudAt;
    private long floorSum;
    private int floorCount;
    private int loudInARow;
    private int peak;
    private boolean speech;
    private boolean running;

    private final Runnable poll = this::poll;

    Recorder(Context c) {
        context = c.getApplicationContext();
    }

    boolean isRunning() {
        return running;
    }

    void start(Listener l) {
        cancel();
        listener = l;
        file = new File(context.getCacheDir(), "fala.aac");
        if (file.exists()) //noinspection ResultOfMethodCallIgnored
            file.delete();
        try {
            recorder = create(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        } catch (Throwable first) {
            release();
            try {
                recorder = create(MediaRecorder.AudioSource.MIC);
            } catch (Throwable second) {
                release();
                l.onError("Não consegui abrir o microfone (" + second.getClass().getSimpleName() + ").");
                return;
            }
        }
        running = true;
        startedAt = SystemClock.uptimeMillis();
        lastLoudAt = startedAt;
        floorSum = 0;
        floorCount = 0;
        loudInARow = 0;
        peak = 0;
        speech = false;
        main.postDelayed(poll, TICK_MS);
    }

    private MediaRecorder create(int source) throws Exception {
        MediaRecorder r = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        recorder = r;
        r.setAudioSource(source);
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        r.setAudioChannels(1);
        r.setAudioSamplingRate(16000);
        r.setAudioEncodingBitRate(32000);
        r.setOutputFile(file.getAbsolutePath());
        r.prepare();
        r.start();
        return r;
    }

    private void poll() {
        if (!running || recorder == null) return;
        long now = SystemClock.uptimeMillis();
        long t = now - startedAt;
        int amp;
        try {
            amp = recorder.getMaxAmplitude();
        } catch (Throwable e) {
            amp = 0;
        }
        if (t < CALIBRATE_MS) {
            if (amp > 0) {
                floorSum += amp;
                floorCount++;
            }
        }
        int floor = floorCount == 0 ? 0 : (int) (floorSum / floorCount);
        int threshold = Math.max(MIN_THRESHOLD, Math.round(floor * 2.2f));
        peak = Math.max(peak, amp);
        listener.onLevel(Math.min(1f, amp / 12000f));

        if (t >= CALIBRATE_MS && amp > threshold) {
            loudInARow++;
            lastLoudAt = now;
            if (loudInARow >= 2) speech = true;
        } else {
            loudInARow = 0;
        }

        if (speech && now - lastLoudAt > END_SILENCE_MS) {
            finish(true);
            return;
        }
        if (!speech && t > NO_SPEECH_MS) {
            finish(peak > threshold);
            return;
        }
        if (t > MAX_MS) {
            finish(true);
            return;
        }
        main.postDelayed(poll, TICK_MS);
    }

    /** Você tocou no Snoop: para de gravar e manda o que tem. */
    void stopAndSend() {
        if (!running) return;
        finish(SystemClock.uptimeMillis() - startedAt > 600);
    }

    private void finish(boolean heard) {
        main.removeCallbacks(poll);
        running = false;
        boolean ok = true;
        try {
            if (recorder != null) recorder.stop();
        } catch (Throwable e) {
            ok = false; // gravou pouco demais
        }
        release();
        Listener l = listener;
        listener = null;
        boolean hasAudio = ok && file != null && file.exists() && file.length() > 1200;
        if (l != null) l.onDone(file, heard && hasAudio);
    }

    /** Para sem avisar ninguém. */
    void cancel() {
        main.removeCallbacks(poll);
        boolean was = running;
        running = false;
        listener = null;
        if (was) {
            try {
                if (recorder != null) recorder.stop();
            } catch (Throwable ignored) {
                // nada gravado
            }
        }
        release();
    }

    private void release() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Throwable ignored) {
                // já liberado
            }
        }
        recorder = null;
    }
}
