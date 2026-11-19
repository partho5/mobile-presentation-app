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
 * Draws a styled text once into a transparent bitmap, the {@link Sprite} the animator then moves, scales and fades.
 * Sizes are fractions of the text size, colours are roles resolved by {@link PaletteResolver}. How the text is fitted
 * (size, at most two lines, or skipped) is decided by {@link TextLayoutPolicy}; what is drawn, and in which order, is
 * decided by {@link LookPaintPlan}. This class is the thin translator: it measures through the real paint, then turns
 * each plan operation into Canvas calls.
 */
public final class TextLookRenderer {

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
     * @param maxWidthPx the widest the whole block (with its box) may be
     * @return the sprite, or null when the text fits nowhere (the caller skips the overlay; never an ellipsis)
     * @throws UnsupportedLookException when the look has a property this painter cannot draw (nothing is drawn)
     */
    public Sprite render(String text, TextLook look, int colorSlot, float sizePx, int maxWidthPx)
            throws UnsupportedLookException {
        LookPaintPlan.Room room = LookPaintPlan.room(look);
        boolean upper = "upper".equals(look.caseMode);
        String shown = upper && hasCase(text) ? text.toUpperCase(Locale.ROOT) : text;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.LEFT);

        TextLayoutPolicy.Result fit = TextLayoutPolicy.fit(shown, sizePx, new PaintMeasurer(paint, room, maxWidthPx));
        if (fit.skipped) return null;
        LookPaintPlan plan = LookPaintPlan.build(look, colors, colorSlot, fit);
        float size = fit.size;
        paint.setTextSize(size);
        StaticLayout layout = layout(fit.joined(), paint, availableWidth(room, size, maxWidthPx), fit.rtl);
        // The real layout broke a line the policy measured as fitting: fail closed rather than draw a third line
        if (layout.getLineCount() > fit.lines.size()) return null;

        float s = size;
        float insetX = (float) room.insetX * s;
        float insetY = (float) room.insetY * s;
        float margin = margin(room, s);
        int textW = (int) Math.ceil(maxLineWidth(layout));
        int textH = layout.getHeight();
        float contentW = textW + 2 * insetX;
        float contentH = textH + 2 * insetY;
        int bmpW = (int) Math.ceil(contentW + 2 * margin);
        int bmpH = (int) Math.ceil(contentH + 2 * margin);
        // The lines are centred in a box as wide as the limit, but the pill is only as wide as the longest line:
        // shift the layout so its longest line starts at the pill's inner left edge
        float minLeft = minLineLeft(layout);
        float left = margin + insetX - minLeft;
        float top = margin + insetY;

        Bitmap bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Frame frame = new Frame(margin, contentW, contentH, (float) plan.borderOuter * s, s, left, top, minLeft,
                maxLineWidth(layout), layout.getHeight());
        int saved = -1;
        if (plan.opacity < 1) saved = canvas.saveLayerAlpha(0, 0, bmpW, bmpH, (int) Math.round(plan.opacity * 255));
        drawPlan(canvas, plan, layout, paint, frame);
        if (saved >= 0) canvas.restoreToCount(saved);

        RectF[] bars = plan.bar != null ? bars(layout, plan.bar, s, left, top) : new RectF[0];
        int barColor = plan.bar != null ? plan.bar.color : 0;
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
        private final LookPaintPlan.Room room;
        private final int maxWidthPx;

        PaintMeasurer(TextPaint paint, LookPaintPlan.Room room, int maxWidthPx) {
            this.paint = paint;
            this.room = room;
            this.maxWidthPx = maxWidthPx;
        }

        @Override
        public float width(String line, float size) {
            paint.setTextSize(size);
            return paint.measureText(line);
        }

        @Override
        public float available(float size) {
            return availableWidth(room, size, maxWidthPx);
        }
    }

    /** The width the lines may use: the block limit less the box padding and border and the room kept for effects. */
    private static int availableWidth(LookPaintPlan.Room room, float s, int maxWidthPx) {
        float inset = 2 * (float) room.insetX * s;
        return Math.max(1, (int) (maxWidthPx - inset - 2 * margin(room, s)));
    }

    /** Room around the content for the stroke, shadow, glow and 3D copies, in pixels. */
    static float margin(LookPaintPlan.Room room, float s) {
        return (float) Math.ceil(room.effect * s) + 2;
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

    /** Where things sit in the bitmap, in pixels: the margin, the content size, the layout's origin and its text box. */
    private static final class Frame {
        final float margin;
        final float s;
        final float left;
        final float top;
        /** The padding rectangle (the box fill); the border and ring lie outside it. */
        final RectF fill;
        /** The text box in layout coordinates, the span a text gradient covers. */
        final float textLeft;
        final float textWidth;
        final float textHeight;

        Frame(float margin, float contentW, float contentH, float borderOuterPx, float s, float left, float top,
              float textLeft, float textWidth, float textHeight) {
            this.margin = margin;
            this.s = s;
            this.left = left;
            this.top = top;
            this.fill = new RectF(margin + borderOuterPx, margin + borderOuterPx, margin + contentW - borderOuterPx,
                    margin + contentH - borderOuterPx);
            this.textLeft = textLeft;
            this.textWidth = textWidth;
            this.textHeight = textHeight;
        }
    }

    /** Turns each operation of the plan, in order, into Canvas calls. */
    private static void drawPlan(Canvas canvas, LookPaintPlan plan, StaticLayout layout, TextPaint paint, Frame f) {
        float s = f.s;
        for (LookPaintPlan.Op op : plan.ops) {
            switch (op.kind) {
                case BOX_SHADOW: {
                    LookPaintPlan.BoxShadowOp o = (LookPaintPlan.BoxShadowOp) op;
                    Paint p = fillPaint(o.color);
                    if (o.blur > 0) p.setMaskFilter(new BlurMaskFilter((float) o.blur * s, BlurMaskFilter.Blur.NORMAL));
                    RectF r = outset(f.fill, (float) o.expand * s);
                    r.offset((float) o.dx * s, (float) o.dy * s);
                    float rad = LookPaintPlan.cornerRadiusPx(o.radiusOfHeight, o.expand, f.fill.height(), s);
                    canvas.drawRoundRect(r, rad, rad, p);
                    break;
                }
                case BOX_FILL: {
                    LookPaintPlan.BoxFillOp o = (LookPaintPlan.BoxFillOp) op;
                    Paint p = fillPaint(o.paint.color);
                    if (o.paint.isGradient()) p.setShader(gradient(o.paint.gradient, f.fill.left, f.fill.top,
                            f.fill.width(), f.fill.height()));
                    float rad = LookPaintPlan.cornerRadiusPx(o.radiusOfHeight, 0, f.fill.height(), s);
                    canvas.drawRoundRect(f.fill, rad, rad, p);
                    break;
                }
                case BOX_BORDER:
                case BOX_RING: {
                    LookPaintPlan.BoxStrokeOp o = (LookPaintPlan.BoxStrokeOp) op;
                    float width = (float) o.width * s;
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(width);
                    p.setColor(o.color);
                    RectF r = outset(f.fill, (float) o.offset * s + width / 2);
                    float rad = LookPaintPlan.cornerRadiusPx(o.radiusOfHeight, o.offset + o.width / 2, f.fill.height(), s);
                    p.setStrokeJoin(rad > 0 ? Paint.Join.ROUND : Paint.Join.MITER);
                    canvas.drawRoundRect(r, rad, rad, p);
                    break;
                }
                case GLOW_BEHIND_TEXT: {
                    LookPaintPlan.GlowBehindOp o = (LookPaintPlan.GlowBehindOp) op;
                    glowText(canvas, layout, paint, f, o.color, o.blur, BlurMaskFilter.Blur.NORMAL);
                    break;
                }
                case EXTRUDE: {
                    LookPaintPlan.ExtrudeOp o = (LookPaintPlan.ExtrudeOp) op;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(o.color);
                    for (int i = o.copies; i >= 1; i--) {
                        drawLayout(canvas, layout, f.left + i * (float) o.dx * s, f.top + i * (float) o.dy * s);
                    }
                    break;
                }
                case TEXT_SHADOW: {
                    LookPaintPlan.TextShadowOp o = (LookPaintPlan.TextShadowOp) op;
                    float sx = f.left + (float) o.dx * s;
                    float sy = f.top + (float) o.dy * s;
                    if (o.blur > 0) paint.setMaskFilter(new BlurMaskFilter((float) o.blur * s, BlurMaskFilter.Blur.NORMAL));
                    if (o.strokeWidth > 0) {
                        strokeStyle(paint, o.strokeWidth, o.strokeJoin, s);
                        paint.setColor(o.color);
                        drawLayout(canvas, layout, sx, sy);
                    }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(o.color);
                    drawLayout(canvas, layout, sx, sy);
                    paint.setMaskFilter(null);
                    break;
                }
                case TEXT_STROKE: {
                    LookPaintPlan.TextStrokeOp o = (LookPaintPlan.TextStrokeOp) op;
                    strokeStyle(paint, o.width, o.join, s);
                    paint.setColor(o.color);
                    drawLayout(canvas, layout, f.left, f.top);
                    break;
                }
                case TEXT_FILL: {
                    LookPaintPlan.TextFillOp o = (LookPaintPlan.TextFillOp) op;
                    paint.setStyle(Paint.Style.FILL);
                    if (o.paint.isGradient()) {
                        // Stops span the measured text box, in the layout's own coordinates
                        paint.setShader(gradient(o.paint.gradient, f.textLeft, 0, f.textWidth, f.textHeight));
                    } else {
                        paint.setColor(o.paint.color);
                    }
                    drawLayout(canvas, layout, f.left, f.top);
                    paint.setShader(null);
                    break;
                }
                case BOX_GLOW: {
                    LookPaintPlan.BoxGlowOp o = (LookPaintPlan.BoxGlowOp) op;
                    Paint p = fillPaint(o.color);
                    p.setMaskFilter(new BlurMaskFilter(Math.max(1f, (float) o.blur * s), BlurMaskFilter.Blur.OUTER));
                    float rad = LookPaintPlan.cornerRadiusPx(o.radiusOfHeight, o.expand, f.fill.height(), s);
                    canvas.drawRoundRect(outset(f.fill, (float) o.expand * s), rad, rad, p);
                    break;
                }
                case TEXT_GLOW: {
                    LookPaintPlan.TextGlowOp o = (LookPaintPlan.TextGlowOp) op;
                    glowText(canvas, layout, paint, f, o.color, o.blur, BlurMaskFilter.Blur.OUTER);
                    break;
                }
                default:
                    // Not reachable: the plan only holds the kinds above; a new kind needs a branch here
                    throw new IllegalStateException("op without a drawer");
            }
        }
    }

    /** A blurred copy of the letters, drawn twice so the halo reads on a busy picture. */
    private static void glowText(Canvas canvas, StaticLayout layout, TextPaint paint, Frame f, int color, double blur,
                                 BlurMaskFilter.Blur style) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setMaskFilter(new BlurMaskFilter(Math.max(1f, (float) blur * f.s), style));
        for (int i = 0; i < LookPaintPlan.PASSES; i++) drawLayout(canvas, layout, f.left, f.top);
        paint.setMaskFilter(null);
    }

    private static Paint fillPaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    private static RectF outset(RectF r, float by) {
        return new RectF(r.left - by, r.top - by, r.right + by, r.bottom + by);
    }

    /** A gradient line (fractions of the painted box) placed on the box at ({@code x}, {@code y}) of {@code w} by {@code h}. */
    private static Shader gradient(LookPaintPlan.Gradient g, float x, float y, float w, float h) {
        return new LinearGradient(x + (float) g.x0 * w, y + (float) g.y0 * h, x + (float) g.x1 * w, y + (float) g.y1 * h,
                g.from, g.to, Shader.TileMode.CLAMP);
    }

    private static void strokeStyle(TextPaint paint, double width, String join, float s) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((float) width * s);
        paint.setStrokeJoin("miter".equals(join) ? Paint.Join.MITER : "bevel".equals(join) ? Paint.Join.BEVEL
                : Paint.Join.ROUND);
    }

    private static void drawLayout(Canvas canvas, StaticLayout layout, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    /** One bar per line, behind the text, from the line's left to its right edge. */
    private static RectF[] bars(StaticLayout layout, LookPaintPlan.Bar bar, float s, float left, float top) {
        RectF[] bars = new RectF[layout.getLineCount()];
        for (int i = 0; i < bars.length; i++) {
            float lineTop = layout.getLineTop(i);
            float lineH = layout.getLineBottom(i) - lineTop;
            float barTop = top + lineTop + lineH * (float) LookPaintPlan.Bar.TOP_AT;
            float overhang = (float) LookPaintPlan.Bar.OVERHANG * s;
            bars[i] = new RectF(left + layout.getLineLeft(i) - overhang, barTop,
                    left + layout.getLineRight(i) + overhang, barTop + (float) bar.heightOfLine * lineH);
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
