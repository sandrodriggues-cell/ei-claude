package com.eiclaude.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Depois de reiniciar o celular ou atualizar o app, o Android não deixa ligar o microfone
 * sozinho em segundo plano. Então o app mostra um aviso: um toque e a escuta volta.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.isEnabled(context)) return;
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            WakeService.showAlert(context, "O celular reiniciou. Toque para religar a escuta do Ei Claude.");
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            WakeService.showAlert(context, "O app foi atualizado. Toque para religar a escuta do Ei Claude.");
        }
    }
}
