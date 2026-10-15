package com.eiclaude.snoop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/** Três barrinhas: barriga, passeio e alegria do Snoop. */
public final class StatsView extends View {

    private static final String[] LABELS = {"Barriga", "Passeio", "Alegria"};
    private static final int[] COLORS = {0xFFE9A862, 0xFF5EC8D8, 0xFFFF6B8B};
    private static final int TRACK = 0xFF2E2824;

    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] values = {70, 0, 70};

    public StatsView(Context c) {
        super(c);
        label.setColor(Ui.MUTED);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
        setContentDescription("Barriga, passeio e alegria do Snoop");
    }

    void setValues(int belly, int walk, int joy) {
        values[0] = belly;
        values[1] = walk;
        values[2] = joy;
        setContentDescription("Barriga " + belly + "%, passeio " + walk + "%, alegria " + joy + "%");
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, Ui.dp(getContext(), 26));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float gap = Ui.dp(getContext(), 8);
        float cell = (w - gap * 2) / 3f;
        float barH = Ui.dp(getContext(), 5);
        float textY = label.getTextSize();
        float barY = textY + Ui.dp(getContext(), 6);
        for (int i = 0; i < 3; i++) {
            float x = i * (cell + gap);
            c.drawText(LABELS[i], x + cell / 2f, textY, label);
            bar.setColor(TRACK);
            rect.set(x, barY, x + cell, barY + barH);
            c.drawRoundRect(rect, barH / 2, barH / 2, bar);
            float v = Math.max(0, Math.min(100, values[i])) / 100f;
            if (v > 0) {
                bar.setColor(values[i] < 25 ? Ui.WARN : COLORS[i]);
                rect.set(x, barY, x + Math.max(barH, cell * v), barY + barH);
                c.drawRoundRect(rect, barH / 2, barH / 2, bar);
            }
        }
    }
}
