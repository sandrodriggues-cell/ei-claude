package com.eiclaude.relogio;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conversa por voz com o Claude no relógio.
 * <ul>
 * <li>Ao abrir, já começa a ouvir.</li>
 * <li>Manda a fala para a API do Claude e lê a resposta em voz alta.</li>
 * <li>Quando termina de falar, volta a ouvir (conversa contínua).</li>
 * <li>Dizer "fechar" (ou "tchau", "encerrar"…) fecha o app.</li>
 * </ul>
 * Toque na tela: começa a ouvir, para de ouvir, cancela a resposta ou interrompe a fala.
 */
public class MainActivity extends Activity implements Speaker.Listener {

    private enum State { IDLE, PREPARING, LISTENING, THINKING, SPEAKING }

    private static final int REQ_MIC = 7001;
    private static final long NEW_CHAT_AFTER_MS = 15L * 60 * 1000;
    private static final int MAX_HISTORY = 20;
    private static final int MAX_TOKENS = 700;
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ArrayList<ClaudeApi.Msg> history = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    private FrameLayout root;
    private ScrollView scroll;
    private TextView status;
    private MicButton mic;
    private TextView youText;
    private TextView replyText;

    private Speaker speaker;
    private IntentVoice intentVoice;
    private OfflineVoice offlineVoice;
    private List<VoiceInput> engines;
    private int engineIndex;
    private VoiceInput current;

    private State state = State.IDLE;
    private boolean resumed;
    private boolean autoListen = true; // ouvir assim que a tela aparecer
    private boolean setupOpened;
    private boolean micAsked;
    private boolean speakingError;
    private int silentInARow;
    private int requestGen;
    private long lastTurnAt;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        speaker = new Speaker(this, this);
        intentVoice = new IntentVoice(this);
        offlineVoice = new OfflineVoice(this);
        applyExtras(getIntent());
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyExtras(intent);
        if (state == State.IDLE) autoListen = true; // abriu o app de novo: quer falar
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        scroll.requestFocus();
        if (Prefs.consumeNewChat(this)) {
            history.clear();
            youText.setText("");
            replyText.setText("");
        }
        if (state == State.IDLE) {
            releaseEngines(); // os ajustes podem ter mudado (voz offline, por exemplo)
            render();
        }
        if (!Prefs.hasKey(this)) {
            if (!setupOpened) {
                setupOpened = true;
                openSetup();
            }
            return;
        }
        if (Prefs.consumeGreeting(this)) {
            greet();
            return;
        }
        if (state == State.IDLE && autoListen) {
            autoListen = false;
            main.postDelayed(this::startListening, 300);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (state == State.LISTENING || state == State.PREPARING) {
            // A tela de voz do sistema fica por cima do app: aí a pausa é esperada.
            if (current == intentVoice && intentVoice.isWaiting()) return;
            cancelListening();
            setState(State.IDLE);
            autoListen = true;
        }
    }

    @Override
    protected void onDestroy() {
        requestGen++;
        main.removeCallbacksAndMessages(null);
        cancelListening();
        releaseEngines();
        offlineVoice.release();
        intentVoice.release();
        speaker.shutdown();
        network.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!intentVoice.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        releaseEngines();
        main.postDelayed(this::startListening, 300);
    }

    /** Ajustes por linha de comando (adb): --es chave sk-ant-… [--es modelo …] [--es nome …]. */
    private void applyExtras(Intent intent) {
        if (intent == null) return;
        String key = intent.getStringExtra("chave");
        if (key != null && Prefs.looksLikeKey(key.trim())) {
            Prefs.setApiKey(this, key.trim());
            Toast.makeText(this, "Chave da API salva", Toast.LENGTH_SHORT).show();
        }
        String model = intent.getStringExtra("modelo");
        if (model != null) Prefs.setModel(this, model.trim());
        String name = intent.getStringExtra("nome");
        if (name != null) Prefs.setName(this, name);
    }

    // ------------------------------------------------------------------ tela

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        scroll = Ui.scroller(this);
        LinearLayout column = Ui.column(this);
        column.setClickable(true);
        column.setOnClickListener(v -> onTap());
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        status = Ui.text(this, 12, Ui.MUTED, false);
        column.addView(status, Ui.wide(this, 0));

        mic = new MicButton(this);
        mic.setOnClickListener(v -> onTap());
        int size = Math.round(Ui.screenWidth(this) * 0.42f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(mic, lp);

        youText = Ui.text(this, 13, Ui.SOFT, false);
        youText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        column.addView(youText, Ui.wide(this, 2));

        replyText = Ui.text(this, 15, Ui.INK, false);
        column.addView(replyText, Ui.wide(this, 8));

        TextView settings = Ui.text(this, 13, Ui.ACCENT, true);
        settings.setText("Ajustes");
        int pad = Ui.dp(this, 10);
        settings.setPadding(pad, pad, pad, pad);
        settings.setClickable(true);
        settings.setOnClickListener(v -> openSetup());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.topMargin = Ui.dp(this, 16);
        column.addView(settings, sp);
    }

    private void setState(State s) {
        state = s;
        render();
    }

    private void render() {
        boolean hasKey = Prefs.hasKey(this);
        switch (state) {
            case PREPARING:
                mic.setLook(MicButton.Look.LISTENING);
                status.setText("Preparando…");
                break;
            case LISTENING:
                mic.setLook(MicButton.Look.LISTENING);
                status.setText("Ouvindo… pode falar");
                break;
            case THINKING:
                mic.setLook(MicButton.Look.THINKING);
                status.setText("Pensando…");
                break;
            case SPEAKING:
                mic.setLook(MicButton.Look.SPEAKING);
                status.setText("Falando · toque para interromper");
                break;
            default:
                mic.setLook(hasKey ? MicButton.Look.IDLE : MicButton.Look.DISABLED);
                status.setText(hasKey ? "Toque para falar" : "Falta configurar a chave da API");
        }
        boolean busy = state == State.PREPARING || state == State.LISTENING || state == State.THINKING;
        replyText.setTextColor(busy ? Ui.MUTED : Ui.INK);
        root.setKeepScreenOn(state != State.IDLE);
    }

    private void showProblem(String message) {
        setState(State.IDLE);
        replyText.setText(message);
        replyText.setTextColor(Ui.WARN);
        status.setText("Toque para tentar de novo");
    }

    private void scrollToTop() {
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
    }

    private void scrollToReply() {
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, youText.getTop() - Ui.dp(this, 6))));
    }

    private void openSetup() {
        startActivity(new Intent(this, SetupActivity.class));
    }

    // ------------------------------------------------------------------ toque

    private void onTap() {
        switch (state) {
            case IDLE:
                silentInARow = 0;
                if (!Prefs.hasKey(this)) {
                    openSetup();
                    return;
                }
                startListening();
                break;
            case PREPARING:
            case LISTENING:
                cancelListening();
                setState(State.IDLE);
                break;
            case THINKING:
                requestGen++; // descarta a resposta que estava a caminho
                dropLastUserMessage();
                setState(State.IDLE);
                status.setText("Cancelado · toque para falar");
                break;
            case SPEAKING:
                speaker.stop();
                speakingError = false;
                silentInARow = 0;
                setState(State.IDLE);
                startListening();
                break;
        }
    }

    // ------------------------------------------------------------------ ouvir

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startListening() {
        if (isFinishing() || isDestroyed()) return;
        if (!resumed) {
            autoListen = true;
            return;
        }
        if (state != State.IDLE) return;
        if (!Prefs.hasKey(this)) {
            openSetup();
            return;
        }
        speaker.stop();
        if (!hasMic() && !micAsked) {
            micAsked = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (engines == null) {
            engines = buildEngines();
            engineIndex = 0;
            failures.clear();
        }
        if (engines.isEmpty()) {
            showProblem(hasMic()
                    ? "Este relógio não tem reconhecimento de voz disponível."
                    : "Permita o microfone para o Ei Claude nas configurações do relógio (Apps › Ei Claude › Permissões).");
            return;
        }
        if (engineIndex >= engines.size()) engineIndex = 0;
        current = engines.get(engineIndex);
        youText.setText("");
        scrollToTop();
        setState(State.PREPARING);
        current.start(voiceCallback);
    }

    private void cancelListening() {
        if (current != null) current.cancel();
    }

    /** Motores de voz em ordem de preferência; o que funcionou da última vez vai primeiro. */
    private List<VoiceInput> buildEngines() {
        List<VoiceInput> list = new ArrayList<>();
        boolean mic = hasMic();
        if (Prefs.offlineOnly(this)) {
            if (mic) list.add(offlineVoice);
            return list;
        }
        if (mic) {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    list.add(new SystemVoice(this, null));
                    for (ComponentName cn : SystemVoice.services(this)) list.add(new SystemVoice(this, cn));
                }
            } catch (Throwable ignored) {
                // sem reconhecedor do sistema
            }
        }
        if (IntentVoice.available(this)) list.add(intentVoice);
        if (mic) list.add(offlineVoice);
        String last = Prefs.engineId(this);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(last)) {
                list.add(0, list.remove(i));
                break;
            }
        }
        return list;
    }

    private void releaseEngines() {
        if (engines == null) return;
        for (VoiceInput v : engines) {
            if (v instanceof SystemVoice) v.release();
        }
        engines = null;
        engineIndex = 0;
    }

    private final VoiceInput.Callback voiceCallback = new VoiceInput.Callback() {
        @Override
        public void onStatus(String message) {
            status.setText(message);
        }

        @Override
        public void onReady() {
            if (state != State.PREPARING && state != State.LISTENING) return;
            setState(State.LISTENING);
            Ui.buzz(MainActivity.this, 35);
            if (current != null) Prefs.setEngine(MainActivity.this, current.id(), current.label());
        }

        @Override
        public void onPartial(String text) {
            if (state == State.LISTENING || state == State.PREPARING) youText.setText(text);
        }

        @Override
        public void onText(String text) {
            silentInARow = 0;
            setState(State.IDLE);
            onUserSaid(text);
        }

        @Override
        public void onNoSpeech() {
            setState(State.IDLE);
            silentInARow++;
            // Na tela de voz do sistema, voltar sem texto costuma ser "cancelar": não reabre.
            boolean retry = current != intentVoice && silentInARow < 2 && resumed;
            if (retry) {
                main.postDelayed(MainActivity.this::startListening, 400);
            } else {
                silentInARow = 0;
                status.setText("Toque para falar");
            }
        }

        @Override
        public void onError(String message) {
            showProblem(message);
        }

        @Override
        public void onUnavailable(String reason) {
            if (current != null) failures.add(current.label() + ": " + reason);
            setState(State.IDLE);
            engineIndex++;
            if (engines != null && engineIndex < engines.size()) {
                startListening();
                return;
            }
            Prefs.setEngine(MainActivity.this, "", "nenhum funcionou (" + String.join("; ", failures) + ")");
            releaseEngines();
            showProblem("Não consegui usar o reconhecimento de voz deste relógio. "
                    + "Nos ajustes, ligue \"Voz offline: sempre\" e tente de novo.");
        }
    };

    // ------------------------------------------------------------------ conversar

    private void onUserSaid(String text) {
        youText.setText("“" + text + "”");
        if (Text.isClose(text)) {
            closeApp();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastTurnAt != 0 && now - lastTurnAt > NEW_CHAT_AFTER_MS) history.clear();
        lastTurnAt = now;
        history.add(new ClaudeApi.Msg("user", text));
        trimHistory();
        setState(State.THINKING);

        final int gen = ++requestGen;
        final String key = Prefs.apiKey(this);
        final String model = Prefs.model(this);
        final String system = systemPrompt();
        final List<ClaudeApi.Msg> snapshot = new ArrayList<>(history);
        try {
            network.execute(() -> {
                try {
                    ClaudeApi.Reply r = ClaudeApi.send(key, model, system, snapshot, MAX_TOKENS);
                    main.post(() -> onReply(gen, r));
                } catch (ClaudeApi.ApiException e) {
                    main.post(() -> onApiError(gen, e));
                }
            });
        } catch (Throwable t) {
            dropLastUserMessage();
            showProblem("Não consegui enviar a pergunta. Tente de novo.");
        }
    }

    private void onReply(int gen, ClaudeApi.Reply r) {
        if (gen != requestGen || isDestroyed()) return;
        Prefs.addTokens(this, r.inputTokens, r.outputTokens);
        String text = r.text == null ? "" : r.text.trim();
        if (text.isEmpty()) text = "Hum, não consegui pensar numa resposta agora. Pode repetir?";
        history.add(new ClaudeApi.Msg("assistant", text));
        lastTurnAt = SystemClock.elapsedRealtime();
        replyText.setText(text);
        speakingError = false;
        setState(State.SPEAKING);
        scrollToReply();
        speaker.speak(Text.forSpeech(text));
    }

    private void onApiError(int gen, ClaudeApi.ApiException e) {
        if (gen != requestGen || isDestroyed()) return;
        dropLastUserMessage();
        replyText.setText(e.getMessage());
        speakingError = true; // fala o erro, mas não volta a ouvir sozinho
        setState(State.SPEAKING);
        replyText.setTextColor(Ui.WARN);
        scrollToReply();
        speaker.speak(e.getMessage());
    }

    private void greet() {
        String text = "Oi, " + Prefs.name(this) + "! Tudo pronto. Pode falar comigo.";
        replyText.setText(text);
        speakingError = false;
        setState(State.SPEAKING);
        speaker.speak(text);
    }

    @Override
    public void onSpeakDone() {
        if (state != State.SPEAKING) return;
        if (speakingError) {
            speakingError = false;
            setState(State.IDLE);
            replyText.setTextColor(Ui.WARN);
            status.setText("Toque para tentar de novo");
            return;
        }
        setState(State.IDLE);
        if (!speaker.isUsable()) {
            status.setText("Toque para responder"); // sem voz no relógio: dá tempo de ler
            return;
        }
        if (resumed) main.postDelayed(this::startListening, 250);
        else autoListen = true;
    }

    private void dropLastUserMessage() {
        int last = history.size() - 1;
        if (last >= 0 && "user".equals(history.get(last).role)) history.remove(last);
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) history.remove(0);
        while (!history.isEmpty() && !"user".equals(history.get(0).role)) history.remove(0);
    }

    private String systemPrompt() {
        String now = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", PT_BR)
                .format(ZonedDateTime.now());
        return "Você é o Claude, assistente de IA criado pela Anthropic, conversando por voz com "
                + Prefs.name(this) + " por um relógio Galaxy Watch. "
                + "Tudo o que você escrever será lido em voz alta pelo relógio, então responda em português do Brasil, "
                + "de forma natural e calorosa, como numa conversa falada: no máximo duas ou três frases curtas, "
                + "a não ser que peçam mais detalhes. "
                + "Não use markdown, listas, títulos, emojis, símbolos ou links; escreva números, horários e siglas "
                + "de um jeito fácil de ouvir. "
                + "A fala chega por um reconhecedor de voz e pode vir com palavras trocadas: entenda pelo contexto e, "
                + "se não der para entender, peça para repetir. "
                + "Nesta conversa você não tem acesso à internet: se pedirem notícias, clima, cotações ou outras "
                + "informações em tempo real, diga com sinceridade que daqui do relógio não consegue consultar. "
                + "Agora é " + now + ", no fuso " + ZoneId.systemDefault().getId() + ".";
    }

    private void closeApp() {
        requestGen++;
        cancelListening();
        speaker.stop();
        Ui.buzz(this, 60);
        finishAndRemoveTask();
    }
}
