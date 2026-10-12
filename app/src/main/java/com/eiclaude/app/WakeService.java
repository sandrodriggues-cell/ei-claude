package com.eiclaude.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Serviço em primeiro plano: escuta o microfone, reconhece a frase de ativação no próprio
 * aparelho (Vosk, sem internet) e abre o app do Claude quando ouve a frase.
 */
public class WakeService extends Service {

    static final String ACTION_START = "com.eiclaude.app.START";
    static final String ACTION_STOP = "com.eiclaude.app.STOP";
    static final String ACTION_RELOAD = "com.eiclaude.app.RELOAD";

    static final String CLAUDE_PACKAGE = "com.anthropic.claude";

    private static final String CHANNEL_LISTEN = "escuta";
    private static final String CHANNEL_ALERT = "avisos";
    private static final int NOTIF_LISTEN = 1;
    private static final int NOTIF_ALERT = 2;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE / 5;  // 0,2 s de áudio por leitura
    private static final long MIN_PAUSE_MS = 8000;             // pausa mínima depois de abrir o Claude
    private static final long RECHECK_MS = 4000;
    private static final long RETRY_MS = 5000;
    private static final long COOLDOWN_MS = 4000;
    private static final int HOTWORD_SOURCE = 1999;           // MediaRecorder.AudioSource.HOTWORD (oculta)

    /** Ligação com a tela do app (mesmo processo; sempre chamada na thread principal). */
    interface UiListener {
        void onStatus(String status);

        void onHeard(String text);

        void onPhraseInfo(String info);

        void onAssist(String text);
    }

    static volatile UiListener uiListener;
    static volatile WakeService current;
    static volatile boolean running;
    static volatile boolean testMode;
    static volatile String lastStatus = "Desligado";
    static volatile String lastPhraseInfo = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> ownSessionIds = new ArrayList<>();
    private final Runnable resumeCheck = this::tryResume;
    private final Runnable retryStart = this::ensureListening;

    private Model model;
    private boolean loadingModel;
    private CaptureThread capture;
    private int generation;
    private boolean sessionTestMode;
    private List<String> activePhrases = new ArrayList<>();
    private boolean pausedForClaude;
    private long lastTriggerAt;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        createChannels(this);
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (Throwable ignored) {
            // Se falhar aqui, o erro aparece de forma clara ao carregar o modelo.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            Prefs.setEnabled(this, false);
            shutdownAll();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!enterForeground()) {
            // O Android não deixou usar o microfone a partir do segundo plano
            // (por exemplo, quando o próprio sistema reinicia o serviço).
            running = false;
            showAlert(this, "Toque para religar a escuta do Ei Claude.");
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        cancelAlert(this);

        if (ACTION_RELOAD.equals(action)) {
            pausedForClaude = false;
            handler.removeCallbacks(resumeCheck);
            stopCapture();
        }
        ensureListening();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdownAll();
        if (current == this) current = null;
        super.onDestroy();
    }

    /** O Claude foi fechado pelo comando de voz: tenta voltar a escutar logo. */
    static void requestResumeSoon() {
        WakeService s = current;
        if (s == null) return;
        s.handler.removeCallbacks(s.resumeCheck);
        s.handler.postDelayed(s.resumeCheck, 1500);
    }

    /** Repassa à tela do app o que o serviço de acessibilidade fez. */
    static void notifyAssist(String text) {
        UiListener l = uiListener;
        if (l != null) l.onAssist(text);
    }

    // ---------------------------------------------------------------- primeiro plano

    private boolean enterForeground() {
        Notification n = buildListenNotification(lastStatus);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_LISTEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_LISTEN, n);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void shutdownAll() {
        running = false;
        pausedForClaude = false;
        handler.removeCallbacks(resumeCheck);
        handler.removeCallbacks(retryStart);
        stopCapture();
        if (model != null) {
            model.close();
            model = null;
        }
        setStatus("Desligado");
    }

    // ---------------------------------------------------------------- escuta

    private void ensureListening() {
        handler.removeCallbacks(retryStart);
        if (!running || pausedForClaude || capture != null) return;
        if (model == null) {
            loadModel();
            return;
        }
        if (otherAppIsRecording()) {
            setStatus("Aguardando: outro app está usando o microfone.");
            handler.postDelayed(retryStart, RECHECK_MS);
            return;
        }
        startCapture();
    }

    private void loadModel() {
        if (loadingModel) return;
        loadingModel = true;
        setStatus("Preparando o reconhecimento de voz…");
        StorageService.unpack(this, "model-pt", "model",
                loaded -> {
                    loadingModel = false;
                    if (!running) {
                        loaded.close();
                        return;
                    }
                    model = loaded;
                    ensureListening();
                },
                error -> {
                    loadingModel = false;
                    setStatus("Não consegui preparar o modelo de voz: " + error.getMessage());
                });
    }

    @SuppressLint("MissingPermission")
    private void startCapture() {
        sessionTestMode = testMode;

        Recognizer recognizer;
        try {
            if (sessionTestMode) {
                activePhrases = new ArrayList<>();
                recognizer = new Recognizer(model, SAMPLE_RATE);
            } else {
                activePhrases = resolvePhrases();
                if (activePhrases.isEmpty()) {
                    setStatus("Nenhuma frase válida. Abra o app e ajuste a frase de ativação.");
                    return;
                }
                JSONArray grammar = new JSONArray();
                for (String p : activePhrases) grammar.put(p);
                grammar.put("[unk]");
                recognizer = new Recognizer(model, SAMPLE_RATE, grammar.toString());
                recognizer.setWords(true);
            }
        } catch (IOException e) {
            setStatus("Falha ao iniciar o reconhecimento: " + e.getMessage());
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
            setStatus("Microfone indisponível agora. Tentando de novo…");
            handler.postDelayed(retryStart, RETRY_MS);
            return;
        }

        rememberSession(record.getAudioSessionId());
        generation++;
        capture = new CaptureThread(generation, recognizer, record, sessionTestMode);
        capture.start();
        acquireWakeLock();

        if (sessionTestMode) {
            setStatus("Modo teste: fale e veja abaixo o que o app entende.");
        } else {
            setStatus("Ouvindo. Diga \"" + activePhrases.get(0) + "\".");
        }
    }

    private void stopCapture() {
        CaptureThread c = capture;
        capture = null;
        generation++; // descarta resultados antigos que ainda estejam na fila
        if (c != null) {
            c.requestStop();
            try {
                c.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseWakeLock();
    }

    private void rememberSession(int sessionId) {
        ownSessionIds.add(sessionId);
        while (ownSessionIds.size() > 8) ownSessionIds.remove(0);
    }

    /** Outro app gravando agora? (Não conta a detecção de "Ok Google"/"Oi Bixby" do sistema.) */
    private boolean otherAppIsRecording() {
        return otherAppRecording(this, ownSessionIds);
    }

    /** Versão para fora do serviço (thread principal): desconta a própria escuta, se estiver ligada. */
    static boolean otherAppRecording(Context c) {
        WakeService s = current;
        List<Integer> own = s != null ? new ArrayList<>(s.ownSessionIds) : new ArrayList<>();
        return otherAppRecording(c, own);
    }

    private static boolean otherAppRecording(Context ctx, List<Integer> ownSessions) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return false;
        try {
            List<AudioRecordingConfiguration> configs = am.getActiveRecordingConfigurations();
            if (configs == null) return false;
            for (AudioRecordingConfiguration c : configs) {
                if (ownSessions.contains(c.getClientAudioSessionId())) continue;
                if (c.getClientAudioSource() == HOTWORD_SOURCE) continue;
                return true;
            }
        } catch (Throwable ignored) {
            // Sem a informação, segue normalmente.
        }
        return false;
    }

    // ---------------------------------------------------------------- frase

    private List<String> resolvePhrases() {
        String entry = Prefs.getPhrase(this);
        boolean automatic = entry.trim().isEmpty();
        List<String> wanted = automatic ? Arrays.asList(Phrase.CANDIDATES) : Phrase.variants(entry);

        List<String> chosen = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        boolean couldNotCheck = false;

        for (String phrase : wanted) {
            boolean allKnown = true;
            for (String w : Phrase.words(phrase)) {
                int k = VoskExtras.knows(model, w);
                if (k < 0) {
                    couldNotCheck = true;
                } else if (k == 0) {
                    allKnown = false;
                    if (!unknown.contains(w)) unknown.add(w);
                }
            }
            if (allKnown) {
                chosen.add(phrase);
                if (automatic) break;
            }
        }

        StringBuilder info = new StringBuilder();
        if (chosen.isEmpty()) {
            info.append("Nenhuma frase pode ser usada: o modelo de voz não conhece ")
                    .append(quoteList(unknown))
                    .append(". Ligue o modo teste para ver como ele escreve o que você fala.");
        } else {
            info.append("Frase em uso: ").append(quoteList(chosen)).append('.');
            if (!unknown.isEmpty()) {
                info.append(" O modelo de voz não conhece ").append(quoteList(unknown));
                info.append(automatic
                        ? ", por isso o modo automático escolheu essa frase."
                        : "; as frases com essas palavras foram deixadas de lado.");
            }
            if (couldNotCheck) {
                info.append(" Não deu para conferir o vocabulário. Se o app não reagir, use o modo teste.");
            }
        }
        lastPhraseInfo = info.toString();
        UiListener l = uiListener;
        if (l != null) l.onPhraseInfo(lastPhraseInfo);
        return chosen;
    }

    private static String quoteList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(i == items.size() - 1 ? " e " : ", ");
            sb.append('"').append(items.get(i)).append('"');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- resultados

    private void onResult(int gen, String json) {
        if (gen != generation) return;
        String text = Phrase.clean(optString(json, "text"));

        if (sessionTestMode) {
            if (!text.isEmpty()) postHeard(text);
            return;
        }
        if (text.isEmpty()) return;

        for (String phrase : activePhrases) {
            if (Phrase.contains(text, phrase)) {
                float conf = minConfidence(json, Phrase.words(phrase));
                float need = Prefs.threshold(this);
                postHeard(String.format(Locale.ROOT, "%s  ·  confiança %d%% (mínimo %d%%)",
                        text, Math.round(conf * 100), Math.round(need * 100)));
                if (conf >= need) onWake();
                return;
            }
        }
        postHeard(text);
    }

    private void onPartial(int gen, String json) {
        if (gen != generation || !sessionTestMode) return;
        String text = Phrase.clean(optString(json, "partial"));
        if (!text.isEmpty()) postHeard(text + " …");
    }

    private void onCaptureError(int gen, String message) {
        if (gen != generation) return;
        capture = null;
        releaseWakeLock();
        setStatus("O microfone falhou (" + message + "). Tentando de novo…");
        handler.postDelayed(retryStart, RETRY_MS);
    }

    // ---------------------------------------------------------------- disparo

    private void onWake() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTriggerAt < COOLDOWN_MS) return;
        lastTriggerAt = now;

        stopCapture(); // libera o microfone para o Claude
        pausedForClaude = true;
        vibrate();
        ClaudeAssistService.beginSession(this);

        if (launchClaude(this)) {
            setStatus("Abrindo o Claude… A escuta volta quando o microfone ficar livre.");
        } else {
            showOpenClaudeNotification(this);
            setStatus("Falta liberar \"Aparecer sobre outros apps\". Toque no aviso para abrir o Claude.");
        }

        handler.removeCallbacks(resumeCheck);
        handler.postDelayed(resumeCheck, MIN_PAUSE_MS);
    }

    /**
     * Abre a tela invisível que acende a tela, pede o desbloqueio e abre o Claude.
     * Devolve false se o Android não deixou abrir a partir do segundo plano.
     */
    static boolean launchClaude(Context c) {
        Intent i = new Intent(c, LaunchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (Settings.canDrawOverlays(c)) {
            try {
                c.startActivity(i);
                return true;
            } catch (Throwable ignored) {
                // tenta pelo serviço de acessibilidade abaixo
            }
        }
        ClaudeAssistService assist = ClaudeAssistService.instance;
        if (assist != null) {
            try {
                assist.startActivity(i);
                return true;
            } catch (Throwable ignored) {
                // sem como abrir daqui
            }
        }
        return false;
    }

    /**
     * Pedido do relógio: libera o microfone e pausa a escuta, como ao ouvir a frase de ativação.
     * Chamar na thread principal.
     */
    static void pauseForClaude() {
        WakeService s = current;
        if (s == null || !running) return;
        s.lastTriggerAt = SystemClock.elapsedRealtime();
        s.stopCapture();
        s.pausedForClaude = true;
        s.setStatus("Abrindo o Claude pelo relógio… A escuta volta quando o microfone ficar livre.");
        s.handler.removeCallbacks(s.resumeCheck);
        s.handler.postDelayed(s.resumeCheck, MIN_PAUSE_MS);
    }

    /** Só volta a escutar quando nenhum outro app (o Claude, por exemplo) estiver gravando. */
    private void tryResume() {
        if (!running || !pausedForClaude) return;
        if (otherAppIsRecording()) {
            setStatus("Em pausa enquanto outro app usa o microfone.");
            handler.postDelayed(resumeCheck, RECHECK_MS);
            return;
        }
        pausedForClaude = false;
        ensureListening();
    }

    private void vibrate() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
            // vibrar é opcional
        }
    }

    // ---------------------------------------------------------------- status e avisos

    private void setStatus(String status) {
        lastStatus = status;
        if (running) notifySafely(this, NOTIF_LISTEN, buildListenNotification(status));
        UiListener l = uiListener;
        if (l != null) l.onStatus(status);
    }

    private void postHeard(String text) {
        UiListener l = uiListener;
        if (l != null) l.onHeard(text);
    }

    private Notification buildListenNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, WakeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_LISTEN)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("Ei Claude")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder((Icon) null, "Desligar", stopPi).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    static void showOpenClaudeNotification(Context c) {
        createChannels(c);
        Intent launch = new Intent(c, LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 3, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("Ei Claude")
                .setContentText("Toque para abrir o Claude.")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        notifySafely(c, NOTIF_ALERT, n);
    }

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel listen = new NotificationChannel(CHANNEL_LISTEN, "Escuta ativa",
                NotificationManager.IMPORTANCE_LOW);
        listen.setDescription("Aviso fixo enquanto o app espera a frase de ativação");
        listen.setShowBadge(false);
        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "Avisos",
                NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Quando o app precisa de um toque seu");
        nm.createNotificationChannel(listen);
        nm.createNotificationChannel(alert);
    }

    /** Aviso que, ao ser tocado, abre o app e religa a escuta. */
    static void showAlert(Context c, String text) {
        createChannels(c);
        Intent open = new Intent(c, MainActivity.class)
                .setAction(MainActivity.ACTION_AUTO_START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("Ei Claude")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        notifySafely(c, NOTIF_ALERT, n);
    }

    static void cancelAlert(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ALERT);
    }

    private static void notifySafely(Context c, int id, Notification n) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(id, n);
        } catch (Throwable ignored) {
            // sem permissão de notificação: segue sem o aviso
        }
    }

    // ---------------------------------------------------------------- utilidades

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EiClaude:escuta");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private static String optString(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Menor confiança entre as palavras da frase no resultado do Vosk. */
    private static float minConfidence(String json, String[] words) {
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("result");
            if (arr == null || arr.length() == 0) return 1f;
            for (int i = 0; i + words.length <= arr.length(); i++) {
                boolean match = true;
                float min = 1f;
                for (int j = 0; j < words.length; j++) {
                    JSONObject w = arr.getJSONObject(i + j);
                    if (!words[j].equals(w.optString("word"))) {
                        match = false;
                        break;
                    }
                    min = Math.min(min, (float) w.optDouble("conf", 1.0));
                }
                if (match) return min;
            }
        } catch (Exception ignored) {
            // resultado em formato inesperado
        }
        return 0f;
    }

    // ---------------------------------------------------------------- captura de áudio

    /** Lê o microfone em blocos de 0,2 s e entrega ao reconhecedor. Libera tudo ao terminar. */
    private final class CaptureThread extends Thread {
        private final int gen;
        private final Recognizer recognizer;
        private final AudioRecord record;
        private final boolean wantPartials;
        private volatile boolean stopRequested;

        CaptureThread(int gen, Recognizer recognizer, AudioRecord record, boolean wantPartials) {
            super("EiClaude-captura");
            this.gen = gen;
            this.recognizer = recognizer;
            this.record = record;
            this.wantPartials = wantPartials;
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
                        handler.post(() -> onResult(gen, result));
                    } else if (wantPartials) {
                        final String partial = recognizer.getPartialResult();
                        handler.post(() -> onPartial(gen, partial));
                    }
                }
            } catch (Throwable t) {
                if (!stopRequested) {
                    final String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    handler.post(() -> onCaptureError(gen, msg));
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
