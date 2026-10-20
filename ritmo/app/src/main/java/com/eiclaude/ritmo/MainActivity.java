package com.eiclaude.ritmo;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.os.Vibrator;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Feito: a tela inteira é a página assets/index.html (a mesma do Claude).
 * O Android só cuida do que a página não consegue sozinha: alarme do timer
 * com a tela apagada, vibração, tela acesa, cores das barras e backups.
 */
public class MainActivity extends Activity {

    static volatile boolean visible = false;

    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_OPEN = 11;
    private static final int REQ_SAVE = 12;
    private static final int REQ_NOTIF = 13;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSave;
    private boolean askedNotifications = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TimerReceiver.ensureChannel(this);
        EventAlarms.ensureChannels(this);
        EventAlarms.scheduleNext(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "RitmoAndroid");
        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.ritmoResume&&window.ritmoResume()", null);
        }
        TimerReceiver.clear(this);
    }

    @Override
    protected void onPause() {
        visible = false;
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Voltar: fecha a folha aberta ou volta para "Hoje"; só então sai do app. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript("(window.ritmoBack&&window.ritmoBack())?'1':'0'", value -> {
            if (value == null || !value.contains("1")) finish();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            if (fileCallback != null) fileCallback.onReceiveValue(result);
            fileCallback = null;
        } else if (requestCode == REQ_SAVE) {
            String content = pendingSave;
            pendingSave = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || content == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "w")) {
                if (out == null) throw new IllegalStateException("sem saída");
                out.write(content.getBytes(StandardCharsets.UTF_8));
                toast("Backup salvo");
            } catch (Exception e) {
                toast("Não foi possível salvar o backup.");
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            v.vibrate(VibrationEffect.createOneShot(Math.max(5, Math.min(ms, 2000)), VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT < 33 || askedNotifications) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        askedNotifications = true;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @SuppressWarnings("deprecation")
    private void applyBars(String status, String nav, boolean light) {
        try {
            Window w = getWindow();
            w.setStatusBarColor(Color.parseColor(status));
            w.setNavigationBarColor(Color.parseColor(nav));
            View d = w.getDecorView();
            int flags = d.getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(light ? (flags | mask) : (flags & ~mask));
        } catch (Exception ignored) {
        }
    }

    /** Abre a tela do Android onde se libera cada permissão do alarme. */
    private void openSetting(String which) {
        String pkg = getPackageName();
        Uri pkgUri = Uri.parse("package:" + pkg);
        Intent i = null;
        if ("notif".equals(which)) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !askedNotifications) {
                askNotifications();
                return;
            }
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        } else if ("fullscreen".equals(which) && Build.VERSION.SDK_INT >= 34) {
            i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri);
        } else if ("exact".equals(which) && Build.VERSION.SDK_INT >= 31) {
            i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri);
        } else if ("battery".equals(which)) {
            i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri);
        }
        if (i == null) i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri);
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri));
            } catch (Exception ignored) {
                toast("Abra os ajustes do Android › Apps › Feito.");
            }
        }
    }

    private void startSave(String name, String content) {
        pendingSave = content;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_SAVE);
        } catch (ActivityNotFoundException e) {
            pendingSave = null;
            toast("Não há onde salvar o arquivo neste aparelho.");
        }
    }

    /** Funções chamadas pela página como window.RitmoAndroid.*. */
    private class Bridge {
        @JavascriptInterface
        public void scheduleAlarm(double at, String title, String text) {
            runOnUiThread(() -> TimerReceiver.schedule(MainActivity.this, (long) at, title, text));
        }

        @JavascriptInterface
        public void cancelAlarm() {
            runOnUiThread(() -> TimerReceiver.cancel(MainActivity.this));
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            buzz(ms);
        }

        @JavascriptInterface
        public void requestNotifications() {
            runOnUiThread(MainActivity.this::askNotifications);
        }

        @JavascriptInterface
        public void setBars(String status, String nav, boolean light) {
            runOnUiThread(() -> applyBars(status, nav, light));
        }

        @JavascriptInterface
        public void setEventAlarms(String json) {
            EventAlarms.setAll(MainActivity.this, json);
        }

        @JavascriptInterface
        public String alarmStatus() {
            return EventAlarms.status(MainActivity.this);
        }

        @JavascriptInterface
        public void openAlarmSetting(String which) {
            runOnUiThread(() -> openSetting(which));
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void testAlarm() {
            EventAlarms.test(MainActivity.this);
        }

        @JavascriptInterface
        public void saveFile(String name, String content) {
            runOnUiThread(() -> startSave(name, content));
        }
    }
}
