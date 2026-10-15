package com.eiclaude.snoop;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Toca a voz que vem do Gemini e informa o volume a cada instante,
 * para a boca do Snoop mexer junto com a fala.
 */
final class Player {

    interface Listener {
        void onLevel(float level);

        void onDone();
    }

    private static final int FRAME_MS = 40;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile AudioTrack track;
    private float[] levels = new float[0];
    private int framesPerLevel = 1;
    private Listener listener;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            AudioTrack t = track;
            Listener l = listener;
            if (t == null || l == null) return;
            int head;
            try {
                head = t.getPlaybackHeadPosition();
            } catch (Throwable e) {
                return;
            }
            int i = head / framesPerLevel;
            l.onLevel(i >= 0 && i < levels.length ? levels[i] : 0f);
            main.postDelayed(this, FRAME_MS);
        }
    };

    void play(Gemini.Audio audio, Listener l) {
        stop();
        final int my = ++generation;
        listener = l;
        final byte[] pcm = audio.pcm;
        final int rate = audio.rate > 0 ? audio.rate : 24000;
        framesPerLevel = Math.max(1, rate * FRAME_MS / 1000);
        levels = computeLevels(pcm, framesPerLevel);
        Thread th = new Thread(() -> {
            AudioTrack t = null;
            try {
                int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minBuf, rate / 4 * 2))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                t.play();
                track = t;
                main.post(tick);
                int chunk = framesPerLevel * 2;
                for (int off = 0; off < pcm.length && generation == my; off += chunk) {
                    int len = Math.min(chunk, pcm.length - off);
                    len -= len % 2;
                    if (len <= 0) break;
                    t.write(pcm, off, len);
                }
                long total = pcm.length / 2;
                long deadline = SystemClock.uptimeMillis() + 3000 + total * 1000 / rate;
                while (generation == my && t.getPlaybackHeadPosition() < total
                        && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(30);
                }
            } catch (Throwable ignored) {
                // sem áudio: termina do mesmo jeito
            } finally {
                if (t != null) {
                    try {
                        t.stop();
                    } catch (Throwable ignored) {
                        // já parado
                    }
                    t.release();
                }
                if (track == t) track = null;
                if (generation == my) {
                    main.post(() -> {
                        if (generation != my) return;
                        main.removeCallbacks(tick);
                        Listener done = listener;
                        listener = null;
                        if (done != null) done.onDone();
                    });
                }
            }
        }, "Snoop-voz");
        th.start();
    }

    /** Para sem avisar. */
    void stop() {
        generation++;
        main.removeCallbacks(tick);
        listener = null;
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (Throwable ignored) {
                // a thread libera
            }
        }
    }

    private static float[] computeLevels(byte[] pcm, int frames) {
        int n = Math.max(1, (pcm.length / 2 + frames - 1) / frames);
        float[] out = new float[n];
        for (int k = 0; k < n; k++) {
            int from = k * frames * 2;
            int to = Math.min(pcm.length - 1, from + frames * 2);
            double sum = 0;
            int count = 0;
            for (int i = from; i + 1 <= to; i += 2) {
                short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                sum += (double) s * s;
                count++;
            }
            double rms = count == 0 ? 0 : Math.sqrt(sum / count);
            out[k] = (float) Math.min(1.0, rms / 6000.0);
        }
        return out;
    }
}
