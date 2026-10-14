package com.jovoc.facecampresentationrecorder.util;

import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

/**
 * Reusable highlighter-pen look; most callers want {@link MarkerText} instead.
 * Draws a word as if marked with a highlighter pen: the edges wander a little and the two ends
 * are slanted, never a perfect rectangle. One span per word keeps line wrapping normal; all
 * words of a phrase share a seed and the wobble is a function of x, so the pieces join into one
 * continuous stroke. The shape is deterministic, so it does not change between redraws.
 */
public final class HighlighterSpan extends ReplacementSpan {

    private final int markColor;
    private final int textColor;
    private final float phase1;
    private final float phase2;
    private final float phase3;
    private final float phase4;
    private final boolean firstWord;
    private final boolean lastWord;
    private final float slantStart;
    private final float slantEnd;

    private final Path path = new Path();

    public HighlighterSpan(int markColor, int textColor, int seed, boolean firstWord, boolean lastWord) {
        this.markColor = markColor;
        this.textColor = textColor;
        this.firstWord = firstWord;
        this.lastWord = lastWord;
        phase1 = unit(seed, 1) * 6.28f;
        phase2 = unit(seed, 2) * 6.28f;
        phase3 = unit(seed, 3) * 6.28f;
        phase4 = unit(seed, 4) * 6.28f;
        slantStart = unit(seed, 5) - 0.5f;
        slantEnd = unit(seed, 6) - 0.5f;
    }

    /** A repeatable number in [0, 1) for a seed and a slot. */
    private static float unit(int seed, int slot) {
        int h = seed * 31 + slot * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 0xFFFF) / 65536f;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return Math.round(paint.measureText(text, start, end));
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top,
                     int y, int bottom, @NonNull Paint paint) {
        float width = paint.measureText(text, start, end);
        float size = paint.getTextSize();
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float yTop = y + metrics.ascent + size * 0.14f;
        float yBottom = y + metrics.descent - size * 0.02f;
        float wobble = size * 0.06f;
        float slant = size * 0.16f;
        float left = x - (firstWord ? size * 0.12f : 0.6f);
        float right = x + width + (lastWord ? size * 0.12f : 0.6f);
        float step = Math.max(size * 0.35f, 1f);

        path.reset();
        path.moveTo(left + (firstWord ? slantStart * slant : 0f), edge(left, yTop, wobble, phase1, phase2));
        for (float px = left + step; px < right; px += step) {
            path.lineTo(px, edge(px, yTop, wobble, phase1, phase2));
        }
        path.lineTo(right + (lastWord ? slantEnd * slant : 0f), edge(right, yTop, wobble, phase1, phase2));
        path.lineTo(right - (lastWord ? slantEnd * slant : 0f), edge(right, yBottom, wobble, phase3, phase4));
        for (float px = right - step; px > left; px -= step) {
            path.lineTo(px, edge(px, yBottom, wobble, phase3, phase4));
        }
        path.lineTo(left - (firstWord ? slantStart * slant : 0f), edge(left, yBottom, wobble, phase3, phase4));
        path.close();

        int oldColor = paint.getColor();
        Paint.Style oldStyle = paint.getStyle();
        PathEffect oldEffect = paint.getPathEffect();
        paint.setColor(markColor);
        paint.setStyle(Paint.Style.FILL);
        paint.setPathEffect(new CornerPathEffect(size * 0.2f));
        canvas.drawPath(path, paint);
        paint.setPathEffect(oldEffect);
        paint.setStyle(oldStyle);

        paint.setColor(textColor);
        canvas.drawText(text, start, end, x, y, paint);
        paint.setColor(oldColor);
    }

    /** Height of an edge at x: two slow sine waves, so neighbouring words continue each other. */
    private static float edge(float x, float base, float wobble, float phaseA, float phaseB) {
        float u = x / 40f;
        return base + wobble * (0.6f * (float) Math.sin(u * 1.3f + phaseA)
                + 0.4f * (float) Math.sin(u * 3.1f + phaseB));
    }
}
