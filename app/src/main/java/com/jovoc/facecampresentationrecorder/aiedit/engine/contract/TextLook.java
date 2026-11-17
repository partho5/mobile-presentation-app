package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

import com.jovoc.facecampresentationrecorder.aiedit.engine.EngineJson;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One text look (spec section 13): which parts it draws, as colour roles ({@code accent}, {@code accent_dark},
 * {@code on_accent}, {@code ink}, {@code paper}) and sizes as fractions of the text size. A part the look does
 * not draw is null, exactly as in the server's explicit shape (fill, stroke, shadow, glow, extrude,
 * background, bar, tilt_deg).
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

        Stroke(String color, double width) {
            this.color = color;
            this.width = width;
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
        stroke = s == null ? null : new Stroke(s.getString("color"), s.getDouble("width"));
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
    }

    /** The object of a part, or null when the look does not draw it (the key must still be present). */
    private static JSONObject part(JSONObject o, String key) throws JSONException {
        if (!o.has(key)) throw new JSONException("missing " + key);
        return o.isNull(key) ? null : o.getJSONObject(key);
    }

    static TextLook fromJson(JSONObject o) throws JSONException {
        return new TextLook(o);
    }
}
