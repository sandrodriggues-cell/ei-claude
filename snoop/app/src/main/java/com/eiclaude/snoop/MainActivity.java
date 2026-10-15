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
    private static final long STATS_EVERY_MS = 30000;
    private static final long MOOD_HOLD_MS = 10L * 60 * 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private ScrollView scroll;
    private TextView status;
    private DogView dog;
    private StatsView stats;
    private TextView treatChip;
    private TextView heardText;
    private TextView replyText;
    private TextView settingsLink;
    private Backdrop.Look look;

    private Speaker speaker;
    private Player player;
    private Recorder recorder;

    private State state = State.IDLE;
    private boolean resumed;
    private boolean askedPerms;
    private boolean autoListen = true;
    private boolean endAfterSpeak;
    private boolean listenAfter = true;
    private long moodSetAt;
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

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            if (Backdrop.AUTO.equals(Backdrop.current(MainActivity.this))) applyBackdrop();
            main.postDelayed(this, STATS_EVERY_MS);
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
        applyBackdrop();
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
        applyBackdrop();
        main.removeCallbacks(statsTick);
        main.post(statsTick);
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
            heardText.setText("");
            replyText.setTextColor(look.ink);
            replyText.setText(s);
            autoListen = false;
            setMood(DogView.Mood.of(pendingMood));
            speak(s, true);
            return;
        }
        if (Prefs.consumeGreeting(this)) {
            String hi = "Oba! Agora eu consigo te ouvir, " + Prefs.name(this) + ". Au au! Fala comigo!";
            Brain.add(this, "model", hi);
            setMood(DogView.Mood.ANIMADO);
            heardText.setText("");
            replyText.setTextColor(look.ink);
            replyText.setText(hi);
            autoListen = false;
            speak(hi, true);
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
        main.removeCallbacks(statsTick);
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
        dog.setPetListener(this::onPet);
        int size = Math.round(w * 0.70f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(dog, lp);

        stats = new StatsView(this);
        stats.setOnClickListener(v -> showStatsHelp());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.topMargin = Ui.dp(this, 2);
        column.addView(stats, slp);

        treatChip = Ui.chip(this, "", v -> feed());
        treatChip.setVisibility(android.view.View.GONE);
        column.addView(treatChip);

        heardText = Ui.text(this, 12, Ui.SOFT, false);
        heardText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        column.addView(heardText, Ui.wide(this, 2));

        replyText = Ui.text(this, 15, Ui.INK, false);
        column.addView(replyText, Ui.wide(this, 6));

        TextView settings = Ui.text(this, 13, Ui.ACCENT, true);
        settingsLink = settings;
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
                long now = System.currentTimeMillis();
                if (now - moodSetAt > MOOD_HOLD_MS) {
                    DogView.Mood hint = Pet.moodHint(this, now);
                    dog.setMood(hint != null ? hint : DogView.Mood.FELIZ);
                }
                main.postDelayed(nap, NAP_AFTER_MS);
        }
        root.setKeepScreenOn(state != State.IDLE);
    }

    /** Pinta o fundo escolhido e ajusta a cor dos textos para continuarem legíveis. */
    private void applyBackdrop() {
        look = Backdrop.look(this);
        root.setBackground(Backdrop.drawable(look, Ui.screenWidth(this), Ui.screenHeight(this)));
        status.setTextColor(look.muted);
        heardText.setTextColor(look.soft);
        if (replyText.getCurrentTextColor() != look.warn) replyText.setTextColor(look.ink);
        settingsLink.setTextColor(look.accent);
        stats.setColors(look.muted, look.track);
        dog.setInk(look.light ? 0xFF2A1C14 : 0xFFF5F1EC);
    }

    private void setMood(DogView.Mood m) {
        dog.setMood(m);
        moodSetAt = System.currentTimeMillis();
    }

    private void refreshStats() {
        long now = System.currentTimeMillis();
        stats.setValues(Pet.belly(this, now), Pet.walk(this, now), Pet.joy(this, now));
        int treats = Pet.treatsAvailable(this, now);
        treatChip.setText(treats == 1 ? "Dar petisco" : "Dar petisco (" + treats + ")");
        treatChip.setVisibility(treats > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** Carinho: deslizou o dedo na cabeça dele. */
    private void onPet() {
        Pet.addJoy(this, 2);
        refreshStats();
        if (state == State.IDLE) {
            setMood(DogView.Mood.FELIZ);
            render(); // acorda do cochilo
        }
    }

    private void feed() {
        if (!Pet.feed(this)) {
            refreshStats();
            return;
        }
        dog.giveTreat();
        Ui.buzz(this, 40);
        refreshStats();
        setMood(DogView.Mood.ANIMADO);
        String thanks = Phrases.treatThanks(this);
        heardText.setText("");
        replyText.setTextColor(look.ink);
        replyText.setText(thanks);
        if (state == State.IDLE && resumed) speak(thanks, false);
    }

    private void showStatsHelp() {
        long now = System.currentTimeMillis();
        long next = Pet.stepsToNextTreat(this, now);
        heardText.setText("");
        replyText.setTextColor(look.soft);
        replyText.setText("Barriga enche com petiscos: você ganha um a cada " + Pet.STEPS_PER_TREAT
                + " passos no dia" + (next > 0 ? " (faltam " + next + ")" : "") + ". Passeio são os passos das "
                + "últimas 3 horas. Alegria sobe com conversa, carinho (deslize o dedo na cabeça dele) e truques.");
    }

    private void showIntro() {
        setState(State.IDLE);
        dog.setMood(DogView.Mood.CURIOSO);
        heardText.setText("");
        replyText.setTextColor(look.ink);
        replyText.setText("Oi! Eu sou o Snoop, seu cachorro virtual. Pra eu conversar, falta a chave do Gemini: "
                + "toque em Ajustes, aqui embaixo.");
    }

    private void showProblem(String message) {
        setState(State.IDLE);
        replyText.setText(message);
        replyText.setTextColor(look.warn);
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
                    setMood(DogView.Mood.CURIOSO);
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
        String say = r.say;
        if (!r.reminderWhen.isEmpty() && !r.reminderText.isEmpty()) {
            Reminders.Item it = Reminders.add(this, Reminders.parseWhen(r.reminderWhen), r.reminderText, r.reminderDaily);
            if (it == null) say += " Hmm, não consegui marcar esse lembrete. Fala o dia e o horário de novo?";
        }
        if (!r.cancelReminder.isEmpty() && Reminders.cancelMatching(this, r.cancelReminder) == 0) {
            say += " Não achei esse lembrete na minha lista.";
        }
        Brain.add(this, "user", r.heard.isEmpty() ? "(mensagem de voz que o Snoop não entendeu)" : r.heard);
        Brain.add(this, "model", say);
        if (!r.remember.isEmpty()) Brain.addNote(this, r.remember);
        Pet.addJoy(this, 6);

        heardText.setText(r.heard.isEmpty() ? "" : "Você: " + r.heard);
        replyText.setTextColor(look.ink);
        replyText.setText(say);
        setMood(DogView.Mood.of(r.mood));
        if (!r.background.isEmpty()) {
            Backdrop.set(this, r.background);
            applyBackdrop();
        }
        if (!r.trick.isEmpty() && dog.playTrick(r.trick)) {
            Pet.addJoy(this, 3);
            if ("latir".equals(Text.fold(r.trick))) Ui.buzz(this, 80);
        }
        refreshStats();
        endAfterSpeak = r.end || Text.isClose(r.heard);
        if (!resumed) {
            setState(State.IDLE);
            return;
        }
        speak(say, true);
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
        setMood(mood);
        showProblem(msg);
    }

    // ------------------------------------------------------------------ falar

    private void speak(String text, boolean thenListen) {
        listenAfter = thenListen;
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
        if (listenAfter && Prefs.keepListening(this) && resumed) main.postDelayed(this::startListening, 300);
    }

    private void closeApp() {
        requestGen++;
        recorder.cancel();
        stopSpeaking();
        Ui.buzz(this, 60);
        finishAndRemoveTask();
    }
}
