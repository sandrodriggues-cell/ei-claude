package com.eiclaude.ritmo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Recebe a hora do alarme e os botões Adiar e Dispensar da notificação. */
public class EventAlarmReceiver extends BroadcastReceiver {

    static final String ACTION_FIRE = "com.eiclaude.ritmo.ALARME";
    static final String ACTION_SNOOZE = "com.eiclaude.ritmo.ADIAR";
    static final String ACTION_DISMISS = "com.eiclaude.ritmo.DISPENSAR";
    static final String ACTION_SILENCE = "com.eiclaude.ritmo.SILENCIAR";
    static final String EXTRA_KEY = "key";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        String key = intent.getStringExtra(EXTRA_KEY);
        if (ACTION_FIRE.equals(action)) EventAlarms.fireDue(c);
        else if (ACTION_SNOOZE.equals(action)) EventAlarms.snooze(c, key, 5);
        else if (ACTION_DISMISS.equals(action)) EventAlarms.dismiss(c, key);
        else if (ACTION_SILENCE.equals(action)) EventAlarms.silence(c, key);
    }
}
