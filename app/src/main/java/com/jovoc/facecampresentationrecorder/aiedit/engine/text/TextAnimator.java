package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.effects.Ease;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * The motion of one text overlay on the output timeline: an entry ({@code pop}, {@code stamp}, {@code slide_up},
 * {@code word_by_word}, {@code sweep}, and {@code fade}, {@code slide_left}, {@code slide_right}, {@code slide_top},
 * {@code slide_bottom}, {@code typewriter}, {@code wipe}, {@code drop_bounce}, {@code blur_in}, {@code flip},
 * {@code glitch}), a hold, and the exit (scale 1 to 0.85 while the alpha falls, out cubic). Entry, shake and exit
 * times are clamped to the contract's {@code speed} (typewriter: its own 200 to 500 ms) and to the item's length.
 * After its entry an animation adds no motion. Pure maths: it fills a reusable {@link State}; the painter turns that
 * into canvas calls on the cached sprite. A slide named for a side starts on that side and moves to its place.
 */
public final class TextAnimator {

    /** What the painter draws at one moment; offsets are fractions of the output size. Reused every frame. */
    public static final class State {
        public float scale = 1;
        public float alpha = 1;
        public float dx;
        public float dy;
        /** How far the highlighter bar has swept, 0..1. */
        public float barProgress = 1;
        /** Words shown so far, or {@link #ALL_WORDS}. */
        public int words = ALL_WORDS;
        /** Vertical squash for the flip, 0..1 (1 = upright). */
        public float scaleY = 1;
        /** How blurred the sprite is, 1 = fully blurred, 0 = sharp. */
        public float blur;
        /** Share of the sprite revealed by a wipe, 0..1, from the reading-direction edge. */
        public float reveal = 1;
        /** True when {@link #reveal} grows from the right edge (right-to-left text). */
        public boolean revealFromRight;
        /** Typewriter steps shown so far (of the animator's unit count), or {@link #ALL_WORDS}. */
        public int units = ALL_WORDS;

        void reset() {
            scaleY = 1;
            blur = 0;
            reveal = 1;
            revealFromRight = false;
            units = ALL_WORDS;
            scale = 1;
            alpha = 1;
            dx = 0;
            dy = 0;
            barProgress = 1;
            words = ALL_WORDS;
        }
    }

    public static final int ALL_WORDS = -1;

    /** The pop overshoots to this and settles to 1. */
    static final float POP_FROM = 0.6f;
    static final float POP_PEAK = 1.08f;
    /** Where the pop reaches its peak (share of the entry). */
    static final double POP_PEAK_AT = 0.7;
    static final float STAMP_FROM = 2.0f;
    /** Stamp shake size, a fraction of the output width. */
    static final float SHAKE_AMPLITUDE = 0.01f;
    static final float EXIT_SCALE = 0.85f;
    /** Side slides start this far from the place, a share of the output width (up and down: {@code from_dy}). */
    static final float SLIDE_DX = 0.12f;
    static final double SLIDE_DX_MIN = 0.02;
    static final double SLIDE_DX_MAX = 0.3;
    /** A dropped text starts this much higher, a share of the output height, and bounces to its place. */
    static final float DROP_DY = 0.12f;
    /** The glitch jumps to a new offset this often. */
    static final long GLITCH_STEP_MS = 30;
    static final float GLITCH_AMPLITUDE = 0.02f;

    /** Entry ids and their duration: key, min, default, max in ms (contract doc, motion params). */
    private static final Object[][] ENTRY_TABLE = {
            {"fade", "entry_ms", 120L, 200L, 300L},
            {"slide_left", "entry_ms", 120L, 200L, 300L},
            {"slide_right", "entry_ms", 120L, 200L, 300L},
            {"slide_top", "entry_ms", 120L, 200L, 300L},
            {"slide_bottom", "entry_ms", 120L, 200L, 300L},
            {"wipe", "entry_ms", 150L, 220L, 300L},
            {"blur_in", "entry_ms", 150L, 220L, 300L},
            {"flip", "entry_ms", 150L, 220L, 300L},
            {"drop_bounce", "entry_ms", 150L, 260L, 300L},
            {"glitch", "entry_ms", 120L, 180L, 260L},
            {"typewriter", "typing_ms", 200L, 400L, 500L},
    };

    final String entry;
    final long startMs;
    final long endMs;
    final long entryMs;
    final long shakeMs;
    final int shakes;
    final long exitMs;
    private final double fromDy;
    private final double fromDx;
    private final int wordCount;
    private final int unitCount;
    private final boolean rtl;
    private final long wordStepMs;

    public TextAnimator(PlanItem item, RegistryEntry registry, EngineContract.SpeedRules speed, int wordCount) {
        this(item, registry, speed, wordCount, wordCount, false);
    }

    /**
     * @param unitCount typewriter steps of the text ({@code TextMotion.revealUnits(text).length})
     * @param rtl       whether the text reads right to left ({@code TextMotion.readingDirection(text)}): a wipe starts
     *                  from the right
     */
    public TextAnimator(PlanItem item, RegistryEntry registry, EngineContract.SpeedRules speed, int wordCount,
                        int unitCount, boolean rtl) {
        startMs = item.startMs;
        endMs = item.endMs;
        this.wordCount = Math.max(1, wordCount);
        this.unitCount = Math.max(1, unitCount);
        this.rtl = rtl;
        wordStepMs = speed.textHoldPerWordMs;
        String kind = item.paramString("entry", defaultString(registry, "entry", "pop"));
        entry = isKnown(kind) ? kind : "pop";
        long length = Math.max(0, endMs - startMs);

        long exit = clamp(Math.round(number(item, registry, "exit_ms", 150)), speed.exitMs[0], speed.exitMs[1]);
        exit = Math.min(exit, length / 2);
        long ent;
        Object[] row = entryRow(entry);
        if (row == null) {
            ent = Math.round(number(item, registry, "entry_ms", 200));
            ent = limit(registry, "entry_ms", ent);
            ent = clamp(ent, speed.entryMs[0], speed.entryMs[1]);
        } else {
            // The motion's own limits (the registry's entry_ms range belongs to the effect's own entry)
            long min = (Long) row[2];
            long max = (Long) row[4];
            ent = clamp(Math.round(item.paramNumber((String) row[1], (Long) row[3])), min, max);
            if ("typewriter".equals(entry)) {
                ent = TextMotion.typewriterMs(this.unitCount, ent);
            } else {
                ent = clamp(ent, speed.entryMs[0], speed.entryMs[1]);
            }
        }
        long shake = 0;
        int count = 0;
        if ("stamp".equals(entry)) {
            shake = limit(registry, "shake_ms", Math.round(number(item, registry, "shake_ms", 120)));
            count = (int) Math.max(1, Math.round(number(item, registry, "shakes", 3)));
            // Hit and shake together stay inside the longest entry
            shake = Math.max(0, Math.min(shake, speed.entryMs[1] - ent));
        }
        ent = Math.min(ent, Math.max(0, length - exit - shake));
        entryMs = ent;
        shakeMs = shake;
        shakes = count;
        exitMs = exit;
        fromDy = limitD(registry, "from_dy", item.paramNumber("from_dy", defaultNumber(registry, "from_dy", 0.08)));
        fromDx = Math.max(SLIDE_DX_MIN, Math.min(SLIDE_DX_MAX, item.paramNumber("from_dx", SLIDE_DX)));
    }

    private static boolean isKnown(String kind) {
        return "pop".equals(kind) || "stamp".equals(kind) || "slide_up".equals(kind)
                || "word_by_word".equals(kind) || "sweep".equals(kind) || entryRow(kind) != null;
    }

    private static Object[] entryRow(String kind) {
        for (Object[] row : ENTRY_TABLE) if (row[0].equals(kind)) return row;
        return null;
    }

    /** The whole entry: the hit, plus the shake of a stamp. */
    long entryTotalMs() {
        return entryMs + shakeMs;
    }

    /**
     * Fills {@code out} for {@code outputMs}.
     *
     * @return false when the overlay is not on screen at that time
     */
    public boolean at(long outputMs, State out) {
        out.reset();
        if (outputMs < startMs || outputMs >= endMs) return false;
        long sinceStart = outputMs - startMs;
        if (sinceStart < entryTotalMs()) {
            entryFrame(outputMs, out);
        } else if (entry.equals("word_by_word")) {
            out.words = visibleWords(sinceStart);
        }
        if (exitMs > 0 && outputMs >= endMs - exitMs) {
            double p = Ease.outCubic(Ease.progress(outputMs, endMs - exitMs, exitMs));
            out.scale = (float) Ease.lerp(1, EXIT_SCALE, p);
            out.alpha = (float) (1 - p);
            out.dx = 0;
            out.dy = 0;
            out.barProgress = 1;
            out.scaleY = 1;
            out.blur = 0;
            out.reveal = 1;
        }
        return true;
    }

    private void entryFrame(long outputMs, State out) {
        double p = Ease.progress(outputMs, startMs, entryMs);
        switch (entry) {
            case "stamp":
                if (outputMs - startMs < entryMs) {
                    out.scale = (float) Ease.lerp(STAMP_FROM, 1, Ease.outCubic(p));
                    out.alpha = (float) Ease.clamp01(p * 3);
                } else {
                    double q = Ease.progress(outputMs, startMs + entryMs, shakeMs);
                    out.dx = (float) (SHAKE_AMPLITUDE * Math.sin(2 * Math.PI * shakes * q) * (1 - q));
                }
                break;
            case "slide_up":
                out.dy = (float) (fromDy * (1 - Ease.outCubic(p)));
                out.alpha = (float) Ease.outCubic(p);
                break;
            case "sweep":
                out.alpha = (float) Ease.clamp01(p * 3);
                out.barProgress = (float) Ease.outCubic(p);
                break;
            case "word_by_word":
                out.alpha = (float) Ease.outCubic(p);
                out.words = visibleWords(outputMs - startMs);
                break;
            case "fade":
                out.alpha = (float) Ease.outCubic(p);
                break;
            case "slide_left":
                out.dx = (float) (-fromDx * (1 - Ease.outCubic(p)));
                out.alpha = (float) Ease.outCubic(p);
                break;
            case "slide_right":
                out.dx = (float) (fromDx * (1 - Ease.outCubic(p)));
                out.alpha = (float) Ease.outCubic(p);
                break;
            case "slide_top":
                out.dy = (float) (-fromDy * (1 - Ease.outCubic(p)));
                out.alpha = (float) Ease.outCubic(p);
                break;
            case "slide_bottom":
                out.dy = (float) (fromDy * (1 - Ease.outCubic(p)));
                out.alpha = (float) Ease.outCubic(p);
                break;
            case "typewriter":
                out.units = (int) Math.min(unitCount, Math.ceil(p * unitCount));
                break;
            case "wipe":
                out.reveal = (float) Ease.outCubic(p);
                out.revealFromRight = rtl;
                break;
            case "drop_bounce":
                out.dy = (float) (-DROP_DY * (1 - outBounce(p)));
                out.alpha = (float) Ease.clamp01(p * 4);
                break;
            case "blur_in":
                out.blur = (float) (1 - Ease.outCubic(p));
                out.alpha = (float) Ease.clamp01(p * 2.5);
                break;
            case "flip":
                out.scaleY = (float) Math.sin(Ease.outCubic(p) * Math.PI / 2);
                out.alpha = (float) Ease.clamp01(p * 3);
                break;
            case "glitch":
                glitchFrame(outputMs - startMs, p, out);
                break;
            default:
                if (p < POP_PEAK_AT) {
                    out.scale = (float) Ease.lerp(POP_FROM, POP_PEAK, Ease.outCubic(p / POP_PEAK_AT));
                } else {
                    out.scale = (float) Ease.lerp(POP_PEAK, 1, Ease.outCubic((p - POP_PEAK_AT) / (1 - POP_PEAK_AT)));
                }
                out.alpha = (float) Ease.clamp01(p * 2);
        }
    }

    /**
     * A short digital jitter: the offset jumps every 30 ms, in a pattern fixed by the step number (so a re-render
     * matches), shrinking to 0; some early steps are dimmed. It ends settled.
     */
    private void glitchFrame(long sinceStartMs, double p, State out) {
        long step = sinceStartMs / GLITCH_STEP_MS;
        int h = (int) (step * 0x9E3779B1L >>> 7) & 0xFFFF;
        double jump = (h % 2001) / 1000.0 - 1;   // -1..1
        double fade = 1 - p;
        out.dx = (float) (GLITCH_AMPLITUDE * jump * fade);
        out.alpha = (float) (p < 0.5 && step % 3 == 0 ? 0.4 : Ease.clamp01(0.4 + p * 3));
    }

    /** Out bounce: reaches 1 at 1, rebounding twice on the way. */
    static double outBounce(double p) {
        double t = Ease.clamp01(p);
        final double n = 7.5625;
        final double d = 2.75;
        if (t < 1 / d) return n * t * t;
        if (t < 2 / d) {
            t -= 1.5 / d;
            return n * t * t + 0.75;
        }
        if (t < 2.5 / d) {
            t -= 2.25 / d;
            return n * t * t + 0.9375;
        }
        t -= 2.625 / d;
        return n * t * t + 0.984375;
    }

    /** Words are shown at about the spoken pace: one more every {@code text_hold.per_word_ms}. */
    private int visibleWords(long sinceStartMs) {
        long step = Math.max(1, wordStepMs);
        return (int) Math.min(wordCount, 1 + sinceStartMs / step);
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String defaultString(RegistryEntry e, String key, String fallback) {
        Object v = e != null ? e.defaultParams.get(key) : null;
        return v instanceof String ? (String) v : fallback;
    }

    private static double defaultNumber(RegistryEntry e, String key, double fallback) {
        return e != null ? e.defaultNumber(key, fallback) : fallback;
    }

    private static double number(PlanItem item, RegistryEntry e, String key, double fallback) {
        return item.paramNumber(key, defaultNumber(e, key, fallback));
    }

    private static long limit(RegistryEntry e, String key, long v) {
        return Math.round(limitD(e, key, v));
    }

    private static double limitD(RegistryEntry e, String key, double v) {
        double[] r = e != null ? e.paramLimits.get(key) : null;
        return r == null ? v : Math.max(r[0], Math.min(r[1], v));
    }
}
