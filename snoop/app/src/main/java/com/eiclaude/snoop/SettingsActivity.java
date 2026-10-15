package com.eiclaude.snoop;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ajustes no relógio. A chave do Gemini é colada pelo celular, numa página servida pelo relógio. */
public class SettingsActivity extends Activity implements SetupServer.Listener {

    private static final long SERVER_MAX_MS = 15L * 60 * 1000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ScrollView scroll;
    private TextView keyStatus;
    private TextView addressHint;
    private TextView address;
    private TextView typeChip;
    private LinearLayout typeRow;
    private EditText keyField;
    private TextView modelChip;
    private TextView voiceChip;
    private TextView idleChip;
    private TextView greetChip;
    private TextView quietChip;
    private TextView missChip;
    private TextView keepChip;
    private TextView shiftChip;
    private TextView backChip;
    private TextView remindersChip;
    private TextView remindersText;
    private TextView notesChip;
    private TextView notesText;
    private TextView forgetChip;
    private TextView diag;

    private SetupServer server;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private boolean testing;
    private long forgetArmedAt;

    private final Runnable stopServerLater = () -> {
        stopServer();
        refreshAddress();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        server = new SetupServer(this, this);
        requestWifi();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        scroll.requestFocus();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        stopServer();
        releaseWifi();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ tela

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        scroll = Ui.scroller(this);
        LinearLayout col = Ui.column(this);
        scroll.addView(col, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        TextView title = Ui.text(this, 16, Ui.INK, true);
        title.setText("Ajustes do Snoop");
        col.addView(title, Ui.wide(this, 0));

        keyStatus = Ui.text(this, 13, Ui.SOFT, false);
        col.addView(keyStatus, Ui.wide(this, 6));

        addressHint = Ui.text(this, 12, Ui.MUTED, false);
        col.addView(addressHint, Ui.wide(this, 10));

        address = Ui.text(this, 15, Ui.ACCENT, true);
        col.addView(address, Ui.wide(this, 4));

        typeChip = Ui.chip(this, "Digitar a chave aqui", v -> toggleTyping());
        col.addView(typeChip);

        typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.VERTICAL);
        typeRow.setVisibility(View.GONE);
        keyField = new EditText(this);
        keyField.setSingleLine(true);
        keyField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        keyField.setTextColor(Ui.INK);
        keyField.setHintTextColor(Ui.MUTED);
        keyField.setHint("chave do Gemini");
        keyField.setGravity(Gravity.CENTER_HORIZONTAL);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keyField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        keyField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveTyped();
                return true;
            }
            return false;
        });
        typeRow.addView(keyField, Ui.wide(this, 6));
        typeRow.addView(Ui.chip(this, "Salvar e testar", v -> saveTyped()));
        col.addView(typeRow, Ui.wide(this, 0));

        modelChip = Ui.chip(this, "", v -> {
            Prefs.setModel(this, Prefs.otherModel(Prefs.model(this)));
            refreshAll();
        });
        col.addView(modelChip);

        voiceChip = Ui.chip(this, "", v -> cycleVoice());
        col.addView(voiceChip);

        backChip = Ui.chip(this, "", v -> {
            Backdrop.cycle(this);
            refreshAll();
        });
        col.addView(backChip);

        idleChip = Ui.chip(this, "", v -> {
            Prefs.setIdleMinutes(this, next(Prefs.IDLE_OPTIONS, Prefs.idleMinutes(this)));
            Pulse.schedule(this);
            refreshAll();
        });
        col.addView(idleChip);

        greetChip = Ui.chip(this, "", v -> {
            Prefs.setGreetMinute(this, next(Prefs.GREET_OPTIONS, Prefs.greetMinute(this)));
            Pulse.schedule(this);
            refreshAll();
        });
        col.addView(greetChip);

        quietChip = Ui.chip(this, "", v -> cycleQuiet());
        col.addView(quietChip);

        missChip = Ui.chip(this, "", v -> {
            Prefs.setMissYou(this, !Prefs.missYou(this));
            refreshAll();
        });
        col.addView(missChip);

        keepChip = Ui.chip(this, "", v -> {
            Prefs.setKeepListening(this, !Prefs.keepListening(this));
            refreshAll();
        });
        col.addView(keepChip);

        shiftChip = Ui.chip(this, "", v -> {
            if (Shift.reference(this) == null) {
                Toast.makeText(this, "Configure a escala na página do celular (endereço acima)", Toast.LENGTH_LONG).show();
                return;
            }
            Shift.setEnabled(this, !Shift.enabled(this));
            Pulse.schedule(this);
            refreshAll();
        });
        col.addView(shiftChip);

        remindersChip = Ui.chip(this, "", v -> {
            boolean show = remindersText.getVisibility() != View.VISIBLE;
            remindersText.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        col.addView(remindersChip);
        remindersText = Ui.text(this, 12, Ui.SOFT, false);
        remindersText.setVisibility(View.GONE);
        col.addView(remindersText, Ui.wide(this, 6));
        col.addView(Ui.chip(this, "Apagar lembretes", v -> {
            Reminders.clear(this);
            Toast.makeText(this, "Lembretes apagados", Toast.LENGTH_SHORT).show();
            refreshAll();
        }));

        col.addView(Ui.chip(this, "Testar um aviso", v -> {
            if (Pulse.say(this, Notifier.ID_TEST, Phrases.greeting(this), "animado")) {
                Toast.makeText(this, "Aviso enviado", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permita as notificações do Snoop", Toast.LENGTH_LONG).show();
            }
        }));

        notesChip = Ui.chip(this, "", v -> {
            boolean show = notesText.getVisibility() != View.VISIBLE;
            notesText.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        col.addView(notesChip);
        notesText = Ui.text(this, 12, Ui.SOFT, false);
        notesText.setVisibility(View.GONE);
        col.addView(notesText, Ui.wide(this, 6));

        forgetChip = Ui.chip(this, "Esquecer conversas e lembranças", v -> forget());
        col.addView(forgetChip);

        diag = Ui.text(this, 11, Ui.MUTED, false);
        col.addView(diag, Ui.wide(this, 14));

        col.addView(Ui.chip(this, "Voltar", v -> finish()));
    }

    private static int next(int[] options, int current) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) return options[(i + 1) % options.length];
        }
        return options[0];
    }

    private void cycleVoice() {
        List<String> order = new ArrayList<>();
        order.add(Prefs.VOICE_WATCH);
        for (String v : Prefs.GEMINI_VOICES) order.add(v);
        String current = Prefs.VOICE_GEMINI.equals(Prefs.voice(this)) ? Prefs.geminiVoice(this) : Prefs.VOICE_WATCH;
        int i = order.indexOf(current);
        String next = order.get((i + 1) % order.size());
        if (Prefs.VOICE_WATCH.equals(next)) {
            Prefs.setVoice(this, Prefs.VOICE_WATCH);
        } else {
            Prefs.setVoice(this, Prefs.VOICE_GEMINI);
            Prefs.setGeminiVoice(this, next);
        }
        refreshAll();
    }

    private void cycleQuiet() {
        int s = Prefs.quietStart(this);
        int e = Prefs.quietEnd(this);
        int idx = -1;
        for (int i = 0; i < Prefs.QUIET_OPTIONS.length; i++) {
            int[] o = Prefs.QUIET_OPTIONS[i];
            if ((o[0] == s && o[1] == e) || (o[0] == o[1] && s == e)) {
                idx = i;
                break;
            }
        }
        int[] n = Prefs.QUIET_OPTIONS[(idx + 1) % Prefs.QUIET_OPTIONS.length];
        Prefs.setQuiet(this, n[0], n[1]);
        refreshAll();
    }

    private void forget() {
        long now = System.currentTimeMillis();
        if (now - forgetArmedAt > 4000) {
            forgetArmedAt = now;
            forgetChip.setText("Toque de novo para confirmar");
            main.postDelayed(() -> forgetChip.setText("Esquecer conversas e lembranças"), 4000);
            return;
        }
        forgetArmedAt = 0;
        Brain.forgetAll(this);
        forgetChip.setText("Esquecer conversas e lembranças");
        Toast.makeText(this, "O Snoop esqueceu tudo", Toast.LENGTH_SHORT).show();
        refreshAll();
    }

    private void refreshAll() {
        boolean hasKey = Prefs.hasKey(this);
        keyStatus.setText(hasKey ? "Chave do Gemini: configurada ✓" : "Chave do Gemini: falta configurar");
        keyStatus.setTextColor(hasKey ? Ui.OK : Ui.WARN);
        modelChip.setText("Cérebro: " + (Prefs.MODEL_SMART.equals(Prefs.model(this)) ? "esperto" : "rápido"));
        voiceChip.setText(Prefs.VOICE_GEMINI.equals(Prefs.voice(this))
                ? "Voz: Gemini (" + Prefs.geminiVoice(this) + ")" : "Voz: leitor do relógio");
        int idle = Prefs.idleMinutes(this);
        idleChip.setText(idle > 0 ? "Aviso de parado: " + idle + " min" : "Aviso de parado: desligado");
        greetChip.setText("Saudação: " + Prefs.hhmm(Prefs.greetMinute(this)));
        quietChip.setText("Silêncio: " + Prefs.quietLabel(this));
        missChip.setText(Prefs.missYou(this) ? "Saudade: ligada" : "Saudade: desligada");
        keepChip.setText(Prefs.keepListening(this) ? "Conversa contínua: sim" : "Conversa contínua: não");
        long nowMs = System.currentTimeMillis();
        backChip.setText("Fundo: " + Backdrop.label(this));
        Backdrop.Look bl = Backdrop.look(this);
        android.graphics.drawable.GradientDrawable sw = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{bl.center, bl.edge});
        sw.setCornerRadius(Ui.dp(this, 22));
        sw.setStroke(Ui.dp(this, 1), 0x55FFFFFF);
        backChip.setBackground(sw);
        backChip.setTextColor(bl.ink);
        if (Shift.reference(this) == null) {
            shiftChip.setText("Escala: configurar no celular");
        } else if (Shift.enabled(this)) {
            shiftChip.setText("Escala: " + Shift.workDays(this) + "x" + Shift.offDays(this) + " · "
                    + (Shift.isOff(this, Shift.date(nowMs)) ? "hoje é folga" : "hoje tem turno"));
        } else {
            shiftChip.setText("Escala: desligada");
        }
        List<Reminders.Item> reminders = Reminders.list(this);
        remindersChip.setText("Lembretes (" + reminders.size() + ")");
        StringBuilder rb = new StringBuilder();
        if (reminders.isEmpty()) rb.append("Nenhum. Peça por voz: \"Snoop, me lembra de tomar água às 15h\".");
        for (Reminders.Item it : reminders) rb.append("• ").append(Reminders.label(it)).append('\n');
        remindersText.setText(rb.toString().trim());
        List<String> notes = Brain.notes(this);
        notesChip.setText("O que o Snoop sabe (" + notes.size() + ")");
        StringBuilder nb = new StringBuilder();
        if (notes.isEmpty()) nb.append("Nada ainda. Ele guarda o que você contar sobre você.");
        for (String n : notes) nb.append("• ").append(n).append('\n');
        notesText.setText(nb.toString().trim());
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // sem versão
        }
        long idleNow = Moves.idleMinutes(this, System.currentTimeMillis());
        String steps = Prefs.getString(this, "passos_status");
        diag.setText("Pedidos ao Gemini hoje: " + Prefs.requestsToday(this)
                + "\nPassos: " + (steps.isEmpty() ? "aguardando" : steps)
                + (idleNow >= 0 ? " · parado há " + idleNow + " min" : " · sem dados ainda")
                + "\nVoz do relógio: " + Prefs.ttsStatus(this)
                + "\nVersão: " + version);
        refreshAddress();
    }

    // ------------------------------------------------------------------ página no celular

    private void refreshAddress() {
        if (isFinishing()) return;
        String ip = wifiAddress();
        if (ip == null) {
            stopServer();
            addressHint.setText("Para colar a chave pelo celular, ligue o Wi-Fi do relógio na mesma rede do celular.");
            address.setText("");
            address.setVisibility(View.GONE);
            root.setKeepScreenOn(false);
            return;
        }
        if (!server.isRunning()) {
            try {
                server.start();
                main.removeCallbacks(stopServerLater);
                main.postDelayed(stopServerLater, SERVER_MAX_MS);
            } catch (Exception e) {
                addressHint.setText("Não consegui abrir a página de ajustes: " + e.getMessage());
                address.setVisibility(View.GONE);
                return;
            }
        }
        addressHint.setText("No celular, no mesmo Wi-Fi, abra no navegador:");
        address.setText("http://" + ip + ":" + server.port() + "/" + server.pin());
        address.setVisibility(View.VISIBLE);
        root.setKeepScreenOn(true); // a página só funciona com esta tela aberta
    }

    private void stopServer() {
        main.removeCallbacks(stopServerLater);
        if (server != null) server.stop();
        if (root != null) root.setKeepScreenOn(false);
    }

    @Override
    public void onSaved(boolean keyWorks, String message) {
        if (isFinishing()) return;
        refreshAll();
        keyStatus.setText(message);
        keyStatus.setTextColor(keyWorks ? Ui.OK : Ui.WARN);
        Ui.buzz(this, keyWorks ? 60 : 200);
        if (keyWorks) {
            Prefs.requestGreeting(this);
            main.postDelayed(this::finish, 2500);
        }
    }

    // ------------------------------------------------------------------ digitar no relógio

    private void toggleTyping() {
        boolean show = typeRow.getVisibility() != View.VISIBLE;
        typeRow.setVisibility(show ? View.VISIBLE : View.GONE);
        typeChip.setText(show ? "Esconder campo da chave" : "Digitar a chave aqui");
        if (show) keyField.requestFocus();
    }

    private void saveTyped() {
        if (testing) return;
        String key = keyField.getText().toString().replaceAll("\\s+", "");
        if (!Prefs.looksLikeKey(key)) {
            keyStatus.setText("Essa chave parece incompleta.");
            keyStatus.setTextColor(Ui.WARN);
            return;
        }
        Prefs.setApiKey(this, key);
        keyField.setText("");
        testing = true;
        keyStatus.setText("Testando a chave…");
        keyStatus.setTextColor(Ui.SOFT);
        final String model = Prefs.model(this);
        new Thread(() -> {
            boolean ok;
            String msg;
            try {
                Gemini.chat(key, model, "Você é um teste. Responda só: oi.", new ArrayList<>(), null, null, "teste");
                ok = true;
                msg = "Chave funcionando ✓";
            } catch (Gemini.ApiException e) {
                ok = false;
                msg = e.getMessage();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            main.post(() -> {
                testing = false;
                onSaved(fOk, fMsg);
            });
        }, "Snoop-teste").start();
    }

    // ------------------------------------------------------------------ Wi-Fi

    /** Pede ao relógio para manter o Wi-Fi ligado enquanto os ajustes estão abertos. */
    private void requestWifi() {
        try {
            connectivity = getSystemService(ConnectivityManager.class);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            wifiCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    main.post(SettingsActivity.this::refreshAddress);
                }

                @Override
                public void onLost(Network network) {
                    main.post(SettingsActivity.this::refreshAddress);
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    main.post(SettingsActivity.this::refreshAddress);
                }
            };
            connectivity.requestNetwork(request, wifiCallback);
        } catch (Throwable t) {
            wifiCallback = null;
        }
    }

    private void releaseWifi() {
        if (connectivity == null || wifiCallback == null) return;
        try {
            connectivity.unregisterNetworkCallback(wifiCallback);
        } catch (Throwable ignored) {
            // já liberado
        }
        wifiCallback = null;
    }

    /** Endereço IPv4 do relógio na rede Wi-Fi, ou null. */
    private String wifiAddress() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
            // tenta pelas interfaces
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
            // sem Wi-Fi
        }
        return null;
    }
}
