package com.eiclaude.ritmo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Depois de ligar o celular, atualizar o app ou mudar a hora: agenda de novo o próximo alarme. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a) || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            EventAlarms.scheduleNext(c);
        }
    }
}
