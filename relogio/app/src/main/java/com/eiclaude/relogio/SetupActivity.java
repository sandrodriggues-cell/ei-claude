package com.eiclaude.relogio;

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
import java.util.Collections;

/**
 * Ajustes: chave da API (de preferência colada pelo celular, numa página servida pelo
 * relógio), modelo, voz offline, nova conversa e diagnóstico.
 */
public class SetupActivity extends Activity implements SetupServer.Listener {

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
    private TextView offlineChip;
    private TextView diag;

    private SetupServer server;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private boolean testing;

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
        title.setText("Ajustes");
        col.addView(title, Ui.wide(this, 0));

        keyStatus = Ui.text(this, 13, Ui.SOFT, false);
        col.addView(keyStatus, Ui.wide(this, 6));

        addressHint = Ui.text(this, 12, Ui.MUTED, false);
        col.addView(addressHint, Ui.wide(this, 12));

        address = Ui.text(this, 15, Ui.ACCENT, true);
        address.setTextIsSelectable(false);
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
        keyField.setHint("sk-ant-…");
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
            String next = Prefs.MODEL_SONNET.equals(Prefs.model(this)) ? Prefs.MODEL_HAIKU : Prefs.MODEL_SONNET;
            Prefs.setModel(this, next);
            refreshAll();
        });
        col.addView(modelChip);

        offlineChip = Ui.chip(this, "", v -> {
            Prefs.setOfflineOnly(this, !Prefs.offlineOnly(this));
            refreshAll();
        });
        col.addView(offlineChip);

        col.addView(Ui.chip(this, "Nova conversa", v -> {
            Prefs.requestNewChat(this);
            Toast.makeText(this, "A próxima fala começa uma conversa nova.", Toast.LENGTH_SHORT).show();
        }));

        diag = Ui.text(this, 11, Ui.MUTED, false);
        col.addView(diag, Ui.wide(this, 14));

        col.addView(Ui.chip(this, "Voltar", v -> finish()));
    }

    private void refreshAll() {
        boolean hasKey = Prefs.hasKey(this);
        keyStatus.setText(hasKey ? "Chave da API: configurada ✓" : "Chave da API: falta configurar");
        keyStatus.setTextColor(hasKey ? Ui.OK : Ui.WARN);
        String model = Prefs.model(this);
        modelChip.setText("Modelo: " + Prefs.modelLabel(model)
                + (Prefs.MODEL_HAIKU.equals(model) ? " (rápido)" : ""));
        offlineChip.setText(Prefs.offlineOnly(this) ? "Voz offline: sempre" : "Voz offline: só se precisar");
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // sem versão
        }
        diag.setText("Entrada de voz: " + Prefs.engineLabel(this)
                + "\nSaída de voz: " + Prefs.ttsStatus(this)
                + "\nUso total: " + Text.number(Prefs.tokensIn(this)) + " tokens de entrada e "
                + Text.number(Prefs.tokensOut(this)) + " de saída"
                + "\nVersão: " + version);
        refreshAddress();
    }

    // ------------------------------------------------------------------ página no celular

    private void refreshAddress() {
        if (isFinishing()) return;
        String ip = wifiAddress();
        if (ip == null) {
            stopServer();
            addressHint.setText("Para colar a chave pelo celular, ligue o Wi-Fi do relógio na mesma rede "
                    + "do celular. Ou digite a chave aqui.");
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
        if (keyWorks) finishWithGreeting();
    }

    private void finishWithGreeting() {
        Prefs.requestGreeting(this);
        main.postDelayed(this::finish, 2500);
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
            keyStatus.setText("A chave começa com sk-ant- e não tem espaços.");
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
                ClaudeApi.send(key, model, "Responda apenas: ok",
                        Collections.singletonList(new ClaudeApi.Msg("user", "teste")), 16);
                ok = true;
                msg = "Chave funcionando ✓";
            } catch (ClaudeApi.ApiException e) {
                ok = false;
                msg = e.getMessage();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            main.post(() -> {
                testing = false;
                onSaved(fOk, fMsg);
            });
        }, "EiClaude-teste").start();
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
                    main.post(SetupActivity.this::refreshAddress);
                }

                @Override
                public void onLost(Network network) {
                    main.post(SetupActivity.this::refreshAddress);
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    main.post(SetupActivity.this::refreshAddress);
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
