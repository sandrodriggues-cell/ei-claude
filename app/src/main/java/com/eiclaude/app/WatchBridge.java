package com.eiclaude.app;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.SystemClock;

import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;

/**
 * Ponte com o app do relógio (Wear OS Data Layer).
 * O relógio pede "abrir" ou "fechar"; o celular faz o mesmo que a frase de ativação e o comando
 * "fechar" e responde com o andamento ("código" + texto curto para mostrar no relógio).
 * Nada de API: a conversa acontece no app oficial do Claude, no celular.
 */
final class WatchBridge {

    static final String PATH_OPEN = "/ei-claude/abrir";
    static final String PATH_CLOSE = "/ei-claude/fechar";
    static final String PATH_STATUS = "/ei-claude/status";

    private static final long ACTIVE_MS = 15L * 60 * 1000;

    private static volatile String nodeId;
    private static volatile long lastAt;

    private WatchBridge() {
    }

    static void remember(String id) {
        if (id == null || id.isEmpty()) return;
        nodeId = id;
        lastAt = SystemClock.elapsedRealtime();
    }

    /** O relógio falou com o celular há pouco? */
    static boolean active() {
        return nodeId != null && SystemClock.elapsedRealtime() - lastAt < ACTIVE_MS;
    }

    /** Manda o andamento para a tela do relógio (se ele estiver aberto, aparece; se não, some). */
    static void send(Context c, String code, String text) {
        String id = nodeId;
        if (id == null || !active()) return;
        byte[] data = (code + "\n" + text).getBytes(StandardCharsets.UTF_8);
        try {
            Wearable.getMessageClient(c.getApplicationContext()).sendMessage(id, PATH_STATUS, data);
        } catch (Throwable ignored) {
            // relógio fora de alcance: segue sem avisar
        }
        WakeService.notifyAssist("Relógio: " + text);
    }

    /** Pedido "abrir" do relógio. Thread principal. */
    static void open(Context c) {
        if (c.getPackageManager().getLaunchIntentForPackage(WakeService.CLAUDE_PACKAGE) == null) {
            send(c, "sem_claude", "O app do Claude não está instalado no celular.");
            return;
        }
        WakeService.pauseForClaude(); // libera o microfone, se a escuta estiver ligada
        ClaudeAssistService.beginSession(c, true);
        if (!WakeService.launchClaude(c)) {
            WakeService.showOpenClaudeNotification(c);
            send(c, "sem_permissao", "O celular não deixou abrir o Claude. No app Ei Claude do celular, "
                    + "libere \"Aparecer sobre outros apps\".");
            return;
        }
        boolean locked = isLocked(c);
        if (!ClaudeAssistService.isConnected()) {
            send(c, "sem_acessibilidade", (locked ? "Desbloqueie o celular. " : "")
                    + "Abri o Claude, mas o modo de voz só liga sozinho com a Acessibilidade do Ei Claude "
                    + "ligada no celular.");
        } else if (locked) {
            send(c, "desbloquear", "Se o celular pedir, desbloqueie: o Claude abre em seguida.");
        } else {
            send(c, "abrindo", "Abrindo o Claude no celular…");
        }
    }

    /** Pedido "fechar" do relógio. Thread principal. */
    static void close(Context c) {
        ClaudeAssistService.closeFromWatch(c);
    }

    static boolean isLocked(Context c) {
        try {
            KeyguardManager km = c.getSystemService(KeyguardManager.class);
            return km != null && km.isDeviceLocked();
        } catch (Throwable t) {
            return false;
        }
    }
}
