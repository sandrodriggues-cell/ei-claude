package com.eiclaude.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Tela principal: preparação das permissões, liga/desliga, frase, sensibilidade e modo teste. */
public class MainActivity extends Activity implements WakeService.UiListener {

    static final String ACTION_AUTO_START = "com.eiclaude.app.AUTO_START";

    private static final int REQ_MIC = 10;
    private static final int REQ_NOTIF = 11;

    private TextView tvStatus;
    private TextView tvPhraseInfo;
    private TextView tvSensitivity;
    private TextView tvHeard;
    private Button btnToggle;
    private Button btnMic;
    private Button btnNotif;
    private Button btnOverlay;
    private Button btnBattery;
    private Button btnClaude;
    private EditText etPhrase;
    private SeekBar sbSensitivity;
    private Switch swTest;
    private Button btnA11y;
    private Button btnRestricted;
    private Switch swAutoVoice;
    private Switch swCloseVoice;
    private EditText etClose;
    private TextView tvAssist;

    private boolean startAfterMicGrant;
    private boolean autoStartPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvPhraseInfo = findViewById(R.id.tvPhraseInfo);
        tvSensitivity = findViewById(R.id.tvSensitivity);
        tvHeard = findViewById(R.id.tvHeard);
        btnToggle = findViewById(R.id.btnToggle);
        btnMic = findViewById(R.id.btnMic);
        btnNotif = findViewById(R.id.btnNotif);
        btnOverlay = findViewById(R.id.btnOverlay);
        btnBattery = findViewById(R.id.btnBattery);
        btnClaude = findViewById(R.id.btnClaude);
        etPhrase = findViewById(R.id.etPhrase);
        sbSensitivity = findViewById(R.id.sbSensitivity);
        swTest = findViewById(R.id.swTest);
        Button btnSavePhrase = findViewById(R.id.btnSavePhrase);

        btnToggle.setOnClickListener(v -> onToggle());
        btnMic.setOnClickListener(v -> requestMic(false));
        btnNotif.setOnClickListener(v -> requestNotifications());
        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        btnBattery.setOnClickListener(v -> openBatterySettings());
        btnClaude.setOnClickListener(v -> openClaudeOrStore());
        btnSavePhrase.setOnClickListener(v -> savePhrase());
        etPhrase.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                savePhrase();
                return true;
            }
            return false;
        });

        etPhrase.setText(Prefs.getPhrase(this));
        int sensitivity = Prefs.getSensitivity(this);
        sbSensitivity.setProgress(sensitivity);
        updateSensitivityLabel(sensitivity);
        sbSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSensitivityLabel(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Prefs.setSensitivity(MainActivity.this, seekBar.getProgress());
            }
        });

        swTest.setOnCheckedChangeListener((button, checked) -> onTestModeChanged(checked));

        // Modo de voz automático e "fechar"
        btnA11y = findViewById(R.id.btnA11y);
        btnRestricted = findViewById(R.id.btnRestricted);
        swAutoVoice = findViewById(R.id.swAutoVoice);
        swCloseVoice = findViewById(R.id.swCloseVoice);
        etClose = findViewById(R.id.etClose);
        tvAssist = findViewById(R.id.tvAssist);
        Button btnSaveClose = findViewById(R.id.btnSaveClose);
        Button btnDiag = findViewById(R.id.btnDiag);

        swAutoVoice.setChecked(Prefs.isAutoVoice(this));
        swCloseVoice.setChecked(Prefs.isCloseByVoice(this));
        etClose.setText(Prefs.getClosePhrases(this));
        swAutoVoice.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setAutoVoice(this, checked);
            refreshAssistInfo();
        });
        swCloseVoice.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setCloseByVoice(this, checked);
            refreshAssistInfo();
        });
        btnA11y.setOnClickListener(v -> openAccessibilitySettings());
        btnRestricted.setOnClickListener(v -> openRestrictedSettingsHelp());
        btnSaveClose.setOnClickListener(v -> saveClosePhrases());
        btnDiag.setOnClickListener(v -> showClaudeButtons());
        etClose.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveClosePhrases();
                return true;
            }
            return false;
        });

        autoStartPending = ACTION_AUTO_START.equals(getIntent().getAction());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_AUTO_START.equals(intent.getAction())) autoStartPending = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        WakeService.uiListener = this;
        refresh();
        onStatus(WakeService.lastStatus);
        onPhraseInfo(WakeService.lastPhraseInfo);
        if (autoStartPending) {
            autoStartPending = false;
            if (Prefs.isEnabled(this) && !WakeService.running) startListeningService();
        }
    }

    @Override
    protected void onPause() {
        if (WakeService.uiListener == this) WakeService.uiListener = null;
        // O modo teste só faz sentido com a tela aberta.
        if (swTest.isChecked()) swTest.setChecked(false);
        super.onPause();
    }

    // ------------------------------------------------------------------ liga / desliga

    private void onToggle() {
        if (WakeService.running) {
            Prefs.setEnabled(this, false);
            sendToService(WakeService.ACTION_STOP, false);
            btnToggle.setText("Ligar a escuta");
            return;
        }
        if (!hasMic()) {
            requestMic(true);
            return;
        }
        if (!isClaudeInstalled()) {
            Toast.makeText(this, "Instale o app do Claude primeiro.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Sem \"Aparecer sobre outros apps\", o Claude não abre sozinho: vai aparecer um aviso para você tocar.",
                    Toast.LENGTH_LONG).show();
        }
        startListeningService();
    }

    private void startListeningService() {
        if (!hasMic()) return;
        Prefs.setEnabled(this, true);
        sendToService(WakeService.ACTION_START, true);
        btnToggle.setText("Desligar a escuta");
    }

    private void reloadIfRunning() {
        if (WakeService.running) sendToService(WakeService.ACTION_RELOAD, true);
    }

    private void sendToService(String action, boolean foreground) {
        Intent i = new Intent(this, WakeService.class).setAction(action);
        try {
            if (foreground) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Não consegui ligar o serviço: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ preparação

    private void refresh() {
        setRow(btnMic, hasMic(), "Microfone liberado", "Liberar o microfone");
        setRow(btnNotif, hasNotifications(), "Notificações liberadas", "Liberar as notificações");
        setRow(btnOverlay, Settings.canDrawOverlays(this),
                "Pode aparecer sobre outros apps", "Liberar \"Aparecer sobre outros apps\"");
        setRow(btnBattery, isIgnoringBattery(), "Bateria sem restrições", "Tirar da otimização de bateria");
        setRow(btnClaude, isClaudeInstalled(), "App do Claude instalado", "Instalar o app do Claude");
        btnToggle.setText(WakeService.running ? "Desligar a escuta" : "Ligar a escuta");

        boolean a11y = isAccessibilityOn();
        setRow(btnA11y, a11y, "Acessibilidade ligada", "Ligar a Acessibilidade do Ei Claude");
        btnRestricted.setVisibility(!a11y && Build.VERSION.SDK_INT >= 33 ? View.VISIBLE : View.GONE);
        refreshAssistInfo();
    }

    private void setRow(Button b, boolean ok, String okText, String todoText) {
        b.setText(ok ? "✓  " + okText : "○  " + todoText);
        b.setTextColor(getColor(ok ? R.color.ok : R.color.warn));
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotifications() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean isClaudeInstalled() {
        return getPackageManager().getLaunchIntentForPackage(WakeService.CLAUDE_PACKAGE) != null;
    }

    private void requestMic(boolean thenStart) {
        if (hasMic()) {
            if (thenStart) startListeningService();
            else Toast.makeText(this, "O microfone já está liberado.", Toast.LENGTH_SHORT).show();
            return;
        }
        startAfterMicGrant = thenStart;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    private void requestNotifications() {
        if (hasNotifications()) {
            Toast.makeText(this, "As notificações já estão liberadas.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MIC) {
            if (granted) {
                if (startAfterMicGrant) startListeningService();
            } else {
                Toast.makeText(this, "Sem o microfone o app não consegue ouvir. Libere em Permissões.",
                        Toast.LENGTH_LONG).show();
                openAppSettings();
            }
            startAfterMicGrant = false;
        } else if (requestCode == REQ_NOTIF && !granted) {
            Toast.makeText(this, "Libere as notificações em Notificações.", Toast.LENGTH_LONG).show();
            openAppSettings();
        }
        refresh();
    }

    private void openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Já está liberado.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Ative \"Ei Claude\" na lista e volte para cá.", Toast.LENGTH_LONG).show();
        tryStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void openBatterySettings() {
        if (isIgnoringBattery()) {
            Toast.makeText(this, "Já está sem restrições.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent ask = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        if (!tryStart(ask)) tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    }

    private void openClaudeOrStore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(WakeService.CLAUDE_PACKAGE);
        if (launch != null) {
            tryStart(launch);
            return;
        }
        if (!tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + WakeService.CLAUDE_PACKAGE)))) {
            tryStart(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + WakeService.CLAUDE_PACKAGE)));
        }
    }

    private void openAppSettings() {
        tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ frase e sensibilidade

    private void savePhrase() {
        List<String> variants = Phrase.variants(etPhrase.getText().toString());
        String value = String.join(", ", variants);
        Prefs.setPhrase(this, value);
        etPhrase.setText(value);
        etPhrase.setSelection(value.length());
        hideKeyboard();
        Toast.makeText(this, value.isEmpty() ? "Modo automático." : "Frase salva.", Toast.LENGTH_SHORT).show();
        if (WakeService.running) {
            reloadIfRunning();
        } else {
            tvPhraseInfo.setText("A frase é conferida quando a escuta estiver ligada.");
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(etPhrase.getWindowToken(), 0);
        etPhrase.clearFocus();
    }

    private void updateSensitivityLabel(int progress) {
        int need = Math.round(Prefs.thresholdFor(progress) * 100);
        tvSensitivity.setText(progress + "%  ·  confiança mínima para abrir o Claude: " + need + "%");
    }

    private void onTestModeChanged(boolean on) {
        if (on && !WakeService.running) {
            swTest.setChecked(false);
            Toast.makeText(this, "Ligue a escuta primeiro.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (WakeService.testMode == on) return;
        WakeService.testMode = on;
        tvHeard.setText(on ? "Fale algo…" : "…");
        reloadIfRunning();
    }

    // ------------------------------------------------------------------ modo de voz e "fechar"

    private boolean isAccessibilityOn() {
        if (ClaudeAssistService.isConnected()) return true;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    private void openAccessibilitySettings() {
        if (isAccessibilityOn()) {
            Toast.makeText(this, "A Acessibilidade já está ligada.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Toque em Apps instalados › Ei Claude e ative.", Toast.LENGTH_LONG).show();
        if (!tryStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) openAppSettings();
    }

    private void openRestrictedSettingsHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Configuração restrita")
                .setMessage("Como o Ei Claude não veio da Play Store, o Android pede uma liberação antes da "
                        + "Acessibilidade.\n\nNa tela que vai abrir, toque nos três pontinhos (⋮) no canto "
                        + "de cima e em \"Permitir configurações restritas\". Depois volte e ligue a Acessibilidade.")
                .setPositiveButton("Abrir", (dialog, which) -> openAppSettings())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void saveClosePhrases() {
        List<String> variants = Phrase.variants(etClose.getText().toString());
        String value = variants.isEmpty() ? Prefs.DEFAULT_CLOSE_PHRASES : String.join(", ", variants);
        Prefs.setClosePhrases(this, value);
        etClose.setText(value);
        etClose.setSelection(value.length());
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(etClose.getWindowToken(), 0);
        etClose.clearFocus();
        Toast.makeText(this, "Frases de fechar salvas.", Toast.LENGTH_SHORT).show();
    }

    private void showClaudeButtons() {
        if (!isAccessibilityOn()) {
            Toast.makeText(this, "Ligue a Acessibilidade do Ei Claude primeiro.", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> labels = ClaudeAssistService.lastLabels;
        if (labels == null || labels.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Ajustar botões do Claude")
                    .setMessage("Abra o Claude na tela da conversa (ou no modo de voz), espere alguns segundos "
                            + "e volte aqui. O Ei Claude vai listar os botões que encontrar.")
                    .setPositiveButton("Abrir o Claude", (dialog, which) -> openClaudeOrStore())
                    .setNegativeButton("Fechar", null)
                    .show();
            return;
        }
        final String[] items = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Botões na última tela do Claude")
                .setItems(items, (dialog, which) -> chooseButtonRole(items[which]))
                .setPositiveButton("Copiar lista", (dialog, which) -> copyButtons(labels))
                .setNeutralButton("Voltar ao automático", (dialog, which) -> {
                    Prefs.setVoiceButton(this, "");
                    Prefs.setStopButton(this, "");
                    refreshAssistInfo();
                    Toast.makeText(this, "O app volta a descobrir os botões sozinho.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void chooseButtonRole(String label) {
        String[] roles = {"É o botão que liga o modo de voz", "É o botão que encerra o modo de voz"};
        new AlertDialog.Builder(this)
                .setTitle("\"" + label + "\"")
                .setItems(roles, (dialog, which) -> {
                    if (which == 0) {
                        Prefs.setVoiceButton(this, label);
                    } else {
                        Prefs.setStopButton(this, label);
                    }
                    refreshAssistInfo();
                    Toast.makeText(this, "Guardado.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void copyButtons(List<String> labels) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Botões do Claude", String.join("\n", labels)));
        Toast.makeText(this, "Lista copiada. É só colar na conversa com o Claude.", Toast.LENGTH_LONG).show();
    }

    private void refreshAssistInfo() {
        String voice = Prefs.getVoiceButton(this);
        String stop = Prefs.getStopButton(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Botão do modo de voz: ").append(voice.isEmpty() ? "descobrir sozinho" : "\"" + voice + "\"");
        sb.append("\nBotão de encerrar: ").append(stop.isEmpty() ? "descobrir sozinho" : "\"" + stop + "\"");
        String last = ClaudeAssistService.lastAction;
        if (last != null && !last.isEmpty()) sb.append("\nÚltima ação: ").append(last);
        tvAssist.setText(sb.toString());
    }

    // ------------------------------------------------------------------ avisos do serviço

    @Override
    public void onAssist(String text) {
        refreshAssistInfo();
    }

    @Override
    public void onStatus(String status) {
        tvStatus.setText(status);
        btnToggle.setText(WakeService.running ? "Desligar a escuta" : "Ligar a escuta");
    }

    @Override
    public void onHeard(String text) {
        tvHeard.setText(text);
    }

    @Override
    public void onPhraseInfo(String info) {
        if (info != null && !info.isEmpty()) tvPhraseInfo.setText(info);
    }
}
