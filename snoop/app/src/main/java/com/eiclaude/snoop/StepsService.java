package com.eiclaude.snoop;

import android.os.SystemClock;

import androidx.health.services.client.PassiveListenerService;
import androidx.health.services.client.data.DataPointContainer;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.IntervalDataPoint;

import java.util.List;

/** Recebe do Health Services, em lotes, os passos do dia. */
public class StepsService extends PassiveListenerService {

    @Override
    public void onNewDataPointsReceived(DataPointContainer dataPoints) {
        try {
            List<IntervalDataPoint<Long>> list = dataPoints.getData(DataType.STEPS_DAILY);
            long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            for (IntervalDataPoint<Long> p : list) {
                long when = bootAt + p.getEndDurationFromBoot().toMillis();
                Long value = p.getValue();
                if (value != null) Moves.add(this, "hs", when, value);
            }
        } catch (Throwable ignored) {
            // lote inválido: espera o próximo
        }
    }
}
