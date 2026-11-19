package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What to draw for one text look, as an ordered list of explicit operations. Pure Java (no Bitmap, Canvas or Paint), so
 * every property of a look is checked on the JVM; {@link TextLookRenderer} only translates the operations to a Canvas.
 *
 * <p>Order, back to front, is the contract's layer order ({@link Layer}): box shadow, box fill, box border (and its
 * second ring), text shadow, text stroke, text fill, glow. Two passes that are not in that list keep the place legacy
 * phones draw them: the 3D copies sit just before the text shadow, and the text glow of a look without a box sits
 * behind the text (see {@link #build}). Lengths are in text-size units (fractions of the size {@code s}), colours are
 * resolved ARGB ints with their alpha applied, gradient lines are fractions of the box they paint. A look with a key or
 * value without a handler is refused with {@link UnsupportedLookException}; nothing is skipped silently.
 *
 * <p><b>Glow decision (C5).</b> The contract's layer order puts {@code glow} last, today's renderer draws the legacy glow
 * behind the text, and the vectors list the {@code glow} layer for the shipped glow look as well as for the neon box.
 * Rule: a look with a {@code box} draws its glows (the box's {@code glow} and the legacy text {@code glow}) as the last
 * layer, as an outer-only blur, so the halo shows outside the letters and outside the box but never tints the text or the
 * box interior. A look without a box (only the shipped glow look) keeps the legacy glow behind the text, so it draws as
 * before. The rule reads data only (is there a box), never a look id.
 */
public final class LookPaintPlan {

    /** The contract's layers, back to front ({@code text_layer_order}). */
    public enum Layer {
        BOX_SHADOW("box_shadow"), BOX_FILL("box_fill"), BOX_BORDER("box_border"), TEXT_SHADOW("text_shadow"),
        TEXT_STROKE("text_stroke"), TEXT_FILL("text_fill"), GLOW("glow");

        /** The id the server uses. */
        public final String id;

        Layer(String id) {
            this.id = id;
        }
    }

    public enum Kind {
        BOX_SHADOW, BOX_FILL, BOX_BORDER, BOX_RING, GLOW_BEHIND_TEXT, EXTRUDE, TEXT_SHADOW, TEXT_STROKE, TEXT_FILL,
        BOX_GLOW, TEXT_GLOW
    }

    /** A gradient line over the box it paints, as fractions of that box from its left and top edge. */
    public static final class Gradient {
        public final double x0;
        public final double y0;
        public final double x1;
        public final double y1;
        public final int from;
        public final int to;

        Gradient(double[] line, int from, int to) {
            this.x0 = line[0];
            this.y0 = line[1];
            this.x1 = line[2];
            this.y1 = line[3];
            this.from = from;
            this.to = to;
        }
    }

    /** A solid colour or a gradient. */
    public static final class FillPaint {
        public final int color;
        /** Null for a solid colour. */
        public final Gradient gradient;

        FillPaint(int color, Gradient gradient) {
            this.color = color;
            this.gradient = gradient;
        }

        public boolean isGradient() {
            return gradient != null;
        }
    }

    /** One draw operation. */
    public abstract static class Op {
        public final Kind kind;
        /** The contract layer, null for the two legacy passes that are not in the contract's list. */
        public final Layer layer;

        Op(Kind kind, Layer layer) {
            this.kind = kind;
            this.layer = layer;
        }
    }

    /** The shadow of the box's whole outline (fill and border), offset by (dx, dy); blur 0 is a hard block. */
    public static final class BoxShadowOp extends Op {
        public final double expand;
        public final double radiusOfHeight;
        public final double dx;
        public final double dy;
        public final double blur;
        public final int color;

        BoxShadowOp(double expand, double radiusOfHeight, double dx, double dy, double blur, int color) {
            super(Kind.BOX_SHADOW, Layer.BOX_SHADOW);
            this.expand = expand;
            this.radiusOfHeight = radiusOfHeight;
            this.dx = dx;
            this.dy = dy;
            this.blur = blur;
            this.color = color;
        }
    }

    /** The box fill, over the padding rectangle (the text box grown by the padding). */
    public static final class BoxFillOp extends Op {
        public final double radiusOfHeight;
        public final FillPaint paint;

        BoxFillOp(double radiusOfHeight, FillPaint paint) {
            super(Kind.BOX_FILL, Layer.BOX_FILL);
            this.radiusOfHeight = radiusOfHeight;
            this.paint = paint;
        }
    }

    /**
     * A stroked outline outside the padding rectangle: its inner edge is {@code offset} outside that rectangle and it is
     * {@code width} thick (the border at offset 0, the second ring after the border and its gap).
     */
    public static final class BoxStrokeOp extends Op {
        public final double offset;
        public final double width;
        public final double radiusOfHeight;
        public final int color;

        BoxStrokeOp(Kind kind, double offset, double width, double radiusOfHeight, int color) {
            super(kind, Layer.BOX_BORDER);
            this.offset = offset;
            this.width = width;
            this.radiusOfHeight = radiusOfHeight;
            this.color = color;
        }
    }

    /** The legacy glow of a look without a box: a blurred copy of the text behind it, drawn {@link #PASSES} times. */
    public static final class GlowBehindOp extends Op {
        public final double blur;
        public final int color;

        GlowBehindOp(double blur, int color) {
            super(Kind.GLOW_BEHIND_TEXT, null);
            this.blur = blur;
            this.color = color;
        }
    }

    /** The 3D copies of the text, the farthest first. */
    public static final class ExtrudeOp extends Op {
        public final int copies;
        public final double dx;
        public final double dy;
        public final int color;

        ExtrudeOp(int copies, double dx, double dy, int color) {
            super(Kind.EXTRUDE, null);
            this.copies = copies;
            this.dx = dx;
            this.dy = dy;
            this.color = color;
        }
    }

    /** The text's shadow; {@code strokeWidth} above 0 means the stroked letters cast it too. */
    public static final class TextShadowOp extends Op {
        public final double dx;
        public final double dy;
        public final double blur;
        public final int color;
        public final double strokeWidth;
        public final String strokeJoin;

        TextShadowOp(double dx, double dy, double blur, int color, double strokeWidth, String strokeJoin) {
            super(Kind.TEXT_SHADOW, Layer.TEXT_SHADOW);
            this.dx = dx;
            this.dy = dy;
            this.blur = blur;
            this.color = color;
            this.strokeWidth = strokeWidth;
            this.strokeJoin = strokeJoin;
        }
    }

    public static final class TextStrokeOp extends Op {
        public final double width;
        public final String join;
        public final int color;

        TextStrokeOp(double width, String join, int color) {
            super(Kind.TEXT_STROKE, Layer.TEXT_STROKE);
            this.width = width;
            this.join = join;
            this.color = color;
        }
    }

    /** The letters' fill; a gradient spans the measured text box. */
    public static final class TextFillOp extends Op {
        public final FillPaint paint;

        TextFillOp(FillPaint paint) {
            super(Kind.TEXT_FILL, Layer.TEXT_FILL);
            this.paint = paint;
        }
    }

    /** The glow around the box's outline: an outer-only blur, drawn after the text. */
    public static final class BoxGlowOp extends Op {
        public final double expand;
        public final double radiusOfHeight;
        public final double blur;
        public final int color;

        BoxGlowOp(double expand, double radiusOfHeight, double blur, int color) {
            super(Kind.BOX_GLOW, Layer.GLOW);
            this.expand = expand;
            this.radiusOfHeight = radiusOfHeight;
            this.blur = blur;
            this.color = color;
        }
    }

    /** The legacy glow of a look with a box: an outer-only blur of the letters, drawn after the text. */
    public static final class TextGlowOp extends Op {
        public final double blur;
        public final int color;

        TextGlowOp(double blur, int color) {
            super(Kind.TEXT_GLOW, Layer.GLOW);
            this.blur = blur;
            this.color = color;
        }
    }

    /** The highlighter bar: not drawn into the sprite (the animator sweeps it), one per line behind the text. */
    public static final class Bar {
        /** How far the bar sticks out of the line on each side, in text-size units. */
        public static final double OVERHANG = 0.1;
        /** How far down the line the bar starts, as a fraction of the line's height. */
        public static final double TOP_AT = 0.40;
        public final int color;
        /** The bar's height as a fraction of the line's height. */
        public final double heightOfLine;

        Bar(int color, double heightOfLine) {
            this.color = color;
            this.heightOfLine = heightOfLine;
        }
    }

    /** What a look takes around the text box, before any layout: how far the box reaches, and the room for effects. */
    public static final class Room {
        /** The padding plus the border and ring, per side, in text-size units. */
        public final double insetX;
        public final double insetY;
        /** The room kept around the content for strokes, shadows and glows, in text-size units (before rounding up). */
        public final double effect;

        Room(double insetX, double insetY, double effect) {
            this.insetX = insetX;
            this.insetY = insetY;
            this.effect = effect;
        }
    }

    /** How many times a glow is drawn, so it reads on a busy picture. */
    public static final int PASSES = 2;

    private static final String[] JOINS = {"round", "miter", "bevel"};

    /** The operations, in draw order. */
    public final List<Op> ops;
    /** The padding of the box around the text box, per side, in text-size units; 0 when there is no box. */
    public final double padX;
    public final double padY;
    /** The border and ring outside the padding, per side; 0 without a border. */
    public final double borderOuter;
    /** The room for effects (see {@link Room#effect}). */
    public final double effect;
    /** Opacity of the whole overlay, 0..1. */
    public final double opacity;
    /** True when the text is drawn in capitals ({@code case: upper}); false for as written. */
    public final boolean upper;
    public final String fontFamily;
    public final int fontWeight;
    /** In text-size units; the painter uses 0 for scripts that join or reorder letters. */
    public final double letterSpacing;
    /** Null for a look without a marker bar. */
    public final Bar bar;
    /** From the layout result: the text size in pixels, the number of lines and the paragraph direction. */
    public final float sizePx;
    public final int lines;
    public final boolean rtl;

    private LookPaintPlan(List<Op> ops, Room room, double padX, double padY, double borderOuter, TextLook look, Bar bar,
                          TextLayoutPolicy.Result fit) {
        this.ops = Collections.unmodifiableList(ops);
        this.padX = padX;
        this.padY = padY;
        this.borderOuter = borderOuter;
        this.effect = room.effect;
        this.opacity = look.opacity;
        this.upper = "upper".equals(look.caseMode);
        this.fontFamily = look.font.family;
        this.fontWeight = look.font.weight;
        this.letterSpacing = look.letterSpacing;
        this.bar = bar;
        this.sizePx = fit.size;
        this.lines = fit.lines.size();
        this.rtl = fit.rtl;
    }

    /**
     * What the look takes around its text, known before the text is measured (the policy needs it to size the lines).
     *
     * @throws UnsupportedLookException when the look has a field without a handler
     */
    public static Room room(TextLook look) throws UnsupportedLookException {
        validate(look);
        double padX = 0;
        double padY = 0;
        double outer = 0;
        double m = 0;
        TextLook.Box box = look.box;
        if (box != null) {
            padX = box.padX;
            padY = box.padY;
            outer = borderOuter(box);
            if (box.shadow != null) {
                m = Math.max(m, Math.max(Math.abs(box.shadow.dx), Math.abs(box.shadow.dy)) + box.shadow.blur * 1.5);
            }
            if (box.glow != null) m = Math.max(m, box.glow.blur * 1.6);
        }
        if (look.textStroke != null) m = Math.max(m, look.textStroke.width / 2);
        if (look.textShadow != null) {
            m = Math.max(m, Math.max(Math.abs(look.textShadow.dx), Math.abs(look.textShadow.dy))
                    + look.textShadow.blur * 1.5);
        }
        if (look.glow != null) m = Math.max(m, look.glow.blur * 1.6);
        if (look.extrude != null) {
            m = Math.max(m, look.extrude.copies * Math.max(Math.abs(look.extrude.dx), Math.abs(look.extrude.dy)));
        }
        return new Room(padX + outer, padY + outer, m);
    }

    /**
     * The plan for {@code look}, its colours resolved for {@code colorSlot}, over the text the policy laid out.
     *
     * @throws UnsupportedLookException when the look has a field without a handler
     */
    public static LookPaintPlan build(TextLook look, PaletteResolver colors, int colorSlot, TextLayoutPolicy.Result fit)
            throws UnsupportedLookException {
        Room room = room(look);
        List<Op> ops = new ArrayList<>();
        TextLook.Box box = look.box;
        double outer = box != null ? borderOuter(box) : 0;
        double radius = box != null ? Math.max(0, Math.min(0.5, box.radius)) : 0;

        if (box != null) {
            if (box.shadow != null) {
                TextLook.BoxShadow sh = box.shadow;
                ops.add(new BoxShadowOp(outer, radius, sh.dx, sh.dy, "hard".equals(sh.style) ? 0 : sh.blur,
                        color(colors, sh.color, colorSlot, sh.alpha)));
            }
            ops.add(new BoxFillOp(radius, paint(box.fill, colors, colorSlot, box.fill.alpha)));
            if (box.border != null) {
                TextLook.Border b = box.border;
                ops.add(new BoxStrokeOp(Kind.BOX_BORDER, 0, b.width, radius, color(colors, b.color, colorSlot, 1)));
                if (b.ring != null) {
                    ops.add(new BoxStrokeOp(Kind.BOX_RING, b.width + b.ring.gap, b.ring.width, radius,
                            color(colors, b.ring.color, colorSlot, 1)));
                }
            }
        }
        boolean glowLast = box != null;
        if (look.glow != null && !glowLast) {
            ops.add(new GlowBehindOp(look.glow.blur, color(colors, look.glow.color, colorSlot, look.glow.alpha)));
        }
        if (look.extrude != null) {
            ops.add(new ExtrudeOp(look.extrude.copies, look.extrude.dx, look.extrude.dy,
                    color(colors, look.extrude.color, colorSlot, 1)));
        }
        if (look.textShadow != null) {
            TextLook.Shadow sh = look.textShadow;
            ops.add(new TextShadowOp(sh.dx, sh.dy, sh.blur, color(colors, sh.color, colorSlot, sh.alpha),
                    look.textStroke != null ? look.textStroke.width : 0,
                    look.textStroke != null ? look.textStroke.join : "round"));
        }
        if (look.textStroke != null) {
            ops.add(new TextStrokeOp(look.textStroke.width, look.textStroke.join,
                    color(colors, look.textStroke.color, colorSlot, 1)));
        }
        ops.add(new TextFillOp(paint(look.textFill, colors, colorSlot, 1)));
        if (glowLast) {
            if (box.glow != null) {
                ops.add(new BoxGlowOp(outer, radius, box.glow.blur, color(colors, box.glow.color, colorSlot, box.glow.alpha)));
            }
            if (look.glow != null) {
                ops.add(new TextGlowOp(look.glow.blur, color(colors, look.glow.color, colorSlot, look.glow.alpha)));
            }
        }
        Bar bar = look.bar == null ? null
                : new Bar(color(colors, look.bar.color, colorSlot, look.bar.alpha), look.bar.height);
        return new LookPaintPlan(ops, room, box != null ? box.padX : 0, box != null ? box.padY : 0, outer, look, bar,
                fit);
    }

    /** The border and the second ring outside the padding, per side (0 without a border). */
    private static double borderOuter(TextLook.Box box) {
        if (box.border == null) return 0;
        double w = box.border.width;
        if (box.border.ring != null) w += box.border.ring.gap + box.border.ring.width;
        return w;
    }

    /**
     * The corner radius in pixels of an outline {@code expand} text-size units outside a padding rectangle {@code fillHeightPx}
     * tall, for a corner of {@code radiusOfHeight} of that height: it follows the fill's corner (a sharp box stays sharp).
     */
    public static float cornerRadiusPx(double radiusOfHeight, double expand, float fillHeightPx, float sizePx) {
        if (radiusOfHeight <= 0) return 0;
        return (float) (radiusOfHeight * fillHeightPx + expand * sizePx);
    }

    /**
     * The gradient line of {@code direction} as {@code {x0, y0, x1, y1}}, fractions of the painted box: {@code ltr} runs left
     * to right along the middle, {@code ttb} top to bottom, {@code diag} from the top left corner to the bottom right one.
     */
    public static double[] gradientLine(String direction) throws UnsupportedLookException {
        if ("ltr".equals(direction)) return new double[]{0, 0, 1, 0};
        if ("ttb".equals(direction)) return new double[]{0, 0, 0, 1};
        if ("diag".equals(direction)) return new double[]{0, 0, 1, 1};
        throw new UnsupportedLookException("gradient_direction");
    }

    private static int color(PaletteResolver colors, String role, int slot, double alpha) {
        return PaletteResolver.withAlpha(colors.resolve(role, slot), alpha);
    }

    private static FillPaint paint(TextLook.FillSpec f, PaletteResolver colors, int slot, double alpha)
            throws UnsupportedLookException {
        if ("solid".equals(f.kind)) return new FillPaint(color(colors, f.color, slot, alpha), null);
        return new FillPaint(0, new Gradient(gradientLine(f.direction), color(colors, f.from, slot, alpha),
                color(colors, f.to, slot, alpha)));
    }

    /** Refuses a look with a key, enum value or number the plan has no handler for. */
    private static void validate(TextLook look) throws UnsupportedLookException {
        if (!look.unknownKeys.isEmpty()) throw new UnsupportedLookException(look.unknownKeys.get(0));
        fillKind(look.textFill, "text_fill");
        if (look.textStroke != null) oneOf(look.textStroke.join, JOINS, "text_stroke.join");
        if (look.box != null) {
            TextLook.Box b = look.box;
            oneOf(b.shape, new String[]{"pill", "rounded", "sharp"}, "box.shape");
            fillKind(b.fill, "box.fill");
            if (b.shadow != null) oneOf(b.shadow.style, new String[]{"soft", "hard"}, "box.shadow.style");
        }
        if (!(look.opacity >= 0 && look.opacity <= 1)) throw new UnsupportedLookException("opacity");
        // An unknown family is not refused: LookTypography draws sans and the painter notes property_not_drawn
        if (look.font.weight < 100 || look.font.weight > 900) throw new UnsupportedLookException("font.weight");
        oneOf(look.caseMode, new String[]{"as_written", "upper"}, "case");
        if (Double.isNaN(look.letterSpacing) || Double.isInfinite(look.letterSpacing)) {
            throw new UnsupportedLookException("letter_spacing");
        }
    }

    private static void fillKind(TextLook.FillSpec f, String field) throws UnsupportedLookException {
        if ("solid".equals(f.kind)) return;
        if (!"gradient".equals(f.kind)) throw new UnsupportedLookException(field + ".kind");
        gradientLine(f.direction);
    }

    private static void oneOf(String value, String[] allowed, String field) throws UnsupportedLookException {
        for (String a : allowed) if (a.equals(value)) return;
        throw new UnsupportedLookException(field);
    }
}
