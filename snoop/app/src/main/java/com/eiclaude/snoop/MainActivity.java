package com.eiclaude.snoop;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tela do Snoop: toque nele e fale. A fala vai em áudio para o Gemini, que entende, responde no
 * jeito do Snoop e diz o humor dele; a resposta sai em voz alta e ele já volta a ouvir.
 */
public class MainActivity extends Activity implements Speaker.Listener {

    static final String EXTRA_SAY = "fala";
    static final String EXTRA_MOOD = "humor";

    private enum State { IDLE, LISTENING, THINKING, SPEAKING }

    private static final int REQ_PERMS = 7101;
    private static final long NAP_AFTER_MS = 45000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private ScrollView scroll;
    private TextView status;
    private DogView dog;
    private TextView heardText;
    private TextView replyText;

    private Speaker speaker;
    private Player player;
    private Recorder recorder;

    private State state = State.IDLE;
    private boolean resumed;
    private boolean askedPerms;
    private boolean autoListen = true;
    private boolean endAfterSpeak;
    private int requestGen;
    private int speakGen;
    private String pendingSay;
    private String pendingMood;

    private final Runnable nap = () -> {
        if (state == State.IDLE && dog != null) {
            dog.setMode(DogView.Mode.SLEEPING);
            status.setText("Zzz… toque para acordar o Snoop");
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onLevel(float level) {
            dog.setLevel(level);
        }

        @Override
        public void onDone() {
            onSpeakDone();
        }
    };

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        speaker = new Speaker(this, this);
        player = new Player();
        recorder = new Recorder(this);
        Notifier.ensureChannel(this);
        Pulse.schedule(this);
        Health.registerIfNeeded(this);
        readExtras(getIntent());
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readExtras(intent);
        if (state == State.IDLE) autoListen = true; // abriu de novo: quer conversar
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Prefs.putLong(this, "aberto_em", System.currentTimeMillis());
        scroll.requestFocus();
        String[] missing = missingPermissions();
        if (!askedPerms && missing.length > 0) {
            askedPerms = true;
            requestPermissions(missing, REQ_PERMS);
            return;
        }
        if (state != State.IDLE) return;
        if (!Prefs.hasKey(this)) {
            showIntro();
            return;
        }
        if (pendingSay != null) {
            String s = pendingSay;
            pendingSay = null;
            dog.setMood(DogView.Mood.of(pendingMood));
            heardText.setText("");
            replyText.setTextColor(Ui.INK);
            replyText.setText(s);
            autoListen = false;
            speak(s);
            return;
        }
        if (Prefs.consumeGreeting(this)) {
            String hi = "Oba! Agora eu consigo te ouvir, " + Prefs.name(this) + ". Au au! Fala comigo!";
            Brain.add(this, "model", hi);
            dog.setMood(DogView.Mood.ANIMADO);
            heardText.setText("");
            replyText.setTextColor(Ui.INK);
            replyText.setText(hi);
            autoListen = false;
            speak(hi);
            return;
        }
        if (autoListen) {
            autoListen = false;
            main.postDelayed(this::startListening, 350);
            return;
        }
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        autoListen = false;
        main.removeCallbacks(nap);
        if (state == State.LISTENING) {
            recorder.cancel();
            setState(State.IDLE);
        } else if (state == State.SPEAKING) {
            stopSpeaking();
            setState(State.IDLE);
        }
        // PENSANDO: a resposta chega e fica na tela, sem falar.
    }

    @Override
    protected void onDestroy() {
        requestGen++;
        speakGen++;
        main.removeCallbacksAndMessages(null);
        recorder.cancel();
        player.stop();
        speaker.shutdown();
        network.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        Health.register(this);
        Pulse.schedule(this);
        // A tela volta (onResume) logo depois e segue de lá.
    }

    private void readExtras(Intent intent) {
        if (intent == null) return;
        String say = intent.getStringExtra(EXTRA_SAY);
        if (say != null && !say.trim().isEmpty()) {
            pendingSay = say.trim();
            pendingMood = intent.getStringExtra(EXTRA_MOOD);
            intent.removeExtra(EXTRA_SAY);
        }
    }

    private String[] missingPermissions() {
        List<String> out = new ArrayList<>();
        if (!hasMic()) out.add(Manifest.permission.RECORD_AUDIO);
        if (!Health.hasPermission(this)) out.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            out.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return out.toArray(new String[0]);
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ tela

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        scroll = Ui.scroller(this);
        LinearLayout column = Ui.column(this);
        int w = Ui.screenWidth(this);
        int h = Ui.screenHeight(this);
        column.setPadding(column.getPaddingLeft(), Math.round(h * 0.05f), column.getPaddingRight(), Math.round(h * 0.25f));
        column.setClickable(true);
        column.setOnClickListener(v -> onTap());
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        status = Ui.text(this, 12, Ui.MUTED, false);
        column.addView(status, Ui.wide(this, 0));

        dog = new DogView(this);
        dog.setClickable(true);
        dog.setOnClickListener(v -> onTap());
        int size = Math.round(w * 0.70f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(dog, lp);

        heardText = Ui.text(this, 12, Ui.SOFT, false);
        heardText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        column.addView(heardText, Ui.wide(this, 2));

        replyText = Ui.text(this, 15, Ui.INK, false);
        column.addView(replyText, Ui.wide(this, 6));

        TextView settings = Ui.text(this, 13, Ui.ACCENT, true);
        settings.setText("Ajustes");
        int pad = Ui.dp(this, 10);
        settings.setPadding(pad, pad, pad, pad);
        settings.setClickable(true);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        sp.topMargin = Ui.dp(this, 14);
        column.addView(settings, sp);
    }

    private void setState(State s) {
        state = s;
        render();
    }

    private void render() {
        main.removeCallbacks(nap);
        switch (state) {
            case LISTENING:
                dog.setMode(DogView.Mode.LISTENING);
                status.setText("Ouvindo… pode falar");
                break;
            case THINKING:
                dog.setMode(DogView.Mode.THINKING);
                status.setText("Pensando…");
                break;
            case SPEAKING:
                dog.setMode(DogView.Mode.SPEAKING);
                status.setText("Falando · toque para interromper");
                break;
            default:
                dog.setMode(DogView.Mode.IDLE);
                status.setText(Prefs.hasKey(this) ? "Toque no Snoop para conversar" : "Falta a chave do Gemini");
                main.postDelayed(nap, NAP_AFTER_MS);
        }
        root.setKeepScreenOn(state != State.IDLE);
    }

    private void showIntro() {
        setState(State.IDLE);
        dog.setMood(DogView.Mood.CURIOSO);
        heardText.setText("");
        replyText.setTextColor(Ui.INK);
        replyText.setText("Oi! Eu sou o Snoop, seu cachorro virtual. Pra eu conversar, falta a chave do Gemini: "
                + "toque em Ajustes, aqui embaixo.");
    }

    private void showProblem(String message) {
        setState(State.IDLE);
        replyText.setText(message);
        replyText.setTextColor(Ui.WARN);
        status.setText("Toque no Snoop para tentar de novo");
    }

    private void scrollToReply() {
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, heardText.getTop() - Ui.dp(this, 40))));
    }

    // ------------------------------------------------------------------ toque

    private void onTap() {
        dog.poke();
        switch (state) {
            case IDLE:
                if (!Prefs.hasKey(this)) {
                    startActivity(new Intent(this, SettingsActivity.class));
                    return;
                }
                startListening();
                break;
            case LISTENING:
                recorder.stopAndSend(); // "pronto, pode responder"
                break;
            case THINKING:
                requestGen++; // descarta a resposta que estava a caminho
                setState(State.IDLE);
                status.setText("Cancelado · toque para falar");
                break;
            case SPEAKING:
                stopSpeaking();
                endAfterSpeak = false;
                setState(State.IDLE);
                startListening();
                break;
        }
    }

    // ------------------------------------------------------------------ ouvir

    private void startListening() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        if (state != State.IDLE) return;
        if (!Prefs.hasKey(this)) {
            showIntro();
            return;
        }
        if (!hasMic()) {
            showProblem("Permita o microfone do Snoop nas configurações do relógio (Aplicativos › Snoop › Permissões).");
            return;
        }
        stopSpeaking();
        heardText.setText("");
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
        setState(State.LISTENING);
        Ui.buzz(this, 25);
        recorder.start(new Recorder.Listener() {
            @Override
            public void onLevel(float level) {
                dog.setLevel(level);
            }

            @Override
            public void onDone(File file, boolean heard) {
                dog.setLevel(0f);
                if (state != State.LISTENING) return;
                if (!heard) {
                    setState(State.IDLE);
                    dog.setMood(DogView.Mood.CURIOSO);
                    status.setText("Não ouvi nada · toque no Snoop para falar");
                    return;
                }
                send(file);
            }

            @Override
            public void onError(String message) {
                dog.setLevel(0f);
                showProblem(message);
            }
        });
    }

    // ------------------------------------------------------------------ pensar (Gemini)

    private void send(File file) {
        final byte[] audio;
        try {
            audio = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            showProblem("Não consegui ler a gravação.");
            return;
        }
        setState(State.THINKING);
        final int gen = ++requestGen;
        final String key = Prefs.apiKey(this);
        final String model = Prefs.model(this);
        final List<Brain.Msg> history = Brain.history(this);
        final String system = Brain.persona(this, Moves.idleMinutes(this, System.currentTimeMillis()));
        network.execute(() -> {
            String used = model;
            Gemini.Reply reply = null;
            Gemini.ApiException error = null;
            try {
                reply = Gemini.chat(key, model, system, history, audio, Recorder.MIME, null);
            } catch (Gemini.ApiException e) {
                if (e.kind == Gemini.Kind.MODEL) {
                    used = Prefs.otherModel(model); // modelo indisponível: tenta o outro cérebro
                    try {
                        reply = Gemini.chat(key, used, system, history, audio, Recorder.MIME, null);
                    } catch (Gemini.ApiException e2) {
                        error = e2;
                    }
                } else {
                    error = e;
                }
            }
            final Gemini.Reply fReply = reply;
            final Gemini.ApiException fError = error;
            final String fUsed = used;
            main.post(() -> {
                if (fError != null || fReply == null) {
                    onFail(gen, fError != null ? fError
                            : new Gemini.ApiException(Gemini.Kind.OTHER, 0, "sem resposta"));
                } else {
                    onReply(gen, fReply, fUsed);
                }
            });
        });
    }

    private void onReply(int gen, Gemini.Reply r, String usedModel) {
        if (gen != requestGen || isDestroyed()) return;
        if (!usedModel.equals(Prefs.model(this))) Prefs.setModel(this, usedModel);
        Prefs.countRequest(this, r.tokensIn, r.tokensOut);
        Brain.add(this, "user", r.heard.isEmpty() ? "(mensagem de voz que o Snoop não entendeu)" : r.heard);
        Brain.add(this, "model", r.say);
        if (!r.remember.isEmpty()) Brain.addNote(this, r.remember);

        heardText.setText(r.heard.isEmpty() ? "" : "Você: " + r.heard);
        replyText.setTextColor(Ui.INK);
        replyText.setText(r.say);
        dog.setMood(DogView.Mood.of(r.mood));
        endAfterSpeak = r.end || Text.isClose(r.heard);
        if (!resumed) {
            setState(State.IDLE);
            return;
        }
        speak(r.say);
    }

    private void onFail(int gen, Gemini.ApiException e) {
        if (gen != requestGen || isDestroyed()) return;
        String msg;
        DogView.Mood mood = DogView.Mood.PREOCUPADO;
        switch (e.kind) {
            case KEY:
                msg = "Minha chave do Gemini não funcionou: " + e.getMessage() + " Confira nos Ajustes.";
                break;
            case QUOTA:
                msg = "Ufa, cansei! Batemos o limite grátis do Gemini por agora. Tenta de novo daqui a pouco.";
                mood = DogView.Mood.SONOLENTO;
                break;
            case NETWORK:
                msg = "Fiquei sem internet (" + e.getMessage() + "). O relógio está conectado ao celular ou ao Wi-Fi?";
                mood = DogView.Mood.TRISTE;
                break;
            case MODEL:
                msg = "O cérebro do Gemini não respondeu: " + e.getMessage();
                break;
            case BLOCKED:
                msg = "Esse assunto eu não consigo responder. Vamos falar de outra coisa?";
                mood = DogView.Mood.CURIOSO;
                break;
            default:
                msg = "Deu um probleminha: " + e.getMessage();
        }
        dog.setMood(mood);
        showProblem(msg);
    }

    // ------------------------------------------------------------------ falar

    private void speak(String text) {
        setState(State.SPEAKING);
        scrollToReply();
        final String spoken = Text.forSpeech(text);
        boolean gemini = Prefs.hasKey(this)
                && (Prefs.VOICE_GEMINI.equals(Prefs.voice(this)) || !speaker.isGood());
        if (!gemini) {
            dog.setLevelDriven(false);
            speaker.speak(spoken);
            return;
        }
        dog.setLevelDriven(true);
        final int gen = ++speakGen;
        final String key = Prefs.apiKey(this);
        final String voice = Prefs.geminiVoice(this);
        network.execute(() -> {
            Gemini.Audio audio = null;
            try {
                audio = Gemini.speak(key, voice, spoken);
            } catch (Gemini.ApiException ignored) {
                // cai para o leitor do relógio
            }
            final Gemini.Audio fAudio = audio;
            main.post(() -> {
                if (gen != speakGen || state != State.SPEAKING) return;
                if (fAudio != null && fAudio.pcm.length > 0) {
                    Prefs.countRequest(this, 0, 0);
                    player.play(fAudio, playerListener);
                } else {
                    dog.setLevelDriven(false);
                    speaker.speak(spoken);
                }
            });
        });
    }

    private void stopSpeaking() {
        speakGen++;
        speaker.stop();
        player.stop();
        dog.setLevelDriven(false);
        dog.setLevel(0f);
    }

    @Override
    public void onSpeakDone() {
        if (state != State.SPEAKING) return;
        dog.setLevel(0f);
        dog.setLevelDriven(false);
        if (endAfterSpeak) {
            closeApp();
            return;
        }
        setState(State.IDLE);
        if (Prefs.keepListening(this) && resumed) main.postDelayed(this::startListening, 300);
    }

    private void closeApp() {
        requestGen++;
        recorder.cancel();
        stopSpeaking();
        Ui.buzz(this, 60);
        finishAndRemoveTask();
    }
}
