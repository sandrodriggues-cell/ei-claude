package com.eiclaude.snoop;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import androidx.health.services.client.HealthServices;
import androidx.health.services.client.PassiveMonitoringClient;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.PassiveListenerConfig;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Passos: pelo Health Services (em segundo plano) e, de reserva, pelo contador do relógio. */
final class Health {

    private Health() {
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Pede ao Health Services para entregar os passos do dia ao {@link StepsService}. */
    static void register(Context c) {
        Context app = c.getApplicationContext();
        if (!hasPermission(app)) {
            Prefs.putString(app, "passos_status", "falta permitir Atividade física");
            return;
        }
        try {
            Set<DataType<?, ?>> types = new HashSet<>();
            types.add(DataType.STEPS_DAILY);
            PassiveListenerConfig config = PassiveListenerConfig.builder().setDataTypes(types).build();
            PassiveMonitoringClient client = HealthServices.getClient(app).getPassiveMonitoringClient();
            ListenableFuture<Void> f = client.setPassiveListenerServiceAsync(StepsService.class, config);
            Futures.addCallback(f, new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    Prefs.putString(app, "passos_status", "ligado");
                    Prefs.putInt(app, "passos_boot", bootCount(app));
                }

                @Override
                public void onFailure(Throwable t) {
                    Prefs.putString(app, "passos_status", "erro: " + t.getMessage());
                }
            }, MoreExecutors.directExecutor());
        } catch (Throwable t) {
            Prefs.putString(app, "passos_status", "erro: " + t.getMessage());
        }
    }

    /** O registro no Health Services não sobrevive a um reinício do relógio. */
    static void registerIfNeeded(Context c) {
        if (!hasPermission(c)) return;
        if (Prefs.getInt(c, "passos_boot", -1) != bootCount(c) || !"ligado".equals(Prefs.getString(c, "passos_status"))) {
            register(c);
        }
    }

    static int bootCount(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Throwable t) {
            return -2;
        }
    }

    /**
     * Lê o contador de passos do relógio na hora (reserva). Em segundo plano o Android pode não
     * entregar nada; aí devolve -1.
     */
    static long readStepCounter(Context c, long timeoutMs) {
        if (!hasPermission(c)) return -1;
        SensorManager sm = c.getSystemService(SensorManager.class);
        if (sm == null) return -1;
        Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (sensor == null) return -1;
        final long[] value = {-1};
        final CountDownLatch latch = new CountDownLatch(1);
        HandlerThread thread = new HandlerThread("Snoop-passos");
        thread.start();
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.values != null && event.values.length > 0) {
                    value[0] = (long) event.values[0];
                    latch.countDown();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor s, int accuracy) {
                // nada
            }
        };
        try {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, new Handler(thread.getLooper()));
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // sem leitura
        } finally {
            try {
                sm.unregisterListener(listener);
            } catch (Throwable ignored) {
                // já removido
            }
            thread.quitSafely();
        }
        return value[0];
    }
}
