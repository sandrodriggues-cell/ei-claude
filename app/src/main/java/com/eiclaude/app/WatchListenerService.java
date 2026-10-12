package com.eiclaude.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Recebe os pedidos do app do relógio. O Google Play Services liga este serviço quando chega
 * uma mensagem, mesmo com o Ei Claude fechado.
 */
public class WatchListenerService extends WearableListenerService {

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (event == null) return;
        final String path = event.getPath();
        if (!WatchBridge.PATH_OPEN.equals(path) && !WatchBridge.PATH_CLOSE.equals(path)) return;
        final String node = event.getSourceNodeId();
        final Context app = getApplicationContext();

        // O resto do app trabalha na thread principal; espera terminar para o serviço não ser
        // desligado no meio do caminho.
        final CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                WatchBridge.remember(node);
                if (WatchBridge.PATH_OPEN.equals(path)) {
                    WatchBridge.open(app);
                } else {
                    WatchBridge.close(app);
                }
            } catch (Throwable t) {
                WatchBridge.send(app, "erro", "Erro no celular: " + t.getClass().getSimpleName());
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
