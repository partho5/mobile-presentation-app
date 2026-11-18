package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.effects.Ease;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * The motion of one text overlay on the output timeline: an entry ({@code pop}, {@code stamp}, {@code slide_up},
 * {@code word_by_word}, {@code sweep}), a hold, and the exit (scale 1 to 0.85 while the alpha falls, out cubic).
 * Entry, shake and exit times are clamped to the contract's {@code speed} and to the item's length. Pure maths: it
 * fills a reusable {@link State}; the painter turns that into canvas calls on the cached sprite.
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

        void reset() {
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

    final String entry;
    final long startMs;
    final long endMs;
    final long entryMs;
    final long shakeMs;
    final int shakes;
    final long exitMs;
    private final double fromDy;
    private final int wordCount;
    private final long wordStepMs;

    public TextAnimator(PlanItem item, RegistryEntry registry, EngineContract.SpeedRules speed, int wordCount) {
        startMs = item.startMs;
        endMs = item.endMs;
        this.wordCount = Math.max(1, wordCount);
        wordStepMs = speed.textHoldPerWordMs;
        String kind = item.paramString("entry", defaultString(registry, "entry", "pop"));
        entry = isKnown(kind) ? kind : "pop";
        long length = Math.max(0, endMs - startMs);

        long exit = clamp(Math.round(number(item, registry, "exit_ms", 150)), speed.exitMs[0], speed.exitMs[1]);
        exit = Math.min(exit, length / 2);
        long ent = Math.round(number(item, registry, "entry_ms", 200));
        ent = limit(registry, "entry_ms", ent);
        ent = clamp(ent, speed.entryMs[0], speed.entryMs[1]);
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
    }

    private static boolean isKnown(String kind) {
        return "pop".equals(kind) || "stamp".equals(kind) || "slide_up".equals(kind)
                || "word_by_word".equals(kind) || "sweep".equals(kind);
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
            default:
                if (p < POP_PEAK_AT) {
                    out.scale = (float) Ease.lerp(POP_FROM, POP_PEAK, Ease.outCubic(p / POP_PEAK_AT));
                } else {
                    out.scale = (float) Ease.lerp(POP_PEAK, 1, Ease.outCubic((p - POP_PEAK_AT) / (1 - POP_PEAK_AT)));
                }
                out.alpha = (float) Ease.clamp01(p * 2);
        }
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
