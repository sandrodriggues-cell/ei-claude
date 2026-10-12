package com.eiclaude.relogio;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Controle remoto do Claude no celular.
 * <ul>
 * <li>Ao abrir, pede ao Ei Claude do celular para abrir o app do Claude no modo de voz
 * (o mesmo que dizer "Ei, Claude" perto do celular).</li>
 * <li>Mostra o andamento que o celular devolve.</li>
 * <li>"Encerrar" fecha o Claude no celular.</li>
 * </ul>
 * A conversa acontece no app oficial do Claude, no celular: sem API e sem custo extra.
 */
public class MainActivity extends Activity implements MessageClient.OnMessageReceivedListener {

    /** Anunciado pelo Ei Claude do celular (res/values/wear.xml do app do celular). */
    private static final String CAPABILITY = "ei_claude_celular";
    private static final String PATH_OPEN = "/ei-claude/abrir";
    private static final String PATH_CLOSE = "/ei-claude/fechar";
    private static final String PATH_STATUS = "/ei-claude/status";

    private static final long FIRST_REPLY_MS = 10000;
    private static final long LATER_REPLY_MS = 35000;
    private static final long UNLOCK_REPLY_MS = 90000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private ScrollView scroll;
    private TextView status;
    private MicButton button;
    private TextView message;
    private TextView closeChip;

    private MessageClient messages;
    private int requestGen;
    private boolean listening;

    private final Runnable noReply = () -> problem(
            "O celular não respondeu. Confira se o Ei Claude do celular está atualizado e tente de novo.");

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        try {
            messages = Wearable.getMessageClient(this);
            messages.addListener(this);
            listening = true;
        } catch (Throwable t) {
            messages = null;
        }
        if (savedInstanceState == null) send(PATH_OPEN);
        else show(MicButton.Look.IDLE, "Toque para chamar o Claude", "");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        send(PATH_OPEN); // abriu de novo (ícone ou atalho do botão): chama o Claude
    }

    @Override
    protected void onResume() {
        super.onResume();
        scroll.requestFocus();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (messages != null && listening) {
            try {
                messages.removeListener(this);
            } catch (Throwable ignored) {
                // já removido
            }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ tela

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        scroll = Ui.scroller(this);
        LinearLayout column = Ui.column(this);
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        status = Ui.text(this, 12, Ui.MUTED, false);
        column.addView(status, Ui.wide(this, 0));

        button = new MicButton(this);
        button.setOnClickListener(v -> send(PATH_OPEN));
        int size = Math.round(Ui.screenWidth(this) * 0.40f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(button, lp);

        message = Ui.text(this, 14, Ui.INK, false);
        column.addView(message, Ui.wide(this, 4));

        closeChip = Ui.chip(this, "Encerrar o Claude", v -> send(PATH_CLOSE));
        column.addView(closeChip);
    }

    private void show(MicButton.Look look, String title, String text) {
        button.setLook(look);
        status.setText(title);
        message.setText(text);
        message.setTextColor(look == MicButton.Look.DISABLED ? Ui.WARN : Ui.INK);
    }

    private void problem(String text) {
        main.removeCallbacks(noReply);
        show(MicButton.Look.DISABLED, "Toque para tentar de novo", text);
    }

    // ------------------------------------------------------------------ falar com o celular

    private void send(String path) {
        final int gen = ++requestGen;
        main.removeCallbacks(noReply);
        boolean open = PATH_OPEN.equals(path);
        show(MicButton.Look.THINKING, open ? "Chamando o celular…" : "Encerrando…", "");
        scroll.post(() -> scroll.scrollTo(0, 0));
        if (messages == null) {
            problem("Este relógio não tem o serviço de comunicação com o celular (Google Play Services).");
            return;
        }
        try {
            Wearable.getCapabilityClient(this)
                    .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .addOnSuccessListener(info -> {
                        if (gen != requestGen) return;
                        String node = pick(info == null ? null : info.getNodes());
                        if (node == null) {
                            explainNoPhone(gen);
                            return;
                        }
                        deliver(gen, node, path);
                    })
                    .addOnFailureListener(e -> {
                        if (gen == requestGen) problem("Não consegui falar com o celular. Confira a conexão.");
                    });
        } catch (Throwable t) {
            problem("Não consegui falar com o celular. Confira a conexão.");
        }
    }

    private void deliver(int gen, String node, String path) {
        messages.sendMessage(node, path, new byte[0])
                .addOnSuccessListener(id -> {
                    if (gen != requestGen) return;
                    main.removeCallbacks(noReply);
                    main.postDelayed(noReply, FIRST_REPLY_MS);
                })
                .addOnFailureListener(e -> {
                    if (gen == requestGen) problem("O pedido não chegou ao celular. Confira a conexão e tente de novo.");
                });
    }

    /** Ninguém com o Ei Claude novo: diz se é falta de conexão ou de atualizar o app do celular. */
    private void explainNoPhone(int gen) {
        try {
            Wearable.getNodeClient(this).getConnectedNodes()
                    .addOnSuccessListener(nodes -> {
                        if (gen != requestGen) return;
                        if (nodes == null || nodes.isEmpty()) {
                            problem("O relógio está sem conexão com o celular.");
                        } else {
                            problem("Achei o celular, mas o Ei Claude dele está desatualizado. "
                                    + "Instale no celular a versão nova (v4 ou mais) e tente de novo.");
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (gen == requestGen) problem("O relógio está sem conexão com o celular.");
                    });
        } catch (Throwable t) {
            problem("O relógio está sem conexão com o celular.");
        }
    }

    /** Prefere o celular conectado direto (Bluetooth) ao que só responde pela nuvem. */
    private static String pick(Set<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        String any = null;
        for (Node n : nodes) {
            if (n.isNearby()) return n.getId();
            if (any == null) any = n.getId();
        }
        return any;
    }

    // ------------------------------------------------------------------ respostas do celular

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (event == null || !PATH_STATUS.equals(event.getPath())) return;
        byte[] data = event.getData();
        String s = data == null ? "" : new String(data, StandardCharsets.UTF_8);
        int nl = s.indexOf('\n');
        final String code = nl >= 0 ? s.substring(0, nl) : s;
        final String text = nl >= 0 ? s.substring(nl + 1) : "";
        main.post(() -> onStatus(code, text));
    }

    private void onStatus(String code, String text) {
        if (isFinishing() || isDestroyed()) return;
        main.removeCallbacks(noReply);
        switch (code) {
            case "abrindo":
                show(MicButton.Look.THINKING, "Abrindo no celular…", text);
                main.postDelayed(noReply, LATER_REPLY_MS);
                break;
            case "desbloquear":
                show(MicButton.Look.THINKING, "Desbloqueie o celular", text);
                main.postDelayed(noReply, UNLOCK_REPLY_MS); // dá tempo de tirar o celular do bolso
                break;
            case "voz":
                Ui.buzz(this, 60);
                show(MicButton.Look.SPEAKING, "Claude ouvindo no celular", text);
                break;
            case "ja_aberto":
                Ui.buzz(this, 40);
                show(MicButton.Look.SPEAKING, "Claude já está ouvindo", text);
                break;
            case "fechado":
                Ui.buzz(this, 60);
                show(MicButton.Look.IDLE, "Encerrado", text);
                main.postDelayed(this::finishAndRemoveTask, 1800);
                break;
            case "nada":
                show(MicButton.Look.IDLE, "Toque para chamar o Claude", text);
                break;
            case "sem_acessibilidade":
            case "sem_botao":
                show(MicButton.Look.IDLE, "Claude aberto no celular", text);
                message.setTextColor(Ui.WARN);
                break;
            default:
                problem(text.isEmpty() ? "O celular respondeu com um erro (" + code + ")." : text);
        }
    }
}
