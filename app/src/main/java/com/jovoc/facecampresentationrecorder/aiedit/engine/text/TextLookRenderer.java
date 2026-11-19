package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a styled text (one of the six text looks) once into a transparent bitmap, the {@link Sprite} the
 * animator then moves, scales and fades. Sizes are fractions of the text size, colours are roles resolved by
 * {@link PaletteResolver}. How the text is fitted (size, at most two lines, or skipped) is decided by
 * {@link TextLayoutPolicy}; this class only measures through the real paint and draws what the policy says.
 */
public final class TextLookRenderer {

    /** Highlighter bar: how far it sticks out of the text, how far down the line it starts, and its height. */
    private static final float BAR_OVERHANG = 0.1f;
    private static final float BAR_TOP_AT = 0.40f;

    /** A rendered text: the bitmap, and the parts the animator needs besides it. */
    public static final class Sprite {
        public final Bitmap bitmap;
        public final int width;
        public final int height;
        /** The size of what is seen (text, pill or bar), without the room kept for blur and shadow. */
        public final float contentW;
        public final float contentH;
        /** Highlighter bars, one per line, in bitmap pixels; empty for looks without one. */
        public final RectF[] bars;
        public final int barColor;
        /** Per word: the part of the bitmap that holds it (for word-by-word reveal), in word order. */
        public final RectF[] wordClips;
        /** Per typewriter step ({@link TextMotion#revealUnits}): the part of the bitmap that holds it, in step order. */
        public final RectF[] unitClips;
        /** True when the text had to be drawn smaller than asked to fit its box. */
        public final boolean shrunk;
        /** True when the text is on two lines. */
        public final boolean wrapped;

        Sprite(Bitmap bitmap, float contentW, float contentH, RectF[] bars, int barColor, RectF[] wordClips,
               RectF[] unitClips, boolean shrunk, boolean wrapped) {
            this.shrunk = shrunk;
            this.wrapped = wrapped;
            this.bitmap = bitmap;
            this.width = bitmap.getWidth();
            this.height = bitmap.getHeight();
            this.contentW = contentW;
            this.contentH = contentH;
            this.bars = bars;
            this.barColor = barColor;
            this.wordClips = wordClips;
            this.unitClips = unitClips;
        }

        public void recycle() {
            bitmap.recycle();
        }
    }

    private final PaletteResolver colors;

    public TextLookRenderer(PaletteResolver colors) {
        this.colors = colors;
    }

    /**
     * Renders {@code text} in {@code look}.
     *
     * @param colorSlot the item's {@code color_slot}
     * @param sizePx    the wanted text size
     * @param maxWidthPx the widest the whole block (with its pill) may be
     * @return the sprite, or null when the text fits nowhere (the caller skips the overlay; never an ellipsis)
     */
    public Sprite render(String text, TextLook look, int colorSlot, float sizePx, int maxWidthPx) {
        String shown = hasCase(text) ? text.toUpperCase(Locale.ROOT) : text;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.LEFT);

        TextLayoutPolicy.Result fit = TextLayoutPolicy.fit(shown, sizePx, new PaintMeasurer(paint, look, maxWidthPx));
        if (fit.skipped) return null;
        float size = fit.size;
        paint.setTextSize(size);
        StaticLayout layout = layout(fit.joined(), paint, availableWidth(look, size, maxWidthPx), fit.rtl);
        // The real layout broke a line the policy measured as fitting: fail closed rather than draw a third line
        if (layout.getLineCount() > fit.lines.size()) return null;

        float s = size;
        float padX = look.background != null ? (float) look.background.padX * s : 0;
        float padY = look.background != null ? (float) look.background.padY * s : 0;
        float margin = margin(look, s);
        int textW = (int) Math.ceil(maxLineWidth(layout));
        int textH = layout.getHeight();
        float contentW = textW + 2 * padX;
        float contentH = textH + 2 * padY;
        int bmpW = (int) Math.ceil(contentW + 2 * margin);
        int bmpH = (int) Math.ceil(contentH + 2 * margin);
        // The lines are centred in a box as wide as the limit, but the pill is only as wide as the longest line:
        // shift the layout so its longest line starts at the pill's inner left edge
        float left = margin + padX - minLineLeft(layout);
        float top = margin + padY;

        Bitmap bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (look.background != null) {
            drawBackground(canvas, look, colorSlot, s, margin, contentW, contentH);
        }
        drawText(canvas, layout, paint, look, colorSlot, s, left, top);

        RectF[] bars = look.bar != null ? bars(layout, look, s, left, top) : new RectF[0];
        int barColor = look.bar != null
                ? PaletteResolver.withAlpha(colors.resolve(look.bar.color, colorSlot), look.bar.alpha) : 0;
        String drawn = fit.joined();
        return new Sprite(bitmap, contentW, contentH, bars, barColor,
                stripClips(wordSpans(drawn), layout, left, top, bmpW, bmpH),
                stripClips(unitSpans(drawn), layout, left, top, bmpW, bmpH), fit.shrunk, fit.wrapped);
    }

    /** The lines are already broken by the policy (newlines); centred, in the paragraph direction of the text. */
    private static StaticLayout layout(String text, TextPaint paint, int width, boolean rtl) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR)
                .setIncludePad(false)
                .build();
    }

    /** The policy's ruler: real widths from the paint, the room from the look's padding and effect margin. */
    private static final class PaintMeasurer implements TextLayoutPolicy.TextMeasurer {
        private final TextPaint paint;
        private final TextLook look;
        private final int maxWidthPx;

        PaintMeasurer(TextPaint paint, TextLook look, int maxWidthPx) {
            this.paint = paint;
            this.look = look;
            this.maxWidthPx = maxWidthPx;
        }

        @Override
        public float width(String line, float size) {
            paint.setTextSize(size);
            return paint.measureText(line);
        }

        @Override
        public float available(float size) {
            return availableWidth(look, size, maxWidthPx);
        }
    }

    /** The width the lines may use: the block limit less the pill padding and the room kept for effects. */
    private static int availableWidth(TextLook look, float s, int maxWidthPx) {
        float pad = look.background != null ? 2 * (float) look.background.padX * s : 0;
        return Math.max(1, (int) (maxWidthPx - pad - 2 * margin(look, s)));
    }

    /** Room around the text for the stroke, shadow, glow and 3D copies, in pixels. */
    static float margin(TextLook look, float s) {
        double m = 0;
        if (look.stroke != null) m = Math.max(m, look.stroke.width * s / 2);
        if (look.shadow != null) {
            m = Math.max(m, Math.max(Math.abs(look.shadow.dx), Math.abs(look.shadow.dy)) * s + look.shadow.blur * s * 1.5);
        }
        if (look.glow != null) m = Math.max(m, look.glow.blur * s * 1.6);
        if (look.extrude != null) {
            m = Math.max(m, look.extrude.copies * Math.max(Math.abs(look.extrude.dx), Math.abs(look.extrude.dy)) * s);
        }
        return (float) Math.ceil(m) + 2;
    }

    private static float minLineLeft(StaticLayout layout) {
        float l = Float.MAX_VALUE;
        for (int i = 0; i < layout.getLineCount(); i++) l = Math.min(l, layout.getLineLeft(i));
        return l == Float.MAX_VALUE ? 0 : l;
    }

    private static float maxLineWidth(StaticLayout layout) {
        float w = 0;
        for (int i = 0; i < layout.getLineCount(); i++) w = Math.max(w, layout.getLineWidth(i));
        return w;
    }

    private void drawBackground(Canvas canvas, TextLook look, int slot, float s, float margin,
                                float w, float h) {
        TextLook.Background bg = look.background;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(PaletteResolver.withAlpha(colors.resolve(bg.color, slot), bg.alpha));
        if (look.shadow != null) {
            int shadow = PaletteResolver.withAlpha(colors.resolve(look.shadow.color, slot), look.shadow.alpha);
            p.setShadowLayer((float) look.shadow.blur * s, (float) look.shadow.dx * s, (float) look.shadow.dy * s, shadow);
        }
        float r = (float) bg.radius * h;
        canvas.drawRoundRect(margin, margin, margin + w, margin + h, r, r, p);
    }

    /** Glow, 3D copies, shadow, stroke and fill, back to front. */
    private void drawText(Canvas canvas, StaticLayout layout, TextPaint paint, TextLook look, int slot,
                          float s, float left, float top) {
        if (look.glow != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(PaletteResolver.withAlpha(colors.resolve(look.glow.color, slot), look.glow.alpha));
            paint.setMaskFilter(new BlurMaskFilter(Math.max(1f, (float) look.glow.blur * s), BlurMaskFilter.Blur.NORMAL));
            // Two passes make the halo strong enough to read on a busy picture
            drawLayout(canvas, layout, left, top);
            drawLayout(canvas, layout, left, top);
            paint.setMaskFilter(null);
        }
        if (look.extrude != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colors.resolve(look.extrude.color, slot));
            for (int i = look.extrude.copies; i >= 1; i--) {
                drawLayout(canvas, layout, left + i * (float) look.extrude.dx * s, top + i * (float) look.extrude.dy * s);
            }
        }
        if (look.shadow != null && look.background == null) {
            int shadow = PaletteResolver.withAlpha(colors.resolve(look.shadow.color, slot), look.shadow.alpha);
            float sx = left + (float) look.shadow.dx * s;
            float sy = top + (float) look.shadow.dy * s;
            if (look.shadow.blur > 0) {
                paint.setMaskFilter(new BlurMaskFilter((float) look.shadow.blur * s, BlurMaskFilter.Blur.NORMAL));
            }
            if (look.stroke != null) {
                strokeStyle(paint, look.stroke, s);
                paint.setColor(shadow);
                drawLayout(canvas, layout, sx, sy);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(shadow);
            drawLayout(canvas, layout, sx, sy);
            paint.setMaskFilter(null);
        }
        if (look.stroke != null) {
            strokeStyle(paint, look.stroke, s);
            paint.setColor(colors.resolve(look.stroke.color, slot));
            drawLayout(canvas, layout, left, top);
        }
        paint.setStyle(Paint.Style.FILL);
        if (look.fill.isGradient()) {
            int from = colors.resolve(look.fill.gradientFrom, slot);
            int to = colors.resolve(look.fill.gradientTo, slot);
            paint.setShader(new LinearGradient(0, 0, 0, layout.getHeight(), from, to, Shader.TileMode.CLAMP));
        } else {
            paint.setColor(colors.resolve(look.fill.role, slot));
        }
        drawLayout(canvas, layout, left, top);
        paint.setShader(null);
    }

    private static void strokeStyle(TextPaint paint, TextLook.Stroke stroke, float s) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((float) stroke.width * s);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private static void drawLayout(Canvas canvas, StaticLayout layout, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    /** One bar per line, behind the text, from the line's left to its right edge. */
    private RectF[] bars(StaticLayout layout, TextLook look, float s, float left, float top) {
        RectF[] bars = new RectF[layout.getLineCount()];
        for (int i = 0; i < bars.length; i++) {
            float lineTop = layout.getLineTop(i);
            float lineH = layout.getLineBottom(i) - lineTop;
            float barTop = top + lineTop + lineH * BAR_TOP_AT;
            bars[i] = new RectF(left + layout.getLineLeft(i) - BAR_OVERHANG * s, barTop,
                    left + layout.getLineRight(i) + BAR_OVERHANG * s, barTop + (float) look.bar.height * lineH);
        }
        return bars;
    }

    /** Whether the text has letters with case (Latin, Cyrillic, Greek...), so all caps is meaningful. */
    static boolean hasCase(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.toUpperCase(cp) != cp || Character.toLowerCase(cp) != cp) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** The runs of non-white-space characters of {@code text}, as [start, end) offsets. */
    private static List<int[]> wordSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean space = i == text.length() || Character.isWhitespace(text.charAt(i));
            if (!space && start < 0) start = i;
            if (space && start >= 0) {
                spans.add(new int[]{start, i});
                start = -1;
            }
        }
        return spans;
    }

    /** The typewriter steps of {@code text} as [start, end) offsets, trimmed of white space (the line break included). */
    private static List<int[]> unitSpans(String text) {
        int[] ends = TextMotion.revealUnits(text);
        List<int[]> spans = new ArrayList<>();
        int from = 0;
        for (int end : ends) {
            int a = from;
            int b = end;
            while (a < b && Character.isWhitespace(text.charAt(a))) a++;
            while (b > a && Character.isWhitespace(text.charAt(b - 1))) b--;
            if (b > a) spans.add(new int[]{a, b});
            from = end;
        }
        return spans;
    }

    /**
     * For each span the strip of the bitmap it lives in: its own extent between the midpoints to its neighbours on
     * the line, and the full height for the first and last line. Drawn clipped to a strip, only that span shows.
     */
    private static RectF[] stripClips(List<int[]> spans, StaticLayout layout, float left, float top, int bmpW, int bmpH) {
        int n = spans.size();
        float[] wl = new float[n];
        float[] wr = new float[n];
        int[] line = new int[n];
        for (int i = 0; i < n; i++) {
            int[] sp = spans.get(i);
            line[i] = layout.getLineForOffset(sp[0]);
            float a = layout.getPrimaryHorizontal(sp[0]);
            float b = layout.getPrimaryHorizontal(sp[1]);
            // A word split over lines, or a clipped end, gets its line's whole width
            boolean split = layout.getLineForOffset(Math.max(sp[0], sp[1] - 1)) != line[i];
            wl[i] = left + (split ? layout.getLineLeft(line[i]) : Math.min(a, b));
            wr[i] = left + (split ? layout.getLineRight(line[i]) : Math.max(a, b));
        }
        RectF[] clips = new RectF[n];
        for (int i = 0; i < n; i++) {
            float l = 0;
            float r = bmpW;
            // Nearest neighbour on the same line on each side
            for (int j = 0; j < n; j++) {
                if (j == i || line[j] != line[i]) continue;
                if (wr[j] <= wl[i] && (wr[j] + wl[i]) / 2 > l) l = (wr[j] + wl[i]) / 2;
                if (wl[j] >= wr[i] && (wr[i] + wl[j]) / 2 < r) r = (wr[i] + wl[j]) / 2;
            }
            int lines = layout.getLineCount();
            float t = line[i] == 0 ? 0 : top + layout.getLineTop(line[i]);
            float b = line[i] == lines - 1 ? bmpH : top + layout.getLineBottom(line[i]);
            clips[i] = new RectF(l, t, r, b);
        }
        return clips;
    }
}
