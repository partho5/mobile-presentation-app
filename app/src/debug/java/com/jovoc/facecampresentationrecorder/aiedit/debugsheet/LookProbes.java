package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.PaletteResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The pixel probes of the debug look sheet, as pure geometry: where on the rendered picture to look, and what colour (or
 * transparency) has to be there. Pure Java (no Bitmap, Canvas or Paint): the sheet feeds {@link #evaluate} a
 * {@link PixelSource} over the real {@code Bitmap}; the JVM test feeds it synthetic pixels and pins every rectangle.
 *
 * <p>The expectations come from the {@link TextLook} data and the resolved colour roles, never from the paint plan the
 * renderer draws from, so a drawing bug (a missing stroke, a shadow at the wrong offset, a gradient painted flat, a box
 * that does not hold its text) shows up as a failed probe rather than agreeing with itself. The sprite probes use the
 * measured sprite: its size, the size of what is seen ({@code contentW} by {@code contentH}) and the text size {@code s}.
 *
 * <p>Honest limits: glyph pixels cannot be located by geometry, so text probes look for the colour anywhere in the text
 * box ({@link Kind#EACH_HAS}); a text shadow in the same colour as the stroke (the shipped sticker) is therefore a weak
 * probe, and the legacy text glow and the highlighter bar have none. The owner's eyes on the tile stay the verdict.
 */
public final class LookProbes {

    /** Where pixels come from: the sprite's bitmap, the frame's bitmap, or a fake in a test. */
    public interface PixelSource {
        int width();

        int height();

        /** The non-premultiplied ARGB at (x, y), inside the bounds. */
        int argb(int x, int y);
    }

    /** A rectangle of pixels, {@code x0..x1} by {@code y0..y1}, the far edges exclusive; never empty once built by {@link #of}. */
    public static final class Rect {
        public final int x0;
        public final int y0;
        public final int x1;
        public final int y1;

        Rect(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        /** The rectangle of the pixels a box of real coordinates touches, at least one pixel each way, inside {@code w} by {@code h}. */
        static Rect of(double l, double t, double r, double b, int w, int h) {
            int x0 = (int) Math.floor(Math.min(l, r) + 0.5);
            int y0 = (int) Math.floor(Math.min(t, b) + 0.5);
            int x1 = (int) Math.floor(Math.max(l, r) + 0.5);
            int y1 = (int) Math.floor(Math.max(t, b) + 0.5);
            if (x1 <= x0) x1 = x0 + 1;
            if (y1 <= y0) y1 = y0 + 1;
            return new Rect(Math.max(0, x0), Math.max(0, y0), Math.min(w, x1), Math.min(h, y1));
        }

        public boolean isEmpty() {
            return x1 <= x0 || y1 <= y0;
        }

        public int width() {
            return x1 - x0;
        }

        public int height() {
            return y1 - y0;
        }

        @Override
        public String toString() {
            return "[" + x0 + "," + y0 + " - " + x1 + "," + y1 + ")";
        }
    }

    /** What a probe checks, so the sheet and the test can name it. */
    public enum Check {
        TEXT_COLOUR("text colour"), TEXT_GRADIENT("text gradient ends differ"), TEXT_STROKE("text stroke colour"),
        TEXT_SHADOW("text shadow colour at its offset"), EXTRUDE("3D copies colour"), BOX_FILL("box fill and padding"),
        BOX_GRADIENT("box gradient ends differ"), BOX_BORDER("box border colour"), BOX_RING("box second ring colour"),
        BOX_SHADOW("box shadow colour at its offset"), BOX_GLOW("box glow outside the box"),
        SPRITE_EDGE("nothing cut at the picture edge"), FRAME_EDGE("nothing cut at the video edge"),
        SAFE_EDGE("nothing reaches the safe rectangle edge");

        public final String label;

        Check(String label) {
            this.label = label;
        }
    }

    /** How a probe reads its rectangles. */
    public enum Kind {
        /** Every pixel of every rectangle matches. */
        ALL,
        /** Every rectangle holds at least one matching pixel. */
        EACH_HAS,
        /** The best match for colour A in the A rectangles and the best match for colour B in the B rectangles both exist, and differ. */
        ENDS_DIFFER
    }

    /** One probe: rectangles and what a pixel there must look like. */
    public static final class Probe {
        public final Check check;
        public final Kind kind;
        public final List<Rect> a;
        public final List<Rect> b;
        /** The wanted colour (RGB; the alpha is the range below); {@code hasColor} false means any colour. */
        public final boolean hasColor;
        public final int color;
        /** The colour of the B rectangles of {@link Kind#ENDS_DIFFER}. */
        public final int colorB;
        /** The most a channel may differ from the wanted colour. */
        public final int tol;
        public final int alphaMin;
        public final int alphaMax;
        /** A colour no pixel may be near ({@link Kind#ALL}); {@code hasAvoid} false means none. */
        public final boolean hasAvoid;
        public final int avoid;
        public final int avoidTol;
        /** For {@link Kind#ENDS_DIFFER}: the least the two best matches must differ by. */
        public final int minDelta;
        /** A short note on where the rectangles are, for the sheet ("left", "bottom"...). */
        public final String note;

        Probe(Check check, Kind kind, List<Rect> a, List<Rect> b, boolean hasColor, int color, int colorB, int tol,
              int alphaMin, int alphaMax, boolean hasAvoid, int avoid, int avoidTol, int minDelta, String note) {
            this.check = check;
            this.kind = kind;
            this.a = Collections.unmodifiableList(a);
            this.b = Collections.unmodifiableList(b);
            this.hasColor = hasColor;
            this.color = color;
            this.colorB = colorB;
            this.tol = tol;
            this.alphaMin = alphaMin;
            this.alphaMax = alphaMax;
            this.hasAvoid = hasAvoid;
            this.avoid = avoid;
            this.avoidTol = avoidTol;
            this.minDelta = minDelta;
            this.note = note;
        }

        public String name() {
            return check.label;
        }
    }

    /** The outcome of one probe. */
    public static final class Result {
        public final Probe probe;
        public final boolean pass;
        /** What was found, in numbers and positions only. */
        public final String detail;

        Result(Probe probe, boolean pass, String detail) {
            this.probe = probe;
            this.pass = pass;
            this.detail = detail;
        }
    }

    /** What the renderer made: the sprite's size in pixels, the size of what is seen, and the text size. */
    public static final class Sprite {
        public final int width;
        public final int height;
        public final float contentW;
        public final float contentH;
        public final float sizePx;

        public Sprite(int width, int height, float contentW, float contentH, float sizePx) {
            this.width = width;
            this.height = height;
            this.contentW = contentW;
            this.contentH = contentH;
            this.sizePx = sizePx;
        }
    }

    /** A pixel may be at most this transparent-looking at an edge that must stay clear. */
    static final int CLEAR_ALPHA_MAX = 3;
    /** Channel tolerance for a flat colour (anti-aliasing and 8-bit rounding). */
    static final int FLAT_TOL = 14;

    private LookProbes() {}

    // ----------------------------------------------------------------------------------------------- geometry

    /** The border plus the second ring, from the padding rectangle outwards, in text-size units. */
    static double borderOuter(TextLook.Box box) {
        if (box.border == null) return 0;
        double w = box.border.width;
        if (box.border.ring != null) w += box.border.ring.gap + box.border.ring.width;
        return w;
    }

    /** The probes that read the look's sprite: the box, the text and the picture's own edge. */
    public static List<Probe> forSprite(TextLook look, PaletteResolver colors, int colorSlot, Sprite sp) {
        List<Probe> out = new ArrayList<>();
        final double s = sp.sizePx;
        final int w = sp.width;
        final int h = sp.height;
        final TextLook.Box box = look.box;
        final boolean fades = look.opacity < 1;
        final int aLo = fades ? 0 : 250;
        final double mx = (w - sp.contentW) / 2.0;
        final double my = (h - sp.contentH) / 2.0;
        // The seen rectangle (the box outline, or the text box without a box) and the text box inside it
        final double cl = mx;
        final double ct = my;
        final double cr = mx + sp.contentW;
        final double cb = my + sp.contentH;
        final double outer = box != null ? borderOuter(box) : 0;
        final double insetX = box != null ? box.padX + outer : 0;
        final double insetY = box != null ? box.padY + outer : 0;
        final double tl = cl + insetX * s;
        final double tt = ct + insetY * s;
        final double tr = cr - insetX * s;
        final double tb = cb - insetY * s;
        final double tw = tr - tl;
        final double th = tb - tt;

        // ---- the text
        int textColor = 0;
        boolean textSolid = "solid".equals(look.textFill.kind);
        if (textSolid) {
            textColor = argb(colors, look.textFill.color, colorSlot, 1);
            out.add(flat(Check.TEXT_COLOUR, Kind.EACH_HAS, one(Rect.of(tl, tt, tr, tb, w, h)), textColor, aLo, 255, "text box"));
        } else if ("gradient".equals(look.textFill.kind)) {
            int from = argb(colors, look.textFill.from, colorSlot, 1);
            int to = argb(colors, look.textFill.to, colorSlot, 1);
            Probe p = gradientEnds(Check.TEXT_GRADIENT, look.textFill.direction, from, to, tl, tt, tw, th, w, h, fades);
            if (p != null) out.add(p);
        }
        if (look.textStroke != null) {
            double e = look.textStroke.width / 2 * s;
            int c = argb(colors, look.textStroke.color, colorSlot, 1);
            out.add(flat(Check.TEXT_STROKE, Kind.EACH_HAS, one(Rect.of(tl - e, tt - e, tr + e, tb + e, w, h)), c, aLo, 255,
                    "round the letters"));
        }
        if (look.textShadow != null) {
            TextLook.Shadow sh = look.textShadow;
            double e = look.textStroke != null ? look.textStroke.width / 2 * s : 0;
            double dx = sh.dx * s;
            double dy = sh.dy * s;
            Rect r = Rect.of(Math.min(tl, tl + dx) - e, Math.min(tt, tt + dy) - e, Math.max(tr, tr + dx) + e,
                    Math.max(tb, tb + dy) + e, w, h);
            out.add(shadowColour(Check.TEXT_SHADOW, one(r), argb(colors, sh.color, colorSlot, sh.alpha), sh.alpha, sh.blur,
                    fades, "offset " + sign(sh.dx) + " " + sign(sh.dy)));
        }
        if (look.extrude != null && look.extrude.copies > 0) {
            double e = look.extrude.copies * Math.max(Math.abs(look.extrude.dx), Math.abs(look.extrude.dy)) * s;
            out.add(flat(Check.EXTRUDE, Kind.EACH_HAS, one(Rect.of(tl - e, tt - e, tr + e, tb + e, w, h)),
                    argb(colors, look.extrude.color, colorSlot, 1), aLo, 255, "behind the letters"));
        }

        // ---- the box
        if (box != null) {
            final double fl = cl + outer * s;
            final double ft = ct + outer * s;
            final double fr = cr - outer * s;
            final double fb = cb - outer * s;
            final double fw = fr - fl;
            final double fh = fb - ft;
            final double midX = (fl + fr) / 2;
            final double midY = (ft + fb) / 2;
            final double bandX0 = 0.3 * box.padX * s;
            final double bandX1 = 0.8 * box.padX * s;
            final double bandY0 = 0.3 * box.padY * s;
            final double bandY1 = 0.8 * box.padY * s;
            final double halfH = 0.1 * fh;
            final double halfW = 0.1 * fw;
            // The four bands of padding, between the text box and the box edge, away from the rounded corners
            List<Rect> bands = new ArrayList<>();
            bands.add(Rect.of(fl + bandX0, midY - halfH, fl + bandX1, midY + halfH, w, h));
            bands.add(Rect.of(fr - bandX1, midY - halfH, fr - bandX0, midY + halfH, w, h));
            bands.add(Rect.of(midX - halfW, ft + bandY0, midX + halfW, ft + bandY1, w, h));
            bands.add(Rect.of(midX - halfW, fb - bandY1, midX + halfW, fb - bandY0, w, h));
            if ("solid".equals(box.fill.kind)) {
                int c = argb(colors, box.fill.color, colorSlot, box.fill.alpha);
                out.add(new Probe(Check.BOX_FILL, Kind.ALL, bands, Collections.<Rect>emptyList(), true, c, 0, FLAT_TOL,
                        fades ? 0 : (int) Math.round(box.fill.alpha * 255) - 6, 255, false, 0, 0, 0, "four padding bands"));
            } else if ("gradient".equals(box.fill.kind)) {
                int from = argb(colors, box.fill.from, colorSlot, 1);
                int to = argb(colors, box.fill.to, colorSlot, 1);
                Probe p = boxGradientEnds(box.fill.direction, from, to, fl, ft, fw, fh, bandX0, bandX1, bandY0, bandY1,
                        halfW, halfH, w, h, fades);
                if (p != null) out.add(p);
                // Padding stays free of text: no band pixel is near the text colour, when the two are told apart
                if (textSolid && colourDistance(textColor, from) > 70 && colourDistance(textColor, to) > 70) {
                    out.add(new Probe(Check.BOX_FILL, Kind.ALL, bands, Collections.<Rect>emptyList(), false, 0, 0, 0,
                            fades ? 0 : 250, 255, true, textColor, 40, 0, "four padding bands, clear of the text colour"));
                }
            }
            if (box.border != null) {
                double bw = box.border.width * s;
                out.add(ringProbe(Check.BOX_BORDER, argb(colors, box.border.color, colorSlot, 1), fl, ft, fr, fb, 0, bw, w, h,
                        aLo));
                if (box.border.ring != null) {
                    double off = (box.border.width + box.border.ring.gap) * s;
                    out.add(ringProbe(Check.BOX_RING, argb(colors, box.border.ring.color, colorSlot, 1), fl, ft, fr, fb, off,
                            box.border.ring.width * s, w, h, aLo));
                }
            }
            if (box.shadow != null) {
                TextLook.BoxShadow sh = box.shadow;
                double dx = sh.dx * s;
                double dy = sh.dy * s;
                List<Rect> r = new ArrayList<>();
                // Just outside the outline, on the side the shadow is pushed to, inside the shifted outline
                if (Math.abs(sh.dx) >= 0.005) {
                    double near = cr + 0.25 * dx;
                    double far = cr + 0.75 * dx;
                    if (dx < 0) {
                        near = cl + 0.25 * dx;
                        far = cl + 0.75 * dx;
                    }
                    double my0 = (ct + cb) / 2 + dy / 2;
                    r.add(Rect.of(near, my0 - halfH, far, my0 + halfH, w, h));
                }
                if (Math.abs(sh.dy) >= 0.005) {
                    double near = cb + 0.25 * dy;
                    double far = cb + 0.75 * dy;
                    if (dy < 0) {
                        near = ct + 0.25 * dy;
                        far = ct + 0.75 * dy;
                    }
                    double mx0 = (cl + cr) / 2 + dx / 2;
                    r.add(Rect.of(mx0 - halfW, near, mx0 + halfW, far, w, h));
                }
                if (!r.isEmpty()) {
                    out.add(shadowColour(Check.BOX_SHADOW, r, argb(colors, sh.color, colorSlot, sh.alpha), sh.alpha,
                            "hard".equals(sh.style) ? 0 : sh.blur, fades, "offset " + sign(sh.dx) + " " + sign(sh.dy)));
                }
            }
            if (box.glow != null) {
                double reach = Math.max(2, 0.25 * box.glow.blur * s);
                List<Rect> r = new ArrayList<>();
                r.add(Rect.of(cr + 1, midY - halfH, cr + 1 + reach, midY + halfH, w, h));
                r.add(Rect.of(cl - 1 - reach, midY - halfH, cl - 1, midY + halfH, w, h));
                out.add(new Probe(Check.BOX_GLOW, Kind.EACH_HAS, r, Collections.<Rect>emptyList(), true,
                        argb(colors, box.glow.color, colorSlot, 1), 0, 70, 10, 255, false, 0, 0, 0, "left and right of the box"));
            }
        }

        // ---- nothing cut at the picture's own edge (a blur or shadow wider than the room kept for it)
        out.add(clearEdge(Check.SPRITE_EDGE, edgeRects(0, 0, w, h, w, h), "the sprite's outer pixel ring"));
        return out;
    }

    /** The probes that read the whole video frame the painter drew into: the frame edge and the safe rectangle's edge. */
    public static List<Probe> forFrame(int w, int h, double safeX0, double safeX1, double safeY0, double safeY1) {
        List<Probe> out = new ArrayList<>();
        out.add(clearEdge(Check.FRAME_EDGE, edgeRects(0, 0, w, h, w, h), "the frame's outer pixel ring"));
        int sx0 = (int) Math.round(safeX0 * w);
        int sx1 = (int) Math.round(safeX1 * w);
        int sy0 = (int) Math.round(safeY0 * h);
        int sy1 = (int) Math.round(safeY1 * h);
        out.add(clearEdge(Check.SAFE_EDGE, edgeRects(sx0, sy0, sx1, sy1, w, h), "the safe rectangle's boundary lines"));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static List<Rect> one(Rect r) {
        List<Rect> l = new ArrayList<>();
        l.add(r);
        return l;
    }

    /** The one pixel wide lines just inside the rectangle's four edges. */
    static List<Rect> edgeRects(int x0, int y0, int x1, int y1, int w, int h) {
        List<Rect> r = new ArrayList<>();
        r.add(Rect.of(x0, y0, x1, y0 + 1, w, h));
        r.add(Rect.of(x0, y1 - 1, x1, y1, w, h));
        r.add(Rect.of(x0, y0, x0 + 1, y1, w, h));
        r.add(Rect.of(x1 - 1, y0, x1, y1, w, h));
        return r;
    }

    private static Probe clearEdge(Check check, List<Rect> rects, String note) {
        return new Probe(check, Kind.ALL, rects, Collections.<Rect>emptyList(), false, 0, 0, 0, 0, CLEAR_ALPHA_MAX, false, 0, 0,
                0, note);
    }

    private static Probe flat(Check check, Kind kind, List<Rect> rects, int color, int alphaMin, int alphaMax, String note) {
        return new Probe(check, kind, rects, Collections.<Rect>emptyList(), true, color, 0, FLAT_TOL, alphaMin, alphaMax, false,
                0, 0, 0, note);
    }

    /** A shadow: its exact colour when hard and opaque, a loose colour with some alpha when it is soft or see-through. */
    private static Probe shadowColour(Check check, List<Rect> rects, int color, double alpha, double blur, boolean fades,
                                      String note) {
        boolean crisp = blur <= 0 && alpha >= 0.99 && !fades;
        return new Probe(check, Kind.EACH_HAS, rects, Collections.<Rect>emptyList(), true, color, 0, crisp ? FLAT_TOL : 70,
                crisp ? 250 : 10, 255, false, 0, 0, 0, note);
    }

    /** The border (offset 0) or the second ring: a thin band on each of the four sides, mid-way along the side. */
    private static Probe ringProbe(Check check, int color, double fl, double ft, double fr, double fb, double offset,
                                   double width, int w, int h, int aLo) {
        double midX = (fl + fr) / 2;
        double midY = (ft + fb) / 2;
        double tallHalf = 0.1 * (fb - ft);
        double wideHalf = 0.1 * (fr - fl);
        List<Rect> r = new ArrayList<>();
        r.add(Rect.of(fl - offset - width, midY - tallHalf, fl - offset, midY + tallHalf, w, h));
        r.add(Rect.of(fr + offset, midY - tallHalf, fr + offset + width, midY + tallHalf, w, h));
        r.add(Rect.of(midX - wideHalf, ft - offset - width, midX + wideHalf, ft - offset, w, h));
        r.add(Rect.of(midX - wideHalf, fb + offset, midX + wideHalf, fb + offset + width, w, h));
        return flat(check, Kind.EACH_HAS, r, color, aLo, 255, "four sides");
    }

    /** A text gradient: the strip at its start and the strip at its end hold colours near its stops, and differ. */
    private static Probe gradientEnds(Check check, String direction, int from, int to, double x, double y, double gw,
                                      double gh, int w, int h, boolean fades) {
        int delta = colourDistance(from, to);
        if (delta < 16) return null;
        List<Rect> a = new ArrayList<>();
        List<Rect> b = new ArrayList<>();
        double ta;
        double tb;
        if ("ttb".equals(direction)) {
            a.add(Rect.of(x, y, x + gw, y + 0.2 * gh, w, h));
            b.add(Rect.of(x, y + 0.8 * gh, x + gw, y + gh, w, h));
            ta = 0.1;
            tb = 0.9;
        } else if ("diag".equals(direction)) {
            a.add(Rect.of(x, y, x + 0.25 * gw, y + 0.3 * gh, w, h));
            b.add(Rect.of(x + 0.75 * gw, y + 0.7 * gh, x + gw, y + gh, w, h));
            ta = 0.1;
            tb = 0.9;
        } else {
            a.add(Rect.of(x, y, x + 0.2 * gw, y + gh, w, h));
            b.add(Rect.of(x + 0.8 * gw, y, x + gw, y + gh, w, h));
            ta = 0.1;
            tb = 0.9;
        }
        return new Probe(check, Kind.ENDS_DIFFER, a, b, true, PaletteResolver.mix(from, to, ta), PaletteResolver.mix(from, to, tb),
                24 + (int) Math.round(0.25 * delta), fades ? 0 : 250, 255, false, 0, 0, Math.max(10, delta / 4),
                direction + ", start and end strips");
    }

    /** A box gradient: two bands of padding, at the start and the end of the gradient line, each with its exact stop mix. */
    private static Probe boxGradientEnds(String direction, int from, int to, double fl, double ft, double fw, double fh,
                                         double bx0, double bx1, double by0, double by1, double halfW, double halfH, int w, int h,
                                         boolean fades) {
        int delta = colourDistance(from, to);
        if (delta < 16) return null;
        double midX = fl + fw / 2;
        double midY = ft + fh / 2;
        Rect a;
        Rect b;
        double ta;
        double tb;
        if ("ttb".equals(direction)) {
            a = Rect.of(midX - halfW, ft + by0, midX + halfW, ft + by1, w, h);
            b = Rect.of(midX - halfW, ft + fh - by1, midX + halfW, ft + fh - by0, w, h);
            ta = ((ft + (by0 + by1) / 2) - ft) / fh;
            tb = ((ft + fh - (by0 + by1) / 2) - ft) / fh;
        } else {
            a = Rect.of(fl + bx0, midY - halfH, fl + bx1, midY + halfH, w, h);
            b = Rect.of(fl + fw - bx1, midY - halfH, fl + fw - bx0, midY + halfH, w, h);
            double ax = fl + (bx0 + bx1) / 2;
            double bxc = fl + fw - (bx0 + bx1) / 2;
            if ("diag".equals(direction)) {
                ta = diag(ax - fl, midY - ft, fw, fh);
                tb = diag(bxc - fl, midY - ft, fw, fh);
            } else {
                ta = (ax - fl) / fw;
                tb = (bxc - fl) / fw;
            }
        }
        return new Probe(Check.BOX_GRADIENT, Kind.ENDS_DIFFER, one(a), one(b), true, PaletteResolver.mix(from, to, ta),
                PaletteResolver.mix(from, to, tb), 12 + (int) Math.round(0.12 * delta), fades ? 0 : 250, 255, false, 0, 0,
                Math.max(10, delta / 4), direction + ", padding at the two ends");
    }

    /** Where a point of a w by h box falls on the diagonal gradient line (0,0)-(w,h): 0 at the first corner, 1 at the far one. */
    static double diag(double x, double y, double w, double h) {
        return Math.max(0, Math.min(1, (x * w + y * h) / (w * w + h * h)));
    }

    private static int argb(PaletteResolver colors, String role, int slot, double alpha) {
        return PaletteResolver.withAlpha(colors.resolve(role, slot), alpha);
    }

    private static String sign(double v) {
        return v > 0 ? "+" : v < 0 ? "-" : "0";
    }

    /** The largest channel difference between two colours (RGB only). */
    static int colourDistance(int a, int b) {
        return Math.max(Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)),
                Math.max(Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)), Math.abs((a & 0xFF) - (b & 0xFF))));
    }

    // ------------------------------------------------------------------------------------------------ evaluate

    /** Reads the pixels under {@code probe} and decides. */
    public static Result evaluate(Probe probe, PixelSource src) {
        switch (probe.kind) {
            case ALL:
                return all(probe, src);
            case EACH_HAS:
                return eachHas(probe, src);
            default:
                return endsDiffer(probe, src);
        }
    }

    /** Evaluates every probe in order. */
    public static List<Result> evaluateAll(List<Probe> probes, PixelSource src) {
        List<Result> r = new ArrayList<>();
        for (Probe p : probes) r.add(evaluate(p, src));
        return r;
    }

    private static boolean alphaOk(Probe p, int px) {
        int a = px >>> 24;
        return a >= p.alphaMin && a <= p.alphaMax;
    }

    /** The channel distance to {@code want}, easing the tolerance for see-through pixels whose colour is imprecise. */
    private static int distance(int px, int want) {
        int a = px >>> 24;
        int d = colourDistance(px, want);
        return Math.max(0, d - (255 - a) / 4);
    }

    private static boolean matches(Probe p, int px, int want) {
        if (!alphaOk(p, px)) return false;
        if (p.hasColor && distance(px, want) > p.tol) return false;
        return !(p.hasAvoid && distance(px, p.avoid) <= p.avoidTol);
    }

    private static Result all(Probe p, PixelSource src) {
        int checked = 0;
        for (int i = 0; i < p.a.size(); i++) {
            Rect r = clip(p.a.get(i), src);
            if (r.isEmpty()) return new Result(p, false, "region " + (i + 1) + " is outside the picture");
            for (int y = r.y0; y < r.y1; y++) {
                for (int x = r.x0; x < r.x1; x++) {
                    int px = src.argb(x, y);
                    checked++;
                    if (!matches(p, px, p.color)) {
                        return new Result(p, false, "pixel " + x + "," + y + " is " + hex(px) + (p.hasColor ? ", wanted "
                                + hex(p.color) : "") + " (alpha " + p.alphaMin + ".." + p.alphaMax + ")");
                    }
                }
            }
        }
        return new Result(p, true, checked + " pixels ok");
    }

    private static Result eachHas(Probe p, PixelSource src) {
        for (int i = 0; i < p.a.size(); i++) {
            Rect r = clip(p.a.get(i), src);
            if (r.isEmpty()) return new Result(p, false, "region " + (i + 1) + " is outside the picture");
            int best = Integer.MAX_VALUE;
            int bestPx = 0;
            boolean found = false;
            for (int y = r.y0; y < r.y1 && !found; y++) {
                for (int x = r.x0; x < r.x1; x++) {
                    int px = src.argb(x, y);
                    if (matches(p, px, p.color)) {
                        found = true;
                        break;
                    }
                    if (alphaOk(p, px)) {
                        int d = distance(px, p.color);
                        if (d < best) {
                            best = d;
                            bestPx = px;
                        }
                    }
                }
            }
            if (!found) {
                return new Result(p, false, "region " + (i + 1) + " " + r + " has no pixel near " + hex(p.color)
                        + (best == Integer.MAX_VALUE ? " (nothing with that alpha)" : ", closest " + hex(bestPx)));
            }
        }
        return new Result(p, true, p.a.size() + (p.a.size() == 1 ? " region ok" : " regions ok"));
    }

    private static Result endsDiffer(Probe p, PixelSource src) {
        int pa = closest(p, p.a, p.color, src);
        int pb = closest(p, p.b, p.colorB, src);
        if (pa == NONE) return new Result(p, false, "start strip has no opaque pixel");
        if (pb == NONE) return new Result(p, false, "end strip has no opaque pixel");
        int da = distance(pa, p.color);
        int db = distance(pb, p.colorB);
        int apart = colourDistance(pa, pb);
        if (da > p.tol) return new Result(p, false, "start strip closest " + hex(pa) + ", wanted near " + hex(p.color));
        if (db > p.tol) return new Result(p, false, "end strip closest " + hex(pb) + ", wanted near " + hex(p.colorB));
        if (apart < p.minDelta) {
            return new Result(p, false, "start " + hex(pa) + " and end " + hex(pb) + " are the same colour (flat, not a gradient)");
        }
        return new Result(p, true, "start " + hex(pa) + ", end " + hex(pb));
    }

    /** No pixel of the right alpha was found. */
    private static final int NONE = 0x7FFFFFFF;

    private static int closest(Probe p, List<Rect> rects, int want, PixelSource src) {
        int best = Integer.MAX_VALUE;
        int bestPx = NONE;
        for (Rect rect : rects) {
            Rect r = clip(rect, src);
            for (int y = r.y0; y < r.y1; y++) {
                for (int x = r.x0; x < r.x1; x++) {
                    int px = src.argb(x, y);
                    if (!alphaOk(p, px)) continue;
                    int d = distance(px, want);
                    if (d < best) {
                        best = d;
                        bestPx = px;
                    }
                }
            }
        }
        return bestPx;
    }

    private static Rect clip(Rect r, PixelSource src) {
        return new Rect(Math.max(0, r.x0), Math.max(0, r.y0), Math.min(src.width(), r.x1), Math.min(src.height(), r.y1));
    }

    static String hex(int argb) {
        return String.format(Locale.ROOT, "#%08X", argb);
    }
}
