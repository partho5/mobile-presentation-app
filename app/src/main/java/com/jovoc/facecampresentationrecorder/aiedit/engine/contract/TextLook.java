package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineJson;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * One text look (spec section 13): which parts it draws, as colour roles ({@code accent}, {@code accent_dark},
 * {@code on_accent}, {@code ink}, {@code paper}) and sizes as fractions of the text size. A part the look does
 * not draw is null, exactly as in the server's explicit shape (fill, stroke, shadow, glow, extrude,
 * background, bar, tilt_deg).
 *
 * <p>Since the server's look format grew (contract doc, "Look format, additive") a look also carries the new blocks
 * ({@code text_fill}, {@code text_stroke}, {@code text_shadow}, {@code box}, {@code opacity}, {@code font},
 * {@code case}, {@code letter_spacing}); the painter reads those. A look without them (an older contract, or a test
 * fixture) is read from its legacy fields into the same new shape ({@link #hasNewBlocks} is false), so there is one
 * way to draw. Enum values are kept as written; {@code LookPaintPlan} refuses a value or a key it has no handler for
 * ({@link #unknownKeys}) instead of drawing half a look.
 */
public final class TextLook {

    /** The fill: one colour role, or a vertical gradient between two roles. */
    public static final class Fill {
        /** The role of a plain fill, null for a gradient. */
        public final String role;
        public final String gradientFrom;
        public final String gradientTo;

        Fill(String role, String gradientFrom, String gradientTo) {
            this.role = role;
            this.gradientFrom = gradientFrom;
            this.gradientTo = gradientTo;
        }

        public boolean isGradient() {
            return role == null;
        }
    }

    /** An outline of {@code width} (a fraction of the text size). */
    public static final class Stroke {
        public final String color;
        public final double width;
        /** {@code round}, {@code miter} or {@code bevel}; the legacy stroke is always round. */
        public final String join;

        Stroke(String color, double width, String join) {
            this.color = color;
            this.width = width;
            this.join = join;
        }
    }

    /** A drop shadow; a blur of 0 is a hard shadow. */
    public static final class Shadow {
        public final String color;
        public final double alpha;
        public final double dx;
        public final double dy;
        public final double blur;

        Shadow(String color, double alpha, double dx, double dy, double blur) {
            this.color = color;
            this.alpha = alpha;
            this.dx = dx;
            this.dy = dy;
            this.blur = blur;
        }
    }

    /** An outer glow of blur radius {@code blur}. */
    public static final class Glow {
        public final String color;
        public final double alpha;
        public final double blur;

        Glow(String color, double alpha, double blur) {
            this.color = color;
            this.alpha = alpha;
            this.blur = blur;
        }
    }

    /** A 3D look: {@code copies} copies of the text, each shifted by (dx, dy) from the last. */
    public static final class Extrude {
        public final int copies;
        public final String color;
        public final double dx;
        public final double dy;

        Extrude(int copies, String color, double dx, double dy) {
            this.copies = copies;
            this.color = color;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** A rounded rectangle behind the text; {@code radius} is a fraction of its height, padding of the text size. */
    public static final class Background {
        public final String color;
        public final double alpha;
        public final double radius;
        public final double padX;
        public final double padY;

        Background(String color, double alpha, double radius, double padX, double padY) {
            this.color = color;
            this.alpha = alpha;
            this.radius = radius;
            this.padX = padX;
            this.padY = padY;
        }
    }

    /** A marker bar behind the text, {@code height} a fraction of the line, swept left to right on entry. */
    public static final class Bar {
        public final String color;
        public final double alpha;
        public final double height;

        Bar(String color, double alpha, double height) {
            this.color = color;
            this.alpha = alpha;
            this.height = height;
        }
    }

    /** The new fill block: a solid role, or a gradient with a direction ({@code ltr}, {@code ttb}, {@code diag}). */
    public static final class FillSpec {
        /** {@code solid} or {@code gradient} (anything else is refused by the paint plan). */
        public final String kind;
        public final String color;
        public final String direction;
        public final String from;
        public final String to;
        /** Opacity of a box fill, 1 for a text fill. */
        public final double alpha;

        FillSpec(String kind, String color, String direction, String from, String to, double alpha) {
            this.kind = kind;
            this.color = color;
            this.direction = direction;
            this.from = from;
            this.to = to;
            this.alpha = alpha;
        }
    }

    /** The second ring of a border: drawn {@code gap} outside the border, {@code width} thick. */
    public static final class Ring {
        public final String color;
        public final double width;
        public final double gap;

        Ring(String color, double width, double gap) {
            this.color = color;
            this.width = width;
            this.gap = gap;
        }
    }

    /** The border of a box, drawn outside its padding, with an optional second ring outside that. */
    public static final class Border {
        public final String color;
        public final double width;
        public final Ring ring;

        Border(String color, double width, Ring ring) {
            this.color = color;
            this.width = width;
            this.ring = ring;
        }
    }

    /** The shadow of a box; {@code style} is {@code soft} or {@code hard} (a hard one has blur 0). */
    public static final class BoxShadow {
        public final String style;
        public final String color;
        public final double alpha;
        public final double dx;
        public final double dy;
        public final double blur;

        BoxShadow(String style, String color, double alpha, double dx, double dy, double blur) {
            this.style = style;
            this.color = color;
            this.alpha = alpha;
            this.dx = dx;
            this.dy = dy;
            this.blur = blur;
        }
    }

    /** The neon glow of a box's outline. */
    public static final class BoxGlow {
        public final String color;
        public final double alpha;
        public final double blur;

        BoxGlow(String color, double alpha, double blur) {
            this.color = color;
            this.alpha = alpha;
            this.blur = blur;
        }
    }

    /** The new box block: shape, fill, border, radius (fraction of the box height), padding, shadow, glow. */
    public static final class Box {
        /** {@code pill}, {@code rounded} or {@code sharp}. */
        public final String shape;
        public final FillSpec fill;
        public final Border border;
        public final double radius;
        public final double padX;
        public final double padY;
        public final BoxShadow shadow;
        public final BoxGlow glow;

        Box(String shape, FillSpec fill, Border border, double radius, double padX, double padY, BoxShadow shadow,
            BoxGlow glow) {
            this.shape = shape;
            this.fill = fill;
            this.border = border;
            this.radius = radius;
            this.padX = padX;
            this.padY = padY;
            this.shadow = shadow;
            this.glow = glow;
        }
    }

    /** A system font family ({@code sans}, {@code sans_condensed}, {@code serif}, {@code mono}) and weight 100..900. */
    public static final class Font {
        public final String family;
        public final int weight;

        Font(String family, int weight) {
            this.family = family;
            this.weight = weight;
        }
    }

    public final String id;
    public final Fill fill;
    public final Stroke stroke;
    public final Shadow shadow;
    public final Glow glow;
    public final Extrude extrude;
    public final Background background;
    public final Bar bar;
    /** Min and max tilt in degrees, or null for no tilt. */
    public final double[] tiltDeg;

    /** True when the contract carried the new blocks; false when they were derived from the legacy fields. */
    public final boolean hasNewBlocks;
    public final FillSpec textFill;
    /** Null for no letter stroke. */
    public final Stroke textStroke;
    /** The text's own shadow, null for none (a box's shadow is {@code box.shadow}). */
    public final Shadow textShadow;
    /** Null for a look without a box. */
    public final Box box;
    /** Opacity of the whole overlay, 0..1. */
    public final double opacity;
    public final Font font;
    /** {@code as_written} or {@code upper}. */
    public final String caseMode;
    /** Fraction of the text size. */
    public final double letterSpacing;
    /** Keys of the look (and of its new blocks) this reader does not know, as plain key names; empty when none. */
    public final List<String> unknownKeys;

    private static final Set<String> LOOK_KEYS = new HashSet<>(Arrays.asList("id", "fill", "stroke", "shadow", "glow",
            "extrude", "background", "bar", "tilt_deg", "text_fill", "text_stroke", "text_shadow", "box", "opacity",
            "font", "case", "letter_spacing"));

    private TextLook(JSONObject o) throws JSONException {
        id = o.getString("id");
        Object f = o.get("fill");
        if (f instanceof JSONObject) {
            JSONObject g = (JSONObject) f;
            if (!"vertical".equals(g.getString("gradient"))) throw new JSONException("unknown gradient");
            fill = new Fill(null, g.getString("from"), g.getString("to"));
        } else {
            fill = new Fill(o.getString("fill"), null, null);
        }
        JSONObject s = part(o, "stroke");
        stroke = s == null ? null : new Stroke(s.getString("color"), s.getDouble("width"), "round");
        JSONObject sh = part(o, "shadow");
        shadow = sh == null ? null : new Shadow(sh.getString("color"), sh.getDouble("alpha"),
                sh.getDouble("dx"), sh.getDouble("dy"), sh.getDouble("blur"));
        JSONObject gl = part(o, "glow");
        glow = gl == null ? null : new Glow(gl.getString("color"), gl.getDouble("alpha"), gl.getDouble("blur"));
        JSONObject ex = part(o, "extrude");
        extrude = ex == null ? null : new Extrude(ex.getInt("copies"), ex.getString("color"),
                ex.getDouble("dx"), ex.getDouble("dy"));
        JSONObject bg = part(o, "background");
        background = bg == null ? null : new Background(bg.getString("color"), bg.getDouble("alpha"),
                bg.getDouble("radius"), bg.getDouble("pad_x"), bg.getDouble("pad_y"));
        JSONObject br = part(o, "bar");
        bar = br == null ? null : new Bar(br.getString("color"), br.getDouble("alpha"), br.getDouble("height"));
        if (o.isNull("tilt_deg")) {
            tiltDeg = null;
        } else {
            tiltDeg = EngineJson.doubles(o.getJSONArray("tilt_deg"));
            if (tiltDeg.length != 2) throw new JSONException("tilt needs two numbers");
        }

        List<String> unknown = new ArrayList<>();
        keys(o, "", LOOK_KEYS, unknown);
        hasNewBlocks = o.has("text_fill");
        if (hasNewBlocks) {
            textFill = fillSpec(o.getJSONObject("text_fill"), "text_fill", unknown, false);
            JSONObject ts = part(o, "text_stroke");
            textStroke = ts == null ? null : strokeBlock(ts, unknown);
            JSONObject tsh = part(o, "text_shadow");
            if (tsh != null) keys(tsh, "text_shadow.", set("color", "alpha", "dx", "dy", "blur"), unknown);
            textShadow = tsh == null ? null : new Shadow(tsh.getString("color"), tsh.getDouble("alpha"),
                    tsh.getDouble("dx"), tsh.getDouble("dy"), tsh.getDouble("blur"));
            JSONObject bx = part(o, "box");
            box = bx == null ? null : boxBlock(bx, unknown);
            opacity = o.getDouble("opacity");
            JSONObject fo = o.getJSONObject("font");
            keys(fo, "font.", set("family", "weight"), unknown);
            font = new Font(fo.getString("family"), fo.getInt("weight"));
            caseMode = o.getString("case");
            letterSpacing = o.getDouble("letter_spacing");
        } else {
            // Legacy shape: what installed phones draw, in the new form (a shadow of a look with a background is the
            // box's shadow; case upper; sans 700; no spacing)
            textFill = fill.isGradient() ? new FillSpec("gradient", null, "ttb", fill.gradientFrom, fill.gradientTo, 1)
                    : new FillSpec("solid", fill.role, null, null, null, 1);
            textStroke = stroke == null ? null : new Stroke(stroke.color, stroke.width, "round");
            textShadow = background == null ? shadow : null;
            box = background == null ? null : legacyBox(background, shadow);
            opacity = 1;
            font = new Font("sans", 700);
            caseMode = "upper";
            letterSpacing = 0;
        }
        unknownKeys = Collections.unmodifiableList(unknown);
    }

    private static Set<String> set(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    /** Adds {@code prefix + key} to {@code unknown} for every key of {@code o} not in {@code known}. */
    private static void keys(JSONObject o, String prefix, Set<String> known, List<String> unknown) {
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!known.contains(k)) unknown.add(prefix + k);
        }
    }

    private static FillSpec fillSpec(JSONObject f, String path, List<String> unknown, boolean withAlpha)
            throws JSONException {
        String kind = f.getString("kind");
        if ("solid".equals(kind)) {
            keys(f, path + ".", withAlpha ? set("kind", "color", "alpha") : set("kind", "color"), unknown);
            return new FillSpec(kind, f.getString("color"), null, null, null, withAlpha ? f.getDouble("alpha") : 1);
        }
        if ("gradient".equals(kind)) {
            keys(f, path + ".", withAlpha ? set("kind", "direction", "from", "to", "alpha")
                    : set("kind", "direction", "from", "to"), unknown);
            return new FillSpec(kind, null, f.getString("direction"), f.getString("from"), f.getString("to"),
                    withAlpha ? f.getDouble("alpha") : 1);
        }
        // A fill kind this reader does not know: kept by name so the paint plan refuses the look
        unknown.add(path + ".kind");
        return new FillSpec(kind, null, null, null, null, 1);
    }

    private static Stroke strokeBlock(JSONObject o, List<String> unknown) throws JSONException {
        keys(o, "text_stroke.", set("color", "width", "join"), unknown);
        return new Stroke(o.getString("color"), o.getDouble("width"), o.getString("join"));
    }

    private static Box boxBlock(JSONObject o, List<String> unknown) throws JSONException {
        keys(o, "box.", set("shape", "fill", "border", "radius", "padding", "shadow", "glow"), unknown);
        FillSpec fill = fillSpec(o.getJSONObject("fill"), "box.fill", unknown, true);
        Border border = null;
        JSONObject b = part(o, "border");
        if (b != null) {
            keys(b, "box.border.", set("color", "width", "ring"), unknown);
            Ring ring = null;
            JSONObject r = part(b, "ring");
            if (r != null) {
                keys(r, "box.border.ring.", set("color", "width", "gap"), unknown);
                ring = new Ring(r.getString("color"), r.getDouble("width"), r.getDouble("gap"));
            }
            border = new Border(b.getString("color"), b.getDouble("width"), ring);
        }
        JSONObject pad = o.getJSONObject("padding");
        keys(pad, "box.padding.", set("x", "y"), unknown);
        BoxShadow shadow = null;
        JSONObject sh = part(o, "shadow");
        if (sh != null) {
            keys(sh, "box.shadow.", set("style", "color", "alpha", "dx", "dy", "blur"), unknown);
            shadow = new BoxShadow(sh.getString("style"), sh.getString("color"), sh.getDouble("alpha"),
                    sh.getDouble("dx"), sh.getDouble("dy"), sh.getDouble("blur"));
        }
        BoxGlow glow = null;
        JSONObject gl = part(o, "glow");
        if (gl != null) {
            keys(gl, "box.glow.", set("color", "alpha", "blur"), unknown);
            glow = new BoxGlow(gl.getString("color"), gl.getDouble("alpha"), gl.getDouble("blur"));
        }
        return new Box(o.getString("shape"), fill, border, o.getDouble("radius"), pad.getDouble("x"),
                pad.getDouble("y"), shadow, glow);
    }

    /** The legacy {@code background} (and the legacy shadow that belongs to it) as a box. */
    private static Box legacyBox(Background bg, Shadow shadow) {
        BoxShadow bs = shadow == null ? null : new BoxShadow(shadow.blur > 0 ? "soft" : "hard", shadow.color,
                shadow.alpha, shadow.dx, shadow.dy, shadow.blur);
        return new Box(bg.radius >= 0.5 ? "pill" : "rounded", new FillSpec("solid", bg.color, null, null, null, bg.alpha),
                null, bg.radius, bg.padX, bg.padY, bs, null);
    }

    /** The object of a part, or null when the look does not draw it (the key must still be present). */
    private static JSONObject part(JSONObject o, String key) throws JSONException {
        if (!o.has(key)) throw new JSONException("missing " + key);
        return o.isNull(key) ? null : o.getJSONObject(key);
    }

    static TextLook fromJson(JSONObject o) throws JSONException {
        return new TextLook(o);
    }

    private static TextLook plainLook;

    /**
     * The built-in plain look every overlay falls back to when its own look cannot be drawn completely (gate 3): bold
     * sans text as written, a paper fill and a round stroke in the accent colour, no box, no tilt. It is code, not
     * contract data, so a bad contract cannot break it.
     */
    public static synchronized TextLook plain() {
        if (plainLook == null) {
            try {
                JSONObject stroke = new JSONObject().put("color", "accent").put("width", 0.14);
                JSONObject o = new JSONObject().put("id", "plain").put("fill", "paper").put("stroke", stroke)
                        .put("shadow", JSONObject.NULL).put("glow", JSONObject.NULL).put("extrude", JSONObject.NULL)
                        .put("background", JSONObject.NULL).put("bar", JSONObject.NULL).put("tilt_deg", JSONObject.NULL)
                        .put("text_fill", new JSONObject().put("kind", "solid").put("color", "paper"))
                        .put("text_stroke", new JSONObject().put("color", "accent").put("width", 0.14).put("join", "round"))
                        .put("text_shadow", JSONObject.NULL).put("box", JSONObject.NULL).put("opacity", 1)
                        .put("font", new JSONObject().put("family", "sans").put("weight", 700))
                        .put("case", "as_written").put("letter_spacing", 0);
                plainLook = new TextLook(o);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        return plainLook;
    }
}
