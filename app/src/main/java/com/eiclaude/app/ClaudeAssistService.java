package com.eiclaude.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Serviço de acessibilidade que só observa o app do Claude.
 * <ul>
 * <li>Depois do "Ei, Claude", toca no botão do modo de voz.</li>
 * <li>Quando a sua fala "fechar" aparece na tela do Claude, toca em encerrar e volta para a
 * tela inicial.</li>
 * </ul>
 * Nada é enviado para fora do aparelho.
 */
public class ClaudeAssistService extends AccessibilityService {

    private static final long SCAN_DELAY_MS = 400;
    private static final long VOICE_WINDOW_MS = 25000;
    private static final long VOICE_SETTLE_MS = 500;
    private static final long CLOSE_CONFIRM_MS = 1200;
    private static final long SESSION_MAX_MS = 45L * 60 * 1000;
    private static final long SNAPSHOT_EVERY_MS = 3000;
    private static final int MAX_NODES = 3000;
    private static final int MAX_PHRASE_TEXT = 40;

    private static final String[] VOICE_STRONG = {"modo de voz", "voice mode", "conversa por voz",
            "conversar por voz", "voice chat", "start voice", "iniciar voz"};
    private static final String[] VOICE_WEAK = {"voz", "voice", "falar"};
    private static final String[] VOICE_EXCLUDE = {"ditado", "ditar", "dictat", "microfone", "microphone",
            "transcri", "gravar", "record", "anexo", "attach"};

    private static final String[] STOP_STRONG = {"encerrar", "end voice", "sair do modo de voz",
            "parar o modo de voz", "stop voice", "end conversation", "end call"};
    private static final String[] STOP_WEAK = {"parar", "stop", "finalizar", "sair", "end", "fechar", "close",
            "desligar"};
    private static final String[] STOP_EXCLUDE = {"enviar", "send", "teclado", "keyboard"};

    static volatile ClaudeAssistService instance;
    static volatile String lastAction = "";
    static volatile List<String> lastLabels = new ArrayList<>();

    private static volatile long sessionStartedAt;
    private static volatile boolean voicePending;
    private static volatile long voiceButtonSeenAt;
    private static volatile int closeBaseline = -1;
    private static volatile long closeSeenAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = this::scan;
    private boolean scanScheduled;
    private long lastSnapshotAt;

    /** Chamado pelo serviço de escuta logo antes de abrir o Claude. */
    static void beginSession(Context c) {
        sessionStartedAt = SystemClock.elapsedRealtime();
        voicePending = Prefs.isAutoVoice(c);
        voiceButtonSeenAt = 0;
        closeBaseline = -1;
        closeSeenAt = 0;
        setAction(voicePending
                ? "Abrindo o Claude para ligar o modo de voz…"
                : "Abrindo o Claude.");
    }

    static boolean isConnected() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        setAction("Acessibilidade ligada.");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        // nada a interromper
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !WakeService.CLAUDE_PACKAGE.contentEquals(pkg)) return;
        scheduleScan(SCAN_DELAY_MS);
    }

    private void scheduleScan(long delayMs) {
        if (scanScheduled) return;
        scanScheduled = true;
        handler.postDelayed(scanRunnable, delayMs);
    }

    private void rescheduleScan(long delayMs) {
        handler.removeCallbacks(scanRunnable);
        scanScheduled = true;
        handler.postDelayed(scanRunnable, delayMs);
    }

    // ------------------------------------------------------------------ análise da tela

    private void scan() {
        scanScheduled = false;
        long now = SystemClock.elapsedRealtime();
        boolean session = sessionStartedAt > 0 && now - sessionStartedAt < SESSION_MAX_MS;
        if (!session) sessionStartedAt = 0;
        boolean snapshotDue = now - lastSnapshotAt >= SNAPSHOT_EVERY_MS;
        if (!session && !snapshotDue) return;

        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            return;
        }
        if (root == null) return;
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg == null || !WakeService.CLAUDE_PACKAGE.contentEquals(rootPkg)) return;

        List<AccessibilityNodeInfo> nodes = collect(root);
        if (snapshotDue) {
            lastSnapshotAt = now;
            lastLabels = describe(nodes);
        }
        if (!session) return;

        if (voicePending) {
            handleVoiceStart(nodes, now);
            return; // enquanto espera o modo de voz, não procura o "fechar"
        }
        handleClose(nodes, now);
    }

    private void handleVoiceStart(List<AccessibilityNodeInfo> nodes, long now) {
        if (now - sessionStartedAt > VOICE_WINDOW_MS) {
            voicePending = false;
            setAction("Não achei o botão do modo de voz. No Ei Claude, use \"Ajustar botões do Claude\".");
            return;
        }
        AccessibilityNodeInfo button = findButton(nodes, Prefs.getVoiceButton(this),
                VOICE_STRONG, VOICE_WEAK, VOICE_EXCLUDE);
        if (button == null) return;
        if (voiceButtonSeenAt == 0) {
            voiceButtonSeenAt = now; // espera a tela do Claude assentar antes de tocar
            rescheduleScan(VOICE_SETTLE_MS + 50);
            return;
        }
        if (now - voiceButtonSeenAt < VOICE_SETTLE_MS) {
            rescheduleScan(VOICE_SETTLE_MS);
            return;
        }
        String label = buttonLabel(button);
        if (click(button)) {
            voicePending = false;
            closeBaseline = -1;
            closeSeenAt = 0;
            setAction("Toquei no botão do modo de voz (\"" + label + "\").");
        }
    }

    private void handleClose(List<AccessibilityNodeInfo> nodes, long now) {
        if (!Prefs.isCloseByVoice(this)) return;
        List<String> phrases = foldedClosePhrases();
        if (phrases.isEmpty()) return;

        int count = countPhraseTexts(nodes, phrases);
        if (closeBaseline < 0 || count < closeBaseline) {
            closeBaseline = count;
            closeSeenAt = 0;
            return;
        }
        if (count == closeBaseline) {
            closeSeenAt = 0;
            return;
        }
        // Apareceu um "fechar" novo. Confirma depois de um instante, para não reagir a uma
        // frase que ainda está sendo transcrita (ex.: "fechar a janela").
        if (closeSeenAt == 0) {
            closeSeenAt = now;
            rescheduleScan(CLOSE_CONFIRM_MS + 50);
            return;
        }
        if (now - closeSeenAt < CLOSE_CONFIRM_MS) {
            rescheduleScan(CLOSE_CONFIRM_MS);
            return;
        }
        closeSeenAt = 0;
        closeBaseline = count;
        closeClaude(nodes);
    }

    private void closeClaude(List<AccessibilityNodeInfo> nodes) {
        sessionStartedAt = 0;
        AccessibilityNodeInfo stop = findButton(nodes, Prefs.getStopButton(this),
                STOP_STRONG, STOP_WEAK, STOP_EXCLUDE);
        boolean stopped = stop != null && click(stop);
        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_HOME);
            WakeService.requestResumeSoon();
        }, stopped ? 700 : 100);
        setAction(stopped
                ? "Ouvi \"fechar\": encerrei o modo de voz e voltei para a tela inicial."
                : "Ouvi \"fechar\": voltei para a tela inicial (não achei o botão de encerrar).");
    }

    private List<String> foldedClosePhrases() {
        List<String> out = new ArrayList<>();
        for (String p : Phrase.variants(Prefs.getClosePhrases(this))) {
            String f = Phrase.fold(p);
            if (!f.isEmpty() && !out.contains(f)) out.add(f);
        }
        return out;
    }

    // ------------------------------------------------------------------ utilidades de nós

    private static List<AccessibilityNodeInfo> collect(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && out.size() < MAX_NODES) {
            AccessibilityNodeInfo n = queue.poll();
            out.add(n);
            int children = n.getChildCount();
            for (int i = 0; i < children; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return out;
    }

    /** Nome de um botão: descrição de acessibilidade, texto de botão, dica ou id da view. */
    private static String buttonLabel(AccessibilityNodeInfo n) {
        CharSequence desc = n.getContentDescription();
        if (desc != null && desc.toString().trim().length() > 0) return desc.toString().trim();
        if (Build.VERSION.SDK_INT >= 28) {
            CharSequence tip = n.getTooltipText();
            if (tip != null && tip.toString().trim().length() > 0) return tip.toString().trim();
        }
        CharSequence cls = n.getClassName();
        if (cls != null && cls.toString().endsWith("Button")) {
            CharSequence text = n.getText();
            if (text != null && text.toString().trim().length() > 0) return text.toString().trim();
        }
        String id = n.getViewIdResourceName();
        if (id != null && !id.isEmpty()) {
            return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ').trim();
        }
        return "";
    }

    private static AccessibilityNodeInfo findButton(List<AccessibilityNodeInfo> nodes, String taught,
                                                    String[] strong, String[] weak, String[] exclude) {
        String wanted = Phrase.fold(taught);
        if (!wanted.isEmpty()) {
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isVisibleToUser() && Phrase.fold(buttonLabel(n)).equals(wanted)) return n;
            }
            // O botão escolhido à mão sumiu (o Claude pode ter mudado): tenta descobrir sozinho.
        }
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        int bestRight = Integer.MIN_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String f = Phrase.fold(buttonLabel(n));
            if (f.isEmpty() || containsAny(f, exclude)) continue;
            int score = containsAny(f, strong) ? 3 : (containsWord(f, weak) ? 1 : 0);
            if (score == 0) continue;
            n.getBoundsInScreen(r);
            // Empate: o mais à direita (a onda sonora fica ao lado do microfone).
            if (score > bestScore || (score == bestScore && r.right > bestRight)) {
                best = n;
                bestScore = score;
                bestRight = r.right;
            }
        }
        return best;
    }

    private static boolean containsAny(String folded, String[] keys) {
        for (String k : keys) {
            if (folded.contains(Phrase.fold(k))) return true;
        }
        return false;
    }

    private static boolean containsWord(String folded, String[] words) {
        String padded = " " + folded + " ";
        for (String w : words) {
            if (padded.contains(" " + Phrase.fold(w) + " ")) return true;
        }
        return false;
    }

    /** Quantos textos na tela são exatamente uma frase de fechar (botões não contam). */
    private static int countPhraseTexts(List<AccessibilityNodeInfo> nodes, List<String> phrases) {
        int count = 0;
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence text = n.getText();
            if (text == null || text.length() == 0 || text.length() > MAX_PHRASE_TEXT) continue;
            CharSequence cls = n.getClassName();
            if (cls != null && cls.toString().endsWith("Button")) continue;
            if (phrases.contains(Phrase.fold(text.toString()))) count++;
        }
        return count;
    }

    /** Lista de botões da última tela do Claude, para ajuste manual. */
    private static List<String> describe(List<AccessibilityNodeInfo> nodes) {
        Set<String> labels = new LinkedHashSet<>();
        for (AccessibilityNodeInfo n : nodes) {
            if (!n.isVisibleToUser()) continue;
            String label = buttonLabel(n);
            if (label.isEmpty() || label.length() > 60) continue;
            labels.add(label);
            if (labels.size() >= 60) break;
        }
        return new ArrayList<>(labels);
    }

    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        for (int i = 0; i < 5 && target != null; i++) {
            if (target.isClickable() && target.isEnabled()) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                break;
            }
            target = target.getParent();
        }
        // Último recurso: um toque no centro do botão.
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private static void setAction(String text) {
        lastAction = text;
        WakeService.notifyAssist(text);
    }
}
