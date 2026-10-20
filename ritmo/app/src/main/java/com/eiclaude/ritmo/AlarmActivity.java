package com.eiclaude.ritmo;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tela cheia do alarme, por cima da tela de bloqueio, como uma ligação. */
public class AlarmActivity extends Activity {

    static final String EXTRA_KEY = "key";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_SUB = "sub";
    static final String EXTRA_START = "start";
    static final String EXTRA_LEAD = "lead";
    static final String EXTRA_HEAD = "head";

    private static final int BG = 0xFF0D1220;
    private static final int LINE = 0xFF2F3B55;
    private static final int FG = 0xFFECEFF6;
    private static final int MUTED = 0xFF9CA6BA;
    private static final int GOLD = 0xFFF5B83D;
    private static final int GOLD_INK = 0xFF1D1405;

    private static WeakReference<AlarmActivity> current = new WeakReference<>(null);

    static void closeIfShowing(String key) {
        AlarmActivity a = current.get();
        if (a != null && (key == null || key.equals(a.key))) a.runOnUiThread(a::finish);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String key;
    private PulseView pulse;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        current = new WeakReference<>(this);
        build(getIntent());
        // Sem resposta em 2 minutos: a tela fecha (o aviso quieto fica na barra).
        handler.postDelayed(this::finish, 2 * 60 * 1000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (pulse != null) pulse.stop();
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }

    /** Voltar não some com o alarme: é preciso escolher Adiar ou Dispensar. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private TextView button(String label, boolean primary) {
        TextView b = text(label, 17, primary ? GOLD_INK : FG, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        if (primary) bg.setColor(GOLD);
        else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(1.5f), LINE);
        }
        b.setBackground(bg);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private void build(Intent in) {
        key = in.getStringExtra(EXTRA_KEY);
        String title = in.getStringExtra(EXTRA_TITLE);
        String sub = in.getStringExtra(EXTRA_SUB);
        String start = in.getStringExtra(EXTRA_START);
        int lead = in.getIntExtra(EXTRA_LEAD, 0);
        String head = in.getStringExtra(EXTRA_HEAD);
        if (TextUtils.isEmpty(start)) start = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());

        if (pulse != null) pulse.stop();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(28), dp(36), dp(28), dp(28));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView brand = text(TextUtils.isEmpty(head) ? "Evento" : head, 14, GOLD, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setLetterSpacing(0.04f);
        col.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        View top = new View(this);
        col.addView(top, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout dial = new FrameLayout(this);
        pulse = new PulseView(this);
        dial.addView(pulse, new FrameLayout.LayoutParams(-1, -1));
        TextView time = text(start, 46, FG, Typeface.create("monospace", Typeface.NORMAL));
        dial.addView(time, new FrameLayout.LayoutParams(-1, -1));
        col.addView(dial, new LinearLayout.LayoutParams(dp(230), dp(230)));

        TextView when = text(!TextUtils.isEmpty(head) ? "Está na hora" : lead > 0 ? "Começa em " + lead + " min" : "Começando agora", 13, GOLD,
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        when.setLetterSpacing(0.12f);
        when.setAllCaps(true);
        LinearLayout.LayoutParams lpWhen = new LinearLayout.LayoutParams(-1, -2);
        lpWhen.topMargin = dp(26);
        col.addView(when, lpWhen);

        TextView name = text(TextUtils.isEmpty(title) ? "Evento" : title, 30, FG, Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lpName = new LinearLayout.LayoutParams(-1, -2);
        lpName.topMargin = dp(10);
        col.addView(name, lpName);

        if (!TextUtils.isEmpty(sub)) {
            TextView s = text(sub, 15, MUTED, null);
            LinearLayout.LayoutParams lpSub = new LinearLayout.LayoutParams(-1, -2);
            lpSub.topMargin = dp(8);
            col.addView(s, lpSub);
        }

        View bottom = new View(this);
        col.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1.2f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView snooze = button("Adiar 5 min", false);
        TextView dismiss = button("Dispensar", true);
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(0, dp(60), 1f);
        lpA.rightMargin = dp(12);
        row.addView(snooze, lpA);
        row.addView(dismiss, new LinearLayout.LayoutParams(0, dp(60), 1f));
        col.addView(row, new LinearLayout.LayoutParams(-1, -2));

        TextView open = text("Abrir o Feito", 14, MUTED, null);
        open.setPadding(0, dp(16), 0, dp(4));
        open.setClickable(true);
        col.addView(open, new LinearLayout.LayoutParams(-1, -2));

        final String k = key;
        snooze.setOnClickListener(v -> {
            EventAlarms.snooze(this, k, 5);
            finish();
        });
        dismiss.setOnClickListener(v -> {
            EventAlarms.dismiss(this, k);
            finish();
        });
        open.setOnClickListener(v -> {
            EventAlarms.dismiss(this, k);
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });

        setContentView(root);
        pulse.start();
    }

    /** Anéis âmbar pulsando em volta da hora, como numa chamada. */
    static class PulseView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;
        private float phase = 0f;

        PulseView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2f * d);
            ring.setColor(GOLD);
            disc.setStyle(Paint.Style.FILL);
            disc.setColor(Color.argb(38, 245, 184, 61));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2.5f * d);
            edge.setColor(GOLD);
        }

        void start() {
            stop();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(2200);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) anim.cancel();
            anim = null;
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float max = Math.min(cx, cy) - ring.getStrokeWidth();
            float base = max * 0.62f;
            for (int i = 0; i < 2; i++) {
                float p = (phase + i * 0.5f) % 1f;
                float r = base + (max - base) * p;
                ring.setAlpha(Math.round(200 * (1f - p)));
                c.drawCircle(cx, cy, r, ring);
            }
            c.drawCircle(cx, cy, base, disc);
            c.drawCircle(cx, cy, base, edge);
        }
    }
}
