package com.eiclaude.relogio;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.speech.RecognizerIntent;

import java.util.ArrayList;

/** Tela de voz do sistema (a mesma usada para ditar mensagens), aberta por cima do app. */
final class IntentVoice implements VoiceInput {

    static final int REQUEST = 4101;

    private final Activity activity;
    private Callback callback;

    IntentVoice(Activity activity) {
        this.activity = activity;
    }

    static boolean available(Context c) {
        try {
            return intent(c).resolveActivity(c.getPackageManager()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Intent intent(Context c) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com o Claude");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return i;
    }

    @Override
    public String id() {
        return "tela";
    }

    @Override
    public String label() {
        return "tela de voz do sistema";
    }

    /** Está esperando a tela de voz devolver o resultado? */
    boolean isWaiting() {
        return callback != null;
    }

    @Override
    public void start(Callback cb) {
        callback = cb;
        try {
            activity.startActivityForResult(intent(activity), REQUEST);
            cb.onReady();
        } catch (ActivityNotFoundException e) {
            callback = null;
            cb.onUnavailable("sem tela de voz");
        } catch (Throwable t) {
            callback = null;
            cb.onUnavailable(String.valueOf(t.getMessage()));
        }
    }

    /** Repasse de {@code onActivityResult}. Devolve true se o resultado era daqui. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST) return false;
        Callback c = callback;
        callback = null;
        if (c == null) return true;
        String text = "";
        if (resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (list != null && !list.isEmpty() && list.get(0) != null) text = list.get(0).trim();
        }
        if (text.isEmpty()) c.onNoSpeech();
        else c.onText(text);
        return true;
    }

    @Override
    public void cancel() {
        if (callback == null) return;
        callback = null;
        try {
            activity.finishActivity(REQUEST);
        } catch (Throwable ignored) {
            // a tela de voz já fechou
        }
    }

    @Override
    public void release() {
        cancel();
    }
}
