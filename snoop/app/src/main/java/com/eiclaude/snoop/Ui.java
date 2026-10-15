package com.eiclaude.snoop;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Utilidades de tela para o relógio: telas redondas, coroa/bezel giratório, textos e vibração. */
final class Ui {

    static final int INK = 0xFFF5F1EC;
    static final int MUTED = 0xFFA79F96;
    static final int SOFT = 0xFFCFC7BE;
    static final int ACCENT = 0xFFE9A862;
    static final int OK = 0xFF4ADE80;
    static final int WARN = 0xFFFBBF24;
    static final int CHIP = 0xFF26211E;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static int screenWidth(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return m.widthPixels;
    }

    static int screenHeight(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return m.heightPixels;
    }

    static boolean isRound(Context c) {
        Configuration cfg = c.getResources().getConfiguration();
        return cfg.isScreenRound();
    }

    /** Coluna central com margens que cabem numa tela redonda. */
    static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int w = screenWidth(c);
        int h = screenHeight(c);
        int side = Math.round(w * (isRound(c) ? 0.13f : 0.06f));
        col.setPadding(side, Math.round(h * 0.11f), side, Math.round(h * 0.28f));
        return col;
    }

    /** Rolagem que também responde ao bezel/coroa do relógio. */
    static ScrollView scroller(Context c) {
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(true);
        sv.setFillViewport(true);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(true);
        // A rolagem fica com o foco (e com o bezel), sem passar para campos de texto.
        sv.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        sv.setOnGenericMotionListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_SCROLL
                    && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                        * ViewConfiguration.get(v.getContext()).getScaledVerticalScrollFactor();
                v.scrollBy(0, Math.round(delta));
                return true;
            }
            return false;
        });
        return sv;
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setLineSpacing(0f, 1.12f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Botão em forma de pílula, largo, fácil de tocar no relógio. */
    static TextView chip(Context c, String label, View.OnClickListener onClick) {
        TextView t = text(c, 14, INK, false);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 44));
        int pad = dp(c, 12);
        t.setPadding(pad, dp(c, 8), pad, dp(c, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CHIP);
        bg.setCornerRadius(dp(c, 22));
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        t.setLayoutParams(lp);
        return t;
    }

    static LinearLayout.LayoutParams wide(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    static void buzz(Context c, long ms) {
        try {
            Vibrator v = c.getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
            // sem vibração
        }
    }
}
