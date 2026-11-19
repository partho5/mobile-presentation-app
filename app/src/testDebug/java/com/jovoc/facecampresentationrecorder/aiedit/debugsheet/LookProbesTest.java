package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.PaletteResolver;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The look sheet's probe geometry, on the JVM. A small reference picture is drawn from a look's data by rectangles (the box
 * outline, the padding rectangle, the border band, the shadow, the glow, the text box), in the contract's layer order, with
 * none of the renderer's code; every probe of every look must pass on it, and each probe must fail when the property it
 * reads is left out. The real bitmaps are the owner's to read on a device; this pins where the probes look.
 */
public class LookProbesTest {

    private EngineContract contract;
    private List<TextLook> looks;
    private PaletteResolver colors;

    @Before
    public void load() throws Exception {
        contract = TestContract.load();
        looks = contract.getTextLooks();
        colors = new PaletteResolver(new Palette("multi", LookSheetGrid.ACCENTS), contract.getPalette());
    }

    // ------------------------------------------------------------------------------------------ the reference picture

    /** An ARGB picture with the fakes the probes read. */
    private static final class Pic implements LookProbes.PixelSource {
        final int w;
        final int h;
        final int[] px;

        Pic(int w, int h) {
            this.w = w;
            this.h = h;
            this.px = new int[w * h];
        }

        @Override
        public int width() {
            return w;
        }

        @Override
        public int height() {
            return h;
        }

        @Override
        public int argb(int x, int y) {
            return px[y * w + x];
        }

        interface Paint {
            int at(int x, int y);
        }

        void rect(double l, double t, double r, double b, Paint paint) {
            int x0 = (int) Math.floor(l + 0.5);
            int y0 = (int) Math.floor(t + 0.5);
            int x1 = (int) Math.floor(r + 0.5);
            int y1 = (int) Math.floor(b + 0.5);
            for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) px[y * w + x] = over(paint.at(x, y), px[y * w + x]);
            }
        }

        void solid(double l, double t, double r, double b, final int color) {
            rect(l, t, r, b, (x, y) -> color);
        }

        /** Source-over of non-premultiplied ARGB colours. */
        static int over(int src, int dst) {
            double sa = (src >>> 24) / 255.0;
            double da = (dst >>> 24) / 255.0;
            double a = sa + da * (1 - sa);
            if (a <= 0) return 0;
            int out = (int) Math.round(a * 255) << 24;
            for (int shift = 16; shift >= 0; shift -= 8) {
                double c = (((src >> shift) & 0xFF) * sa + ((dst >> shift) & 0xFF) * da * (1 - sa)) / a;
                out |= (int) Math.round(c) << shift;
            }
            return out;
        }
    }

    /** The picture and the sprite numbers the probes get. */
    private static final class Model {
        Pic pic;
        LookProbes.Sprite sprite;
        double textL;
        double textT;
        double textR;
        double textB;
        double fillL;
        double fillT;
        double fillR;
        double fillB;
        double outL;
        double outT;
        double outR;
        double outB;
    }

    private int argb(String role, int slot, double alpha) {
        return PaletteResolver.withAlpha(colors.resolve(role, slot), alpha);
    }

    private int lerp(int a, int b, double t) {
        return PaletteResolver.mix(a, b, Math.max(0, Math.min(1, t)));
    }

    /** Draws the look as rectangles, leaving out the layers named in {@code skip}. */
    private Model model(TextLook look, int slot, double s, double textW, double textH, Set<LookProbes.Check> skip) {
        Model m = new Model();
        TextLook.Box box = look.box;
        double outer = box != null ? LookProbes.borderOuter(box) : 0;
        double insetX = box != null ? box.padX + outer : 0;
        double insetY = box != null ? box.padY + outer : 0;
        double contentW = textW + 2 * insetX * s;
        double contentH = textH + 2 * insetY * s;
        double margin = Math.ceil(1.0 * s) + 2;
        int w = (int) Math.ceil(contentW + 2 * margin);
        int h = (int) Math.ceil(contentH + 2 * margin);
        m.pic = new Pic(w, h);
        m.sprite = new LookProbes.Sprite(w, h, (float) contentW, (float) contentH, (float) s);
        double mx = (w - contentW) / 2;
        double my = (h - contentH) / 2;
        m.outL = mx;
        m.outT = my;
        m.outR = mx + contentW;
        m.outB = my + contentH;
        m.fillL = m.outL + outer * s;
        m.fillT = m.outT + outer * s;
        m.fillR = m.outR - outer * s;
        m.fillB = m.outB - outer * s;
        m.textL = m.outL + insetX * s;
        m.textT = m.outT + insetY * s;
        m.textR = m.outR - insetX * s;
        m.textB = m.outB - insetY * s;
        Pic p = m.pic;

        if (box != null) {
            if (box.shadow != null && !skip.contains(LookProbes.Check.BOX_SHADOW)) {
                double a = "hard".equals(box.shadow.style) ? box.shadow.alpha : box.shadow.alpha * 0.7;
                p.solid(m.outL + box.shadow.dx * s, m.outT + box.shadow.dy * s, m.outR + box.shadow.dx * s,
                        m.outB + box.shadow.dy * s, argb(box.shadow.color, slot, a));
            }
            if (box.border != null && box.border.ring != null && !skip.contains(LookProbes.Check.BOX_RING)) {
                p.solid(m.outL, m.outT, m.outR, m.outB, argb(box.border.ring.color, slot, 1));
                double gap = (box.border.width + box.border.ring.gap) * s;
                double clear = box.border.ring.gap * s;
                if (clear > 0) {
                    final double gl = m.fillL - gap;
                    final double gt = m.fillT - gap;
                    final double gr = m.fillR + gap;
                    final double gb = m.fillB + gap;
                    for (int y = (int) Math.floor(gt + 0.5); y < (int) Math.floor(gb + 0.5); y++) {
                        for (int x = (int) Math.floor(gl + 0.5); x < (int) Math.floor(gr + 0.5); x++) p.px[y * p.w + x] = 0;
                    }
                }
            }
            if (box.border != null && !skip.contains(LookProbes.Check.BOX_BORDER)) {
                double bw = box.border.width * s;
                p.solid(m.fillL - bw, m.fillT - bw, m.fillR + bw, m.fillB + bw, argb(box.border.color, slot, 1));
            }
            if ("solid".equals(box.fill.kind)) {
                p.solid(m.fillL, m.fillT, m.fillR, m.fillB, argb(box.fill.color, slot, box.fill.alpha));
            } else if (skip.contains(LookProbes.Check.BOX_GRADIENT)) {
                p.solid(m.fillL, m.fillT, m.fillR, m.fillB, argb(box.fill.from, slot, 1));
            } else {
                final int from = argb(box.fill.from, slot, 1);
                final int to = argb(box.fill.to, slot, 1);
                final String dir = box.fill.direction;
                final double fw = m.fillR - m.fillL;
                final double fh = m.fillB - m.fillT;
                final double fl = m.fillL;
                final double ft = m.fillT;
                p.rect(fl, ft, m.fillR, m.fillB, (x, y) -> {
                    double dx = x + 0.5 - fl;
                    double dy = y + 0.5 - ft;
                    double t = "ttb".equals(dir) ? dy / fh : "diag".equals(dir) ? LookProbes.diag(dx, dy, fw, fh) : dx / fw;
                    return lerp(from, to, t);
                });
            }
            if (box.glow != null && !skip.contains(LookProbes.Check.BOX_GLOW)) {
                double reach = box.glow.blur * s;
                int g = argb(box.glow.color, slot, box.glow.alpha * 0.5);
                p.solid(m.outL - reach, m.outT - reach, m.outR + reach, m.outT, g);
                p.solid(m.outL - reach, m.outB, m.outR + reach, m.outB + reach, g);
                p.solid(m.outL - reach, m.outT, m.outL, m.outB, g);
                p.solid(m.outR, m.outT, m.outR + reach, m.outB, g);
            }
        }
        if (look.extrude != null && !skip.contains(LookProbes.Check.EXTRUDE)) {
            for (int i = look.extrude.copies; i >= 1; i--) {
                p.solid(m.textL + i * look.extrude.dx * s, m.textT + i * look.extrude.dy * s, m.textR + i * look.extrude.dx * s,
                        m.textB + i * look.extrude.dy * s, argb(look.extrude.color, slot, 1));
            }
        }
        double e = look.textStroke != null ? look.textStroke.width / 2 * s : 0;
        if (look.textShadow != null && !skip.contains(LookProbes.Check.TEXT_SHADOW)) {
            p.solid(m.textL + look.textShadow.dx * s - e, m.textT + look.textShadow.dy * s - e,
                    m.textR + look.textShadow.dx * s + e, m.textB + look.textShadow.dy * s + e,
                    argb(look.textShadow.color, slot, look.textShadow.alpha));
        }
        if (look.textStroke != null && !skip.contains(LookProbes.Check.TEXT_STROKE)) {
            p.solid(m.textL - e, m.textT - e, m.textR + e, m.textB + e, argb(look.textStroke.color, slot, 1));
        }
        if ("solid".equals(look.textFill.kind)) {
            if (!skip.contains(LookProbes.Check.TEXT_COLOUR)) {
                p.solid(m.textL, m.textT, m.textR, m.textB, argb(look.textFill.color, slot, 1));
            }
        } else {
            final int from = argb(look.textFill.from, slot, 1);
            final int to = argb(look.textFill.to, slot, 1);
            final String dir = look.textFill.direction;
            final double tw = m.textR - m.textL;
            final double th = m.textB - m.textT;
            final double tl = m.textL;
            final double tt = m.textT;
            final boolean flat = skip.contains(LookProbes.Check.TEXT_GRADIENT);
            p.rect(m.textL, m.textT, m.textR, m.textB, (x, y) -> {
                double dx = x + 0.5 - tl;
                double dy = y + 0.5 - tt;
                double t = "ttb".equals(dir) ? dy / th : "diag".equals(dir) ? LookProbes.diag(dx, dy, tw, th) : dx / tw;
                return flat ? from : lerp(from, to, t);
            });
        }
        return m;
    }

    private static List<LookProbes.Result> failures(List<LookProbes.Result> all) {
        List<LookProbes.Result> f = new ArrayList<>();
        for (LookProbes.Result r : all) if (!r.pass) f.add(r);
        return f;
    }

    private static String describe(List<LookProbes.Result> failed) {
        StringBuilder sb = new StringBuilder();
        for (LookProbes.Result r : failed) sb.append(r.probe.check).append(" ").append(r.probe.note).append(": ").append(r.detail).append("; ");
        return sb.toString();
    }

    private static Set<LookProbes.Check> checksOf(List<LookProbes.Probe> probes) {
        Set<LookProbes.Check> s = EnumSet.noneOf(LookProbes.Check.class);
        for (LookProbes.Probe p : probes) s.add(p.check);
        return s;
    }

    // ------------------------------------------------------------------------------------------------------- tests

    @Test
    public void everyProbeOfEveryLookPassesOnAPictureDrawnFromTheLooksOwnData() {
        double[] sizes = {96, 64, 41.6};
        double[][] texts = {{0.7, 1.2}, {3.2, 1.2}, {5.0, 2.4}};   // in size units: one letter, a word, two lines
        for (TextLook look : looks) {
            for (int slot = 0; slot < 3; slot++) {
                for (double s : sizes) {
                    for (double[] t : texts) {
                        Model m = model(look, slot, s, t[0] * s, t[1] * s, EnumSet.noneOf(LookProbes.Check.class));
                        List<LookProbes.Probe> probes = LookProbes.forSprite(look, colors, slot, m.sprite);
                        List<LookProbes.Result> failed = failures(LookProbes.evaluateAll(probes, m.pic));
                        assertTrue(look.id + " slot " + slot + " s " + s + ": " + describe(failed), failed.isEmpty());
                    }
                }
            }
        }
    }

    @Test
    public void eachLookGetsTheProbesItsPropertiesAsk() {
        for (TextLook look : looks) {
            Model m = model(look, 0, 96, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
            Set<LookProbes.Check> got = checksOf(LookProbes.forSprite(look, colors, 0, m.sprite));
            Set<LookProbes.Check> want = EnumSet.of(LookProbes.Check.SPRITE_EDGE);
            if ("solid".equals(look.textFill.kind)) want.add(LookProbes.Check.TEXT_COLOUR);
            else want.add(LookProbes.Check.TEXT_GRADIENT);
            if (look.textStroke != null) want.add(LookProbes.Check.TEXT_STROKE);
            if (look.textShadow != null) want.add(LookProbes.Check.TEXT_SHADOW);
            if (look.extrude != null) want.add(LookProbes.Check.EXTRUDE);
            if (look.box != null) {
                want.add(LookProbes.Check.BOX_FILL);   // a gradient box also keeps a padding probe: its text colour is far from both stops
                if (!"solid".equals(look.box.fill.kind)) want.add(LookProbes.Check.BOX_GRADIENT);
                if (look.box.border != null) want.add(LookProbes.Check.BOX_BORDER);
                if (look.box.border != null && look.box.border.ring != null) want.add(LookProbes.Check.BOX_RING);
                if (look.box.shadow != null) want.add(LookProbes.Check.BOX_SHADOW);
                if (look.box.glow != null) want.add(LookProbes.Check.BOX_GLOW);
            }
            assertEquals(look.id, want, got);
        }
    }

    @Test
    public void theNamedLooksGetTheirCardProbes() {
        // gradient fill: left and right (or top and bottom) differ
        assertTrue(checksOf(probesFor("gradient")).contains(LookProbes.Check.TEXT_GRADIENT));
        assertTrue(checksOf(probesFor("gradient_block")).contains(LookProbes.Check.BOX_GRADIENT));
        // shadow colour at the shadow offset: the hard block, the soft shadow, the text shadow
        assertTrue(checksOf(probesFor("gradient_block")).contains(LookProbes.Check.BOX_SHADOW));
        assertTrue(checksOf(probesFor("label_pill")).contains(LookProbes.Check.BOX_SHADOW));
        assertTrue(checksOf(probesFor("sticker")).contains(LookProbes.Check.TEXT_SHADOW));
        // stroke colour on the stroke; box holds text plus padding
        assertTrue(checksOf(probesFor("3d")).contains(LookProbes.Check.TEXT_STROKE));
        assertTrue(checksOf(probesFor("flat_rect")).contains(LookProbes.Check.BOX_FILL));
        assertTrue(checksOf(probesFor("tilt_sticker")).contains(LookProbes.Check.BOX_RING));
        assertTrue(checksOf(probesFor("neon_box")).contains(LookProbes.Check.BOX_GLOW));
    }

    private List<LookProbes.Probe> probesFor(String id) {
        TextLook look = contract.textLook(id);
        Model m = model(look, 0, 96, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
        return LookProbes.forSprite(look, colors, 0, m.sprite);
    }

    @Test
    public void aProbeFailsWhenItsPropertyIsNotDrawn() {
        String[][] cases = {
                {"gradient", "TEXT_GRADIENT"}, {"gradient", "TEXT_STROKE"}, {"gradient_block", "BOX_GRADIENT"},
                {"gradient_block", "BOX_BORDER"}, {"gradient_block", "BOX_SHADOW"}, {"tilt_sticker", "BOX_RING"},
                {"neon_box", "BOX_GLOW"}, {"label_pill", "BOX_SHADOW"}, {"3d", "EXTRUDE"}, {"flat_rect", "TEXT_COLOUR"},
                {"sticker", "TEXT_SHADOW"}};
        for (String[] c : cases) {
            TextLook look = contract.textLook(c[0]);
            LookProbes.Check check = LookProbes.Check.valueOf(c[1]);
            if (c[0].equals("sticker")) {
                // the shadow is the stroke's colour: leave out both, as a renderer that drew neither would
                Model m = model(look, 0, 96, 300, 115, EnumSet.of(LookProbes.Check.TEXT_SHADOW, LookProbes.Check.TEXT_STROKE));
                List<LookProbes.Result> failed = failures(LookProbes.evaluateAll(LookProbes.forSprite(look, colors, 0, m.sprite), m.pic));
                assertTrue(c[0] + " " + c[1], containsCheck(failed, check));
                continue;
            }
            Model m = model(look, 0, 96, 300, 115, EnumSet.of(check));
            List<LookProbes.Result> failed = failures(LookProbes.evaluateAll(LookProbes.forSprite(look, colors, 0, m.sprite), m.pic));
            assertTrue(c[0] + " " + c[1] + " missing was not caught: " + describe(failed), containsCheck(failed, check));
        }
    }

    private static boolean containsCheck(List<LookProbes.Result> failed, LookProbes.Check check) {
        for (LookProbes.Result r : failed) if (r.probe.check == check) return true;
        return false;
    }

    @Test
    public void aShadowAtTheWrongOffsetIsCaught() {
        TextLook look = contract.textLook("gradient_block");   // hard block 0.1 s right and 0.1 s down
        Model m = model(look, 0, 96, 300, 115, EnumSet.of(LookProbes.Check.BOX_SHADOW));
        // a shadow drawn at the opposite corner is not at the offset the look asks for
        m.pic.solid(m.outL - 9.6, m.outT - 9.6, m.outR - 9.6, m.outB - 9.6, argb("ink", 0, 1));
        List<LookProbes.Result> failed = failures(LookProbes.evaluateAll(LookProbes.forSprite(look, colors, 0, m.sprite), m.pic));
        assertTrue(describe(failed), containsCheck(failed, LookProbes.Check.BOX_SHADOW));
    }

    @Test
    public void aFlatGradientAndAWrongStopAreCaught() {
        TextLook look = contract.textLook("gradient");
        Model flat = model(look, 0, 96, 300, 115, EnumSet.of(LookProbes.Check.TEXT_GRADIENT));
        List<LookProbes.Result> r = LookProbes.evaluateAll(LookProbes.forSprite(look, colors, 0, flat.sprite), flat.pic);
        LookProbes.Result g = null;
        for (LookProbes.Result x : r) if (x.probe.check == LookProbes.Check.TEXT_GRADIENT) g = x;
        assertFalse(g.detail, g.pass);
    }

    @Test
    public void textTouchingThePaddingFailsTheBoxProbe() {
        TextLook look = contract.textLook("flat_rect");
        Model m = model(look, 0, 96, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
        // ink that spills into the left padding (a text wider than it was measured)
        m.pic.solid(m.fillL + 4, m.textT, m.textL + 10, m.textB, argb("paper", 0, 1));
        List<LookProbes.Result> failed = failures(LookProbes.evaluateAll(LookProbes.forSprite(look, colors, 0, m.sprite), m.pic));
        assertTrue(describe(failed), containsCheck(failed, LookProbes.Check.BOX_FILL));
    }

    @Test
    public void aBlurCutAtThePictureEdgeIsCaught() {
        TextLook look = contract.textLook("neon_box");
        Model m = model(look, 0, 96, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
        m.pic.px[5 * m.pic.w] = 0xFFFF0000;   // an opaque pixel on the left edge
        List<LookProbes.Result> failed = failures(LookProbes.evaluateAll(LookProbes.forSprite(look, colors, 0, m.sprite), m.pic));
        assertTrue(describe(failed), containsCheck(failed, LookProbes.Check.SPRITE_EDGE));
    }

    @Test
    public void boxRectanglesSitWhereThePaddingBorderShadowAndGlowAre() {
        TextLook look = contract.textLook("gradient_block");   // border 0.1, hard shadow 0.1 / 0.1, padding 0.5 / 0.22
        double s = 96;
        Model m = model(look, 0, s, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
        for (LookProbes.Probe p : LookProbes.forSprite(look, colors, 0, m.sprite)) {
            for (LookProbes.Rect r : p.a) {
                assertFalse(p.check + " is empty", r.isEmpty());
                assertTrue(p.check + " " + r, r.x0 >= 0 && r.y0 >= 0 && r.x1 <= m.pic.w && r.y1 <= m.pic.h);
                switch (p.check) {
                    case BOX_FILL:
                        // padding bands: inside the padding rectangle, outside the text box
                        assertTrue(r.toString(), r.x0 >= m.fillL - 1 && r.x1 <= m.fillR + 1 && r.y0 >= m.fillT - 1 && r.y1 <= m.fillB + 1);
                        assertFalse(r.toString(), r.x1 > m.textL + 1 && r.x0 < m.textR - 1 && r.y1 > m.textT + 1 && r.y0 < m.textB - 1);
                        break;
                    case BOX_BORDER:
                        // the border band: outside the padding rectangle, inside the outline
                        assertFalse(r.toString(), r.x0 >= m.fillL && r.x1 <= m.fillR && r.y0 >= m.fillT && r.y1 <= m.fillB);
                        assertTrue(r.toString(), r.x0 >= m.outL - 1 && r.x1 <= m.outR + 1 && r.y0 >= m.outT - 1 && r.y1 <= m.outB + 1);
                        break;
                    case BOX_SHADOW:
                        // beyond the outline on the side the block is pushed to, inside the shifted outline
                        boolean right = r.x0 >= m.outR - 1 && r.x1 <= m.outR + 0.1 * s + 1;
                        boolean below = r.y0 >= m.outB - 1 && r.y1 <= m.outB + 0.1 * s + 1;
                        assertTrue(r.toString(), right || below);
                        break;
                    default:
                        break;
                }
            }
        }
        int shadowRects = 0;
        for (LookProbes.Probe p : LookProbes.forSprite(look, colors, 0, m.sprite)) {
            if (p.check == LookProbes.Check.BOX_SHADOW) shadowRects += p.a.size();
        }
        assertEquals("one strip right of the box and one below it", 2, shadowRects);

        // label_pill's soft shadow is straight down: a single strip, below
        TextLook pill = contract.textLook("label_pill");
        Model pm = model(pill, 0, s, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
        for (LookProbes.Probe p : LookProbes.forSprite(pill, colors, 0, pm.sprite)) {
            if (p.check != LookProbes.Check.BOX_SHADOW) continue;
            assertEquals(1, p.a.size());
            assertTrue(p.a.get(0).toString(), p.a.get(0).y0 >= pm.outB - 1);
        }
    }

    @Test
    public void theTwoRingsAreTwoBandsOnFourSides() {
        TextLook look = contract.textLook("tilt_sticker");   // border 0.06 paper, ring 0.07 accent, gap 0
        Model m = model(look, 0, 96, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
        List<LookProbes.Probe> probes = LookProbes.forSprite(look, colors, 0, m.sprite);
        LookProbes.Probe border = null;
        LookProbes.Probe ring = null;
        for (LookProbes.Probe p : probes) {
            if (p.check == LookProbes.Check.BOX_BORDER) border = p;
            if (p.check == LookProbes.Check.BOX_RING) ring = p;
        }
        assertEquals(4, border.a.size());
        assertEquals(4, ring.a.size());
        // left side: the ring lies further out than the border
        assertTrue(ring.a.get(0).x1 <= border.a.get(0).x0 + 1);
        assertEquals(argb("paper", 0, 1), border.color);
        assertEquals(argb("accent", 0, 1), ring.color);
    }

    @Test
    public void aTextGradientProbeReadsTheStartAndEndOfItsDirection() {
        TextLook look = contract.textLook("gradient");   // ttb
        Model m = model(look, 0, 96, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
        LookProbes.Probe g = null;
        for (LookProbes.Probe p : LookProbes.forSprite(look, colors, 0, m.sprite)) if (p.check == LookProbes.Check.TEXT_GRADIENT) g = p;
        assertEquals(LookProbes.Kind.ENDS_DIFFER, g.kind);
        // top strip, bottom strip, same width: it is top to bottom
        assertTrue(g.a.get(0).y1 <= g.b.get(0).y0);
        assertEquals(g.a.get(0).x0, g.b.get(0).x0);
        assertEquals(g.a.get(0).x1, g.b.get(0).x1);
        int paper = argb("paper", 0, 1);
        int accent = argb("accent", 0, 1);
        assertTrue(LookProbes.colourDistance(g.color, paper) < LookProbes.colourDistance(g.color, accent));
        assertTrue(LookProbes.colourDistance(g.colorB, accent) < LookProbes.colourDistance(g.colorB, paper));
    }

    @Test
    public void frameProbesReadTheFrameEdgeAndTheSafeRectangleEdge() {
        List<LookProbes.Probe> probes = LookProbes.forFrame(1080, 1920, 0.06, 0.94, 0.1, 0.62);
        assertEquals(2, probes.size());
        assertEquals(LookProbes.Check.FRAME_EDGE, probes.get(0).check);
        assertEquals(LookProbes.Check.SAFE_EDGE, probes.get(1).check);
        Pic empty = new Pic(1080, 1920);
        assertTrue(failures(LookProbes.evaluateAll(probes, empty)).isEmpty());
        // safe rectangle: x 65..1015, y 192..1190, as pixel lines just inside it
        LookProbes.Probe safe = probes.get(1);
        assertEquals(4, safe.a.size());
        assertEquals(192, safe.a.get(0).y0);
        assertEquals(1190, safe.a.get(1).y1 - 0);
        assertEquals(65, safe.a.get(2).x0);
        assertEquals(1015, safe.a.get(3).x1);
        // an overlay that reaches the safe edge fails that probe and not the frame edge probe
        Pic touching = new Pic(1080, 1920);
        touching.px[300 * 1080 + 65] = 0xFFFFFFFF;
        List<LookProbes.Result> r = LookProbes.evaluateAll(probes, touching);
        assertTrue(r.get(0).detail, r.get(0).pass);
        assertFalse(r.get(1).detail, r.get(1).pass);
        // one on the frame's own edge fails the frame edge probe
        Pic cut = new Pic(1080, 1920);
        cut.px[10] = 0xFFFFFFFF;
        assertFalse(LookProbes.evaluateAll(probes, cut).get(0).pass);
    }

    @Test
    public void aRectangleIsAtLeastOnePixelAndStaysInsideThePicture() {
        LookProbes.Rect r = LookProbes.Rect.of(10.2, 10.2, 10.3, 10.3, 100, 100);
        assertEquals(1, r.width());
        assertEquals(1, r.height());
        LookProbes.Rect clamped = LookProbes.Rect.of(-20, -5, 400, 300, 100, 50);
        assertEquals(0, clamped.x0);
        assertEquals(0, clamped.y0);
        assertEquals(100, clamped.x1);
        assertEquals(50, clamped.y1);
        // a region wholly outside the picture is empty, and its probe fails instead of passing on nothing
        LookProbes.Rect outside = LookProbes.Rect.of(500, 500, 600, 600, 100, 100);
        assertTrue(outside.isEmpty());
    }

    @Test
    public void aProbeOutsideThePictureFailsInsteadOfPassingOnNothing() {
        List<LookProbes.Rect> out = Collections.singletonList(LookProbes.Rect.of(500, 500, 600, 600, 100, 100));
        LookProbes.Probe p = new LookProbes.Probe(LookProbes.Check.TEXT_COLOUR, LookProbes.Kind.EACH_HAS, out,
                Collections.<LookProbes.Rect>emptyList(), true, 0xFFFF0000, 0, 14, 250, 255, false, 0, 0, 0, "test");
        assertFalse(LookProbes.evaluate(p, new Pic(100, 100)).pass);
    }

    @Test
    public void theDiagonalGradientRunsFromTheFirstCornerToTheFarOne() {
        assertEquals(0.0, LookProbes.diag(0, 0, 200, 100), 1e-9);
        assertEquals(1.0, LookProbes.diag(200, 100, 200, 100), 1e-9);
        assertEquals(0.5, LookProbes.diag(100, 50, 200, 100), 1e-9);
        assertTrue(LookProbes.diag(100, 0, 200, 100) < LookProbes.diag(100, 100, 200, 100));
    }

    @Test
    public void theSameInputsGiveTheSameProbes() {
        for (TextLook look : looks) {
            Model m = model(look, 1, 96, 300, 115, EnumSet.noneOf(LookProbes.Check.class));
            List<LookProbes.Probe> a = LookProbes.forSprite(look, colors, 1, m.sprite);
            List<LookProbes.Probe> b = LookProbes.forSprite(look, colors, 1, m.sprite);
            assertEquals(a.size(), b.size());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i).check, b.get(i).check);
                assertEquals(a.get(i).color, b.get(i).color);
                assertEquals(a.get(i).a.toString(), b.get(i).a.toString());
            }
        }
    }
}
