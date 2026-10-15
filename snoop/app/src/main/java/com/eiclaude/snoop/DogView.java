package com.eiclaude.snoop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Random;

/**
 * O Snoop desenhado à mão (vira-lata caramelo de coleira azul), com piscadas, orelhas que se
 * mexem, boca que acompanha a fala e expressões para cada humor.
 */
public final class DogView extends View {

    /** Você fez carinho (deslizou o dedo na cabeça dele). */
    interface PetListener {
        void onPet();
    }

    enum Mode { IDLE, LISTENING, THINKING, SPEAKING, SLEEPING }

    enum Mood {
        FELIZ, ANIMADO, CURIOSO, CALMO, TRISTE, PREOCUPADO, SONOLENTO;

        static Mood of(String s) {
            switch (Text.fold(s)) {
                case "animado":
                    return ANIMADO;
                case "curioso":
                    return CURIOSO;
                case "calmo":
                    return CALMO;
                case "triste":
                    return TRISTE;
                case "preocupado":
                    return PREOCUPADO;
                case "sonolento":
                    return SONOLENTO;
                default:
                    return FELIZ;
            }
        }
    }

    private static final int FUR = 0xFFD88C43;
    private static final int FUR_LIGHT = 0xFFE9A862;
    private static final int FUR_DARK = 0xFFB96A2B;
    private static final int EAR = 0xFFA95C24;
    private static final int EAR_FOLD = 0xFF874519;
    private static final int CREAM = 0xFFF7E0BC;
    private static final int INK = 0xFF2A1C14;
    private static final int MOUTH = 0xFF5A2620;
    private static final int TONGUE = 0xFFEE7C8B;
    private static final int TONGUE_LINE = 0xFFD45E6F;
    private static final int COLLAR = 0xFF2F80ED;
    private static final int TAG = 0xFFF2C14E;
    private static final int TAG_EDGE = 0xFFC99A2E;
    private static final int BLUSH = 0x73F49A8C;
    private static final int RING = 0xFF5EC8D8;
    private static final int HEART = 0xFFFF6B8B;
    private static final int BONE = 0xFFF5EBDD;
    private static final int BONE_EDGE = 0xFFC9B79F;

    /** Truques que ele sabe fazer (o Gemini escolhe pelo nome). */
    static final String[] TRICKS = {"sentar", "pata", "rolar", "latir", "pular", "abanar"};

    // Mesmos traços da prévia e dos ícones (coordenadas com a cabeça no centro).
    private static final Path HEAD = SvgPath.parse("M 0 -50 C 27 -50 51 -40 54 -12 C 57 12 48 34 27 42 "
            + "C 14 47 -14 47 -27 42 C -48 34 -57 12 -54 -12 C -51 -40 -27 -50 0 -50 Z");
    private static final Path BODY = SvgPath.parse("M -28 28 C -40 50 -50 72 -56 130 L 56 130 C 50 72 40 50 28 28 Z");
    private static final Path CHEST = SvgPath.parse("M -15 50 C -13 70 -18 90 -26 130 L 26 130 C 18 90 13 70 15 50 Z");
    private static final Path COLLAR_P = SvgPath.parse("M -33 39 C -14 50 14 50 33 39 L 35 48 C 14 60 -14 60 -35 48 Z");
    private static final Path EAR_L = SvgPath.parse("M 12 -2 C 0 -13 -20 -12 -29 4 C -38 22 -37 50 -27 61 "
            + "C -19 69 -8 63 -4 48 C 0 33 9 15 12 -2 Z");
    private static final Path EAR_R = SvgPath.parse("M -16 6 C -12 -14 -1 -33 15 -44 C 26 -32 28 -10 21 9 Z");
    private static final Path EAR_R_FOLD = SvgPath.parse("M 15 -44 C 21 -38 25 -31 27 -26 C 30 -18 31 -12 30 -6 "
            + "C 23 -12 14 -22 6 -32 C 9 -37 12 -41 15 -44 Z");
    private static final Path BLAZE = SvgPath.parse("M -5 -35 C -3 -41 3 -41 5 -35 L 11 6 L -11 6 Z");
    private static final Path NOSE = SvgPath.parse("M -9 7 C -9 1 9 1 9 7 C 9 12 3 15 0 15 C -3 15 -9 12 -9 7 Z");
    private static final Path SMILE = SvgPath.parse("M -12 23 Q -6 28 0 21 Q 6 28 12 23");
    private static final Path FROWN = SvgPath.parse("M -10 28 Q 0 21 10 28");
    private static final Path HEART_P = SvgPath.parse("M 0 4 C -7 -1 -5 -8 0 -4 C 5 -8 7 -1 0 4 Z");
    private static final Path PAW = SvgPath.parse("M -9 0 L 9 0 L 9 44 L -9 44 Z");

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path work = new Path();
    private final Path clip = new Path();
    private final Random random = new Random();

    private Mode mode = Mode.IDLE;
    private Mood mood = Mood.FELIZ;
    private boolean levelDriven;
    private float levelTarget;
    private float level;
    private float mouth;
    private float earL;
    private float earR;
    private float tilt;
    private float lookX;
    private float lookY;

    private final long t0 = SystemClock.uptimeMillis();
    private long lastFrame = t0;
    private long nextBlinkAt = t0 + 1800;
    private long blinkStart = -1;
    private long nextWiggleAt = t0 + 2500;
    private long wiggleStart = -1;
    private long pokeAt = -10000;
    private boolean visible;
    private int ink = 0xFFF5F1EC;

    private PetListener petListener;
    private String trick;
    private long trickAt;
    private long trickDur;
    private long petUntil;
    private long lastPetAt;
    private long chewAt = -10000;
    private final long[] heartAt = new long[10];
    private final float[] heartX = new float[10];
    private int heartNext;
    private float downX;
    private float downY;
    private float travel;
    private boolean petting;
    private final float slop;

    public DogView(Context c) {
        super(c);
        headPaint.setStyle(Paint.Style.FILL);
        headPaint.setShader(new RadialGradient(-14, -30, 80,
                new int[]{FUR_LIGHT, FUR, FUR_DARK}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(INK);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(RING);
        zText.setColor(0xFFF5F1EC);
        zText.setTypeface(Typeface.DEFAULT_BOLD);
        zText.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Snoop, seu cachorro virtual. Toque para conversar, deslize o dedo para fazer carinho.");
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        for (int i = 0; i < heartAt.length; i++) heartAt[i] = -10000;
    }

    /** Cor dos textos e pontinhos desenhados em volta dele (muda com o fundo). */
    void setInk(int color) {
        ink = color;
        zText.setColor(color);
        invalidate();
    }

    void setPetListener(PetListener l) {
        petListener = l;
    }

    /** Faz um truque pelo nome (sentar, pata, rolar, latir, pular, abanar). */
    boolean playTrick(String name) {
        String n = Text.fold(name);
        long dur;
        switch (n) {
            case "sentar":
                dur = 1600;
                break;
            case "pata":
                dur = 1900;
                break;
            case "rolar":
                dur = 1100;
                break;
            case "latir":
                dur = 1000;
                break;
            case "pular":
                dur = 1200;
                break;
            case "abanar":
                dur = 1600;
                break;
            default:
                return false;
        }
        trick = n;
        trickAt = SystemClock.uptimeMillis();
        trickDur = dur;
        if (n.equals("abanar")) burstHearts(3);
        invalidate();
        return true;
    }

    /** Ganhou um petisco: aparece o ossinho e ele mastiga, todo feliz. */
    void giveTreat() {
        chewAt = SystemClock.uptimeMillis();
        burstHearts(3);
        invalidate();
    }

    private void burstHearts(int n) {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < n; i++) {
            int k = heartNext++ % heartAt.length;
            heartAt[k] = now + i * 180L;
            heartX[k] = -30 + random.nextFloat() * 60;
        }
    }

    private boolean happyNow(long now) {
        return now < petUntil || now - chewAt < 1700
                || ("abanar".equals(trick) || "pata".equals(trick) || "sentar".equals(trick));
    }

    // ------------------------------------------------------------------ toque: clique ou carinho

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                travel = 0;
                petting = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                travel += (float) Math.hypot(e.getX() - downX, e.getY() - downY);
                downX = e.getX();
                downY = e.getY();
                if (travel > slop * 3) {
                    petting = true;
                    long now = SystemClock.uptimeMillis();
                    petUntil = now + 1500;
                    if (now - lastPetAt > 350) {
                        lastPetAt = now;
                        burstHearts(1);
                        if (petListener != null) petListener.onPet();
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (!petting) performClick();
                petting = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                petting = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // ------------------------------------------------------------------ controle

    void setMode(Mode m) {
        if (m == null || m == mode) return;
        mode = m;
        if (m != Mode.SPEAKING) levelDriven = false;
        invalidate();
    }

    Mode mode() {
        return mode;
    }

    void setMood(Mood m) {
        if (m != null) mood = m;
        invalidate();
    }

    /** Volume da sua voz (ouvindo) ou da voz dele (falando com a voz do Gemini). */
    void setLevel(float l) {
        levelTarget = Math.max(0f, Math.min(1f, l));
    }

    /** Falando com a voz do Gemini: a boca segue o volume real. Com a do relógio, mexe sozinha. */
    void setLevelDriven(boolean on) {
        levelDriven = on;
        if (!on) levelTarget = 0f;
    }

    /** Pulinho quando você toca nele. */
    void poke() {
        pokeAt = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        visible = isVisible;
        if (isVisible) {
            lastFrame = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int size = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ? w : Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    // ------------------------------------------------------------------ animação

    private static float approach(float x, float target, float dt, float speed) {
        return x + (target - x) * Math.min(1f, dt * speed);
    }

    private void update(long now) {
        float t = (now - t0) / 1000f;
        float dt = Math.min(0.1f, Math.max(0f, (now - lastFrame) / 1000f));
        lastFrame = now;
        level = approach(level, levelTarget, dt, 14f);

        float earLT = 0;
        float earRT = 0;
        float tiltT = 0;
        float lookXT = 0;
        float lookYT = 0;
        switch (mode) {
            case LISTENING:
                earLT = 22 + 10 * level;
                earRT = -8 - 6 * level;
                lookYT = -0.5f;
                break;
            case THINKING:
                tiltT = -7 + 2 * (float) Math.sin(t * 2.2);
                lookXT = 2.2f;
                lookYT = -2.2f;
                earLT = 8;
                break;
            case SLEEPING:
                earLT = -6;
                earRT = 10;
                break;
            case SPEAKING:
                earLT = 4 + 6 * mouth;
                earRT = -2 * mouth;
                break;
            default:
                lookXT = 1.6f * (float) Math.sin(t * 0.45);
                lookYT = 0.8f * (float) Math.sin(t * 0.31);
        }
        if (mode == Mode.IDLE || mode == Mode.SPEAKING) {
            switch (mood) {
                case TRISTE:
                    earLT -= 14;
                    earRT += 16;
                    break;
                case PREOCUPADO:
                    earLT -= 6;
                    earRT += 6;
                    break;
                case CURIOSO:
                    tiltT -= 8;
                    earRT -= 6;
                    break;
                case SONOLENTO:
                    earLT -= 6;
                    earRT += 8;
                    break;
                case ANIMADO:
                    earLT += 8;
                    break;
                default:
                    break;
            }
        }
        if (mode != Mode.SLEEPING && now >= nextWiggleAt) {
            wiggleStart = now;
            nextWiggleAt = now + 3500 + random.nextInt(4000);
        }
        if (wiggleStart > 0 && now - wiggleStart < 450) {
            earRT += 9f * (float) Math.sin((now - wiggleStart) / 450.0 * 3 * Math.PI);
        }
        float tp = trickProgress(now);
        if (trick != null) {
            switch (trick) {
                case "latir":
                    earLT = 20;
                    earRT = -10;
                    break;
                case "abanar":
                    tiltT += 10f * (float) Math.sin(tp * 6 * Math.PI) * (1 - tp);
                    break;
                case "pata":
                    tiltT -= 6;
                    break;
                default:
                    break;
            }
        }
        if (now < petUntil) {
            earLT -= 10; // orelhas para trás, curtindo o carinho
            earRT += 8;
        }
        earL = approach(earL, earLT, dt, 8f);
        earR = approach(earR, earRT, dt, 10f);
        tilt = approach(tilt, tiltT, dt, 5f);
        lookX = approach(lookX, lookXT, dt, 4f);
        lookY = approach(lookY, lookYT, dt, 4f);

        float mouthT = 0;
        if (mode == Mode.SPEAKING) {
            mouthT = levelDriven
                    ? Math.min(1f, level * 1.7f)
                    : 0.15f + 0.65f * Math.abs((float) (Math.sin(t * 7.3) * Math.sin(t * 2.9 + 1)));
        }
        if ("latir".equals(trick)) mouthT = Math.max(0f, (float) Math.sin(tp * 4 * Math.PI));
        if (now - chewAt < 1600) mouthT = 0.15f + 0.45f * Math.abs((float) Math.sin(t * 14));
        mouth = approach(mouth, mouthT, dt, 18f);

        if (now >= nextBlinkAt) {
            blinkStart = now;
            nextBlinkAt = now + 2500 + random.nextInt(3500);
        }
    }

    // ------------------------------------------------------------------ desenho

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        update(now);
        float t = (now - t0) / 1000f;
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h) / 240f;

        c.save();
        c.translate(w / 2f, h / 2f);
        c.scale(s, s);

        if (mode == Mode.LISTENING) {
            ring.setStrokeWidth(3f);
            ring.setAlpha(Math.round(90 + 150 * level));
            c.drawCircle(0, -4, 86 + 8 * level + 2 * (float) Math.sin(t * 4), ring);
        }

        float hop = 0;
        long sincePoke = now - pokeAt;
        if (sincePoke >= 0 && sincePoke < 380) hop -= 8f * (float) Math.sin(Math.PI * sincePoke / 380.0);
        if (mood == Mood.ANIMADO && mode == Mode.IDLE) hop -= 3f * Math.max(0f, (float) Math.sin(t * 5));
        float tp = trickProgress(now);
        if ("pular".equals(trick)) hop -= 20f * Math.abs((float) Math.sin(2 * Math.PI * tp));
        if ("sentar".equals(trick)) hop += 12f * (tp < 0.25f ? tp / 0.25f : tp > 0.8f ? (1 - tp) / 0.2f : 1f);
        float breathe = 0.9f * (float) Math.sin(t * 2 * Math.PI / 3.4);
        boolean happy = happyNow(now);

        c.translate(0, -6 + hop);
        if ("rolar".equals(trick)) {
            float e = tp < 0.5f ? 2 * tp * tp : 1 - (float) Math.pow(-2 * tp + 2, 2) / 2;
            c.rotate(360f * e, 0, 30);
        }
        fill(c, BODY, FUR);
        fill(c, CHEST, CREAM);
        fill(c, COLLAR_P, COLLAR);
        fill.setColor(TAG);
        c.drawCircle(0, 60, 7, fill);
        stroke.setColor(TAG_EDGE);
        stroke.setStrokeWidth(1.2f);
        c.drawCircle(0, 60, 7, stroke);
        stroke.setColor(INK);

        c.save();
        c.translate(0, breathe);
        c.rotate(tilt, 0, 40);

        c.save();
        c.translate(-36, -34);
        c.rotate(earL);
        fill(c, EAR_L, EAR);
        c.restore();

        c.save();
        c.translate(32, -38);
        c.rotate(earR);
        fill(c, EAR_R, EAR);
        fill(c, EAR_R_FOLD, EAR_FOLD);
        c.restore();

        c.drawPath(HEAD, headPaint);
        fill(c, BLAZE, CREAM);
        fill.setColor(CREAM);
        oval.set(-29, 3, 29, 43);
        c.drawOval(oval, fill);

        drawBrows(c);
        drawEyes(c, now, happy);
        fill(c, NOSE, INK);
        fill.setColor(0x59FFFFFF);
        oval.set(-6, 3.4f, 0, 6.6f);
        c.drawOval(oval, fill);
        drawMouth(c, t, happy);
        if (now - chewAt < 1600) drawBone(c, now);
        if ((mood == Mood.FELIZ || mood == Mood.ANIMADO || happy) && mode != Mode.SLEEPING) {
            fill.setColor(BLUSH);
            c.drawCircle(-35, 14, 6, fill);
            c.drawCircle(35, 14, 6, fill);
        }
        c.restore();

        if ("pata".equals(trick)) drawPaw(c, tp);
        if ("latir".equals(trick)) drawBark(c, tp);
        drawHearts(c, now);
        if (mode == Mode.THINKING) drawThinking(c, t);
        if (mode == Mode.SLEEPING) drawZzz(c, t);
        c.restore();

        if (visible) postInvalidateDelayed(33);
    }

    private void fill(Canvas c, Path p, int color) {
        fill.setColor(color);
        c.drawPath(p, fill);
    }

    private void drawBrows(Canvas c) {
        fill.setColor(CREAM);
        for (int side = -1; side <= 1; side += 2) {
            float bx = 20 * side;
            float by = -24;
            float rot = 0;
            float dy = 0;
            if (mood == Mood.TRISTE || mood == Mood.PREOCUPADO) rot = 16 * side;
            if (mode == Mode.LISTENING || mood == Mood.ANIMADO) dy = -3;
            if ((mood == Mood.CURIOSO || mode == Mode.THINKING) && side == 1) dy = -4;
            c.save();
            c.rotate(rot, bx, by + dy);
            oval.set(bx - 6, by + dy - 3.6f, bx + 6, by + dy + 3.6f);
            c.drawOval(oval, fill);
            c.restore();
        }
    }

    private void drawEyes(Canvas c, long now, boolean happy) {
        boolean happyArcs = (mood == Mood.ANIMADO && mode == Mode.IDLE) || (happy && mode != Mode.SLEEPING);
        boolean sleeping = mode == Mode.SLEEPING;
        float lid = 0;
        if (mode != Mode.LISTENING) {
            if (mood == Mood.SONOLENTO) lid = 0.45f;
            else if (mood == Mood.CALMO) lid = 0.2f;
            else if (mood == Mood.TRISTE || mood == Mood.PREOCUPADO) lid = 0.28f;
        }
        float squash = (blinkStart > 0 && now - blinkStart < 140) ? 0.12f : 1f;
        float big = mode == Mode.LISTENING ? 1.1f : 1f;
        stroke.setColor(INK);
        for (int side = -1; side <= 1; side += 2) {
            float cx = 20 * side;
            float cy = -7;
            if (sleeping) {
                stroke.setStrokeWidth(3f);
                work.reset();
                work.moveTo(cx - 7, cy);
                work.quadTo(cx, cy + 6, cx + 7, cy);
                c.drawPath(work, stroke);
                continue;
            }
            if (happyArcs) {
                stroke.setStrokeWidth(3.4f);
                work.reset();
                work.moveTo(cx - 7, cy + 3);
                work.quadTo(cx, cy - 8, cx + 7, cy + 3);
                c.drawPath(work, stroke);
                continue;
            }
            float ex = cx + lookX * 0.6f;
            float ey = cy + lookY * 0.6f;
            c.save();
            c.scale(1f, squash, ex, ey);
            oval.set(ex - 7 * big, ey - 8 * big, ex + 7 * big, ey + 8 * big);
            fill.setColor(INK);
            c.drawOval(oval, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(ex + lookX * 0.5f + 2.4f, ey + lookY * 0.5f - 3f, 2.4f, fill);
            fill.setColor(0xCCFFFFFF);
            c.drawCircle(ex + lookX * 0.5f - 2.2f, ey + lookY * 0.5f + 2.6f, 1f, fill);
            if (lid > 0) {
                clip.reset();
                clip.addOval(oval, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                c.drawRect(oval.left - 1, oval.top - 1, oval.right + 1, oval.top + oval.height() * lid, headPaint);
                c.restore();
            }
            c.restore();
        }
    }

    private void drawMouth(Canvas c, float t, boolean happy) {
        stroke.setColor(INK);
        stroke.setStrokeWidth(2.2f);
        c.drawLine(0, 15, 0, 21, stroke);
        stroke.setStrokeWidth(2.4f);
        if (mouth > 0.04f) {
            float m = mouth;
            work.reset();
            work.moveTo(-11, 23);
            work.quadTo(-6, 27, 0, 21);
            work.quadTo(6, 27, 11, 23);
            work.cubicTo(10, 23 + 15 * m, -10, 23 + 15 * m, -11, 23);
            work.close();
            fill(c, work, MOUTH);
            c.save();
            c.clipPath(work);
            fill.setColor(TONGUE);
            oval.set(-7, 23 + 11 * m - (6 * m + 1), 7, 23 + 11 * m + (6 * m + 1));
            c.drawOval(oval, fill);
            c.restore();
            c.drawPath(SMILE, stroke);
            return;
        }
        if ((mood == Mood.TRISTE || mood == Mood.PREOCUPADO) && mode != Mode.SPEAKING && !happy) {
            c.drawPath(FROWN, stroke);
            return;
        }
        if (((mood == Mood.FELIZ || mood == Mood.ANIMADO) && mode == Mode.IDLE) || happy) {
            float len = 9 + 2 * (float) Math.sin(t * 8);
            work.reset();
            work.moveTo(-6, 24);
            work.lineTo(6, 24);
            work.lineTo(6, 24 + len);
            work.cubicTo(6, 30 + len, -6, 30 + len, -6, 24 + len);
            work.close();
            fill(c, work, TONGUE);
            stroke.setColor(TONGUE_LINE);
            stroke.setStrokeWidth(1.2f);
            c.drawLine(0, 26, 0, 24 + len, stroke);
            stroke.setColor(INK);
            stroke.setStrokeWidth(2.4f);
        }
        c.drawPath(SMILE, stroke);
    }

    /** Andamento do truque atual, de 0 a 1 (encerra o truque ao terminar). */
    private float trickProgress(long now) {
        if (trick == null) return 0f;
        float p = (now - trickAt) / (float) trickDur;
        if (p >= 1f) {
            trick = null;
            return 0f;
        }
        return Math.max(0f, p);
    }

    /** "Dá a pata": a patinha sobe na frente do peito e acena. */
    private void drawPaw(Canvas c, float p) {
        float up = p < 0.2f ? p / 0.2f : p > 0.85f ? (1 - p) / 0.15f : 1f;
        float wave = (p > 0.2f && p < 0.85f) ? 16f * (float) Math.sin((p - 0.2f) / 0.65f * 4 * Math.PI) : 0f;
        c.save();
        c.translate(-24, 112 - 52 * up);
        c.rotate(-10 + wave);
        fill(c, PAW, FUR);
        fill.setColor(FUR);
        c.drawCircle(0, -2, 12, fill);
        fill.setColor(0xFF6B4A3A);
        c.drawCircle(-6, -7, 2.3f, fill);
        c.drawCircle(0, -9.5f, 2.4f, fill);
        c.drawCircle(6, -7, 2.3f, fill);
        oval.set(-6, -3.5f, 6, 5.5f);
        c.drawOval(oval, fill);
        c.restore();
    }

    /** "Au!" num balãozinho. */
    private void drawBark(Canvas c, float p) {
        zText.setTextSize(22);
        zText.setAlpha(Math.round(255 * Math.min(1f, (1 - p) * 2)));
        c.drawText("AU!", -64, -72 - 10 * p, zText);
        zText.setAlpha(255);
    }

    /** Ossinho na boca enquanto mastiga o petisco. */
    private void drawBone(Canvas c, long now) {
        float p = (now - chewAt) / 1600f;
        float scale = 1f - 0.5f * p;
        c.save();
        c.translate(0, 33);
        c.rotate(-12);
        c.scale(scale, scale);
        fill.setColor(BONE);
        c.drawRect(-11, -3, 11, 3, fill);
        c.drawCircle(-12, -3.5f, 4, fill);
        c.drawCircle(-12, 3.5f, 4, fill);
        c.drawCircle(12, -3.5f, 4, fill);
        c.drawCircle(12, 3.5f, 4, fill);
        stroke.setColor(BONE_EDGE);
        stroke.setStrokeWidth(0.8f);
        c.drawLine(-8, -3, 8, -3, stroke);
        c.drawLine(-8, 3, 8, 3, stroke);
        stroke.setColor(INK);
        c.restore();
    }

    /** Coraçõezinhos subindo (carinho, petisco, rabo abanando). */
    private void drawHearts(Canvas c, long now) {
        for (int i = 0; i < heartAt.length; i++) {
            long age = now - heartAt[i];
            if (age < 0 || age > 1400) continue;
            float p = age / 1400f;
            c.save();
            c.translate(heartX[i] + 6 * (float) Math.sin(p * 6), -60 - 50 * p);
            float sc = 0.9f + 0.6f * p;
            c.scale(sc, sc);
            fill.setColor(HEART);
            fill.setAlpha(Math.round(255 * (1 - p)));
            c.drawPath(HEART_P, fill);
            fill.setAlpha(255);
            c.restore();
        }
    }

    private void drawThinking(Canvas c, float t) {
        float[][] dots = {{50, -92, 3.2f}, {63, -99, 4.2f}, {77, -106, 5.2f}};
        for (int i = 0; i < dots.length; i++) {
            float a = 0.35f + 0.65f * Math.max(0f, (float) Math.sin(t * 4 - i * 0.9));
            fill.setColor(ink);
            fill.setAlpha(Math.round(255 * a));
            c.drawCircle(dots[i][0], dots[i][1], dots[i][2], fill);
        }
        fill.setAlpha(255);
    }

    private void drawZzz(Canvas c, float t) {
        for (int k = 0; k < 3; k++) {
            float phase = (t * 0.35f + k / 3f) % 1f;
            zText.setTextSize(10 + 6 * k);
            zText.setAlpha(Math.round(255 * (1f - phase)));
            c.drawText("z", 50 + 10 * k + 6 * phase, -78 - 12 * k - 14 * phase, zText);
        }
    }
}
