package com.jovoc.facecampresentationrecorder.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.LineBackgroundSpan;

/**
 * An underline that looks swiped on with a brush, left to right: it wobbles a little, swells
 * and thins unevenly, and overshoots the words on both sides. When the text wraps, each line
 * gets its own stroke with its own shape. {@link #setAlpha} lets the owner fade it in; the owner
 * must invalidate the TextView after changing it.
 */
final class BrushUnderlineSpan implements LineBackgroundSpan {

    private final int color;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int alpha = 255;

    BrushUnderlineSpan(int color, float density) {
        this.color = color;
        this.density = density;
        paint.setStyle(Paint.Style.FILL);
    }

    void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
    }

    @Override
    public void drawBackground(Canvas canvas, Paint p, int left, int right, int top, int baseline,
                               int bottom, CharSequence text, int start, int end, int lineNumber) {
        if (alpha == 0 || !(text instanceof Spanned)) return;
        Spanned spanned = (Spanned) text;
        int from = Math.max(start, spanned.getSpanStart(this));
        int to = Math.min(end, spanned.getSpanEnd(this));
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) to--;
        while (from < to && Character.isWhitespace(text.charAt(from))) from++;
        if (from >= to) return;

        float x0 = left + p.measureText(text, start, from) - 3 * density;
        float x1 = left + p.measureText(text, start, to) + 5 * density;
        float thickness = Math.max(2.5f * density, p.getTextSize() * 0.13f);
        float centerY = baseline + p.descent() * 0.75f + 4 * density;
        buildStroke(x0, x1, centerY, thickness, lineNumber * 5 + 3);

        paint.setColor(color);
        paint.setAlpha(alpha);
        canvas.drawPath(path, paint);
    }

    /** A closed ribbon from x0 to x1: a slow sag-and-lift wobble, a blunt start, a tapering tail. */
    private void buildStroke(float x0, float x1, float centerY, float thickness, int seed) {
        final int steps = 24;
        float[] x = new float[steps + 1];
        float[] top = new float[steps + 1];
        float[] bottom = new float[steps + 1];
        float width = x1 - x0;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float wobble = (float) (Math.sin(t * 5.1 + seed) * 0.35 + Math.sin(t * 13.3 + seed * 2) * 0.12)
                    * thickness;
            float press = t < 0.08f ? 0.55f + 0.45f * (t / 0.08f) : 1f;
            float tail = t > 0.7f ? 1f - 0.75f * ((t - 0.7f) / 0.3f) : 1f;
            float rough = 1f + (float) Math.sin(t * 19.0 + seed * 3) * 0.15f;
            float half = thickness * 0.5f * press * tail * rough;
            x[i] = x0 + t * width;
            top[i] = centerY + wobble - half;
            bottom[i] = centerY + wobble + half;
        }
        path.reset();
        path.moveTo(x[0], top[0]);
        for (int i = 1; i <= steps; i++) {
            path.quadTo(x[i - 1], top[i - 1], (x[i - 1] + x[i]) / 2f, (top[i - 1] + top[i]) / 2f);
        }
        path.lineTo(x[steps], top[steps]);
        path.lineTo(x[steps], bottom[steps]);
        for (int i = steps; i >= 1; i--) {
            path.quadTo(x[i], bottom[i], (x[i - 1] + x[i]) / 2f, (bottom[i - 1] + bottom[i]) / 2f);
        }
        path.lineTo(x[0], bottom[0]);
        path.close();
    }
}
