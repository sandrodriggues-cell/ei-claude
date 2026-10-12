package com.eiclaude.relogio;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * O botão redondo do centro da tela, desenhado à mão (sem imagens):
 * microfone parado, anel pulsando ao ouvir, arco girando ao pensar e ondas ao falar.
 */
final class MicButton extends View {

    enum Look { IDLE, LISTENING, THINKING, SPEAKING, DISABLED }

    private static final int ACCENT = 0xFFE07A52;
    private static final int ACCENT_DEEP = 0xFFC2410C;
    private static final int DARK = 0xFF2B2420;
    private static final int GRAY = 0xFF4A4440;
    private static final int WHITE = 0xFFFFFFFF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);

    private Look look = Look.IDLE;
    private float phase;

    MicButton(Context c) {
        super(c);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        animator.setDuration(1300);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        setClickable(true);
        setFocusable(false);
        updateDescription();
    }

    void setLook(Look l) {
        if (look == l) return;
        look = l;
        boolean animated = l == Look.LISTENING || l == Look.THINKING || l == Look.SPEAKING;
        if (animated && !animator.isStarted() && isAttachedToWindow()) animator.start();
        if (!animated) {
            animator.cancel();
            phase = 0f;
        }
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        switch (look) {
            case THINKING:
                setContentDescription("Chamando o Claude no celular.");
                break;
            case SPEAKING:
                setContentDescription("O Claude está ouvindo no celular. Toque para chamar de novo.");
                break;
            default:
                setContentDescription("Toque para chamar o Claude no celular.");
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (look == Look.LISTENING || look == Look.THINKING || look == Look.SPEAKING) animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * 0.36f;

        if (look == Look.LISTENING) {
            // Dois anéis que se expandem e somem.
            for (int i = 0; i < 2; i++) {
                float p = (phase + i * 0.5f) % 1f;
                ring.setColor(ACCENT);
                ring.setAlpha((int) (150 * (1f - p)));
                ring.setStrokeWidth(r * 0.06f);
                canvas.drawCircle(cx, cy, r * (1.02f + 0.36f * p), ring);
            }
        }

        int base;
        switch (look) {
            case THINKING:
                base = DARK;
                break;
            case SPEAKING:
                base = ACCENT_DEEP;
                break;
            case DISABLED:
                base = GRAY;
                break;
            default:
                base = ACCENT;
        }
        fill.setColor(base);
        canvas.drawCircle(cx, cy, r, fill);

        switch (look) {
            case THINKING:
                drawSpinner(canvas, cx, cy, r);
                break;
            case SPEAKING:
                drawWaves(canvas, cx, cy, r);
                break;
            default:
                drawMic(canvas, cx, cy, r);
        }
    }

    private void drawMic(Canvas canvas, float cx, float cy, float r) {
        fill.setColor(WHITE);
        float bw = r * 0.34f;
        rect.set(cx - bw / 2f, cy - r * 0.50f, cx + bw / 2f, cy + r * 0.12f);
        canvas.drawRoundRect(rect, bw / 2f, bw / 2f, fill);

        stroke.setColor(WHITE);
        stroke.setStrokeWidth(r * 0.09f);
        float ar = r * 0.33f;
        float acy = cy - r * 0.08f;
        rect.set(cx - ar, acy - ar, cx + ar, acy + ar);
        canvas.drawArc(rect, 10f, 160f, false, stroke);
        canvas.drawLine(cx, acy + ar, cx, cy + r * 0.44f, stroke);
        canvas.drawLine(cx - r * 0.17f, cy + r * 0.44f, cx + r * 0.17f, cy + r * 0.44f, stroke);
    }

    private void drawSpinner(Canvas canvas, float cx, float cy, float r) {
        stroke.setColor(ACCENT);
        stroke.setStrokeWidth(r * 0.10f);
        float sr = r * 0.52f;
        rect.set(cx - sr, cy - sr, cx + sr, cy + sr);
        canvas.drawArc(rect, phase * 360f - 90f, 100f, false, stroke);
        stroke.setAlpha(70);
        canvas.drawCircle(cx, cy, sr, stroke);
        stroke.setAlpha(255);
    }

    private void drawWaves(Canvas canvas, float cx, float cy, float r) {
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(r * 0.11f);
        float gap = r * 0.20f;
        for (int i = -2; i <= 2; i++) {
            double t = (phase * 2 * Math.PI) + i * 0.9;
            float base = (i == 0) ? 0.42f : (Math.abs(i) == 1 ? 0.32f : 0.20f);
            float amp = base * (0.65f + 0.35f * (float) Math.abs(Math.sin(t)));
            float x = cx + i * gap;
            canvas.drawLine(x, cy - r * amp, x, cy + r * amp, stroke);
        }
    }
}
