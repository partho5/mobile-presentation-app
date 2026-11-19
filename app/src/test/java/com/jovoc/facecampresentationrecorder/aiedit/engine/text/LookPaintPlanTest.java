package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.TestStrings;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The pure paint plan: every property of a look is an operation with the right numbers, in the contract's layer order. */
public class LookPaintPlanTest {

    private static final String[] SHIPPED = {"sticker", "label_pill", "3d", "glow", "gradient", "highlighter"};
    private static final TextLayoutPolicy.Result FIT =
            new TextLayoutPolicy.Result(false, 96f, Arrays.asList("SAVE TIME"), false, false);

    private static final String ACCENT = "#FF8040";

    private static EngineContract contract() throws Exception {
        return TestContract.load();
    }

    private static PaletteResolver colors(EngineContract c) {
        return new PaletteResolver(new Palette("multi", Arrays.asList(ACCENT, "#2979FF")), c.getPalette());
    }

    private static LookPaintPlan plan(EngineContract c, String id) throws Exception {
        TextLook look = c.textLook(id);
        assertNotNull(id, look);
        return LookPaintPlan.build(look, colors(c), 0, FIT);
    }

    @SuppressWarnings("unchecked")
    private static <T extends LookPaintPlan.Op> T op(LookPaintPlan p, LookPaintPlan.Kind kind) {
        for (LookPaintPlan.Op o : p.ops) if (o.kind == kind) return (T) o;
        return null;
    }

    private static List<LookPaintPlan.Kind> kinds(LookPaintPlan p) {
        List<LookPaintPlan.Kind> k = new ArrayList<>();
        for (LookPaintPlan.Op o : p.ops) k.add(o.kind);
        return k;
    }

    /** The contract with the look {@code id} edited by {@code edit}. */
    private interface Edit {
        void apply(JSONObject look) throws Exception;
    }

    private static EngineContract withLook(String id, Edit edit) throws Exception {
        JSONObject o = new JSONObject(TestContract.json());
        JSONArray a = o.getJSONArray("text_styles");
        for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).getString("id"))) edit.apply(a.getJSONObject(i));
        return EngineContract.fromJson(o.toString());
    }

    /** The contract as an older server wrote it: no new blocks on any look. */
    private static EngineContract legacyContract() throws Exception {
        JSONObject o = new JSONObject(TestContract.json());
        JSONArray a = o.getJSONArray("text_styles");
        for (int i = 0; i < a.length(); i++) {
            JSONObject l = a.getJSONObject(i);
            for (String k : new String[]{"text_fill", "text_stroke", "text_shadow", "box", "opacity", "font", "case",
                    "letter_spacing"}) {
                l.remove(k);
            }
        }
        return EngineContract.fromJson(o.toString());
    }

    /** Every number and colour of a plan as one string, so two plans compare. */
    private static String describe(LookPaintPlan p) {
        StringBuilder b = new StringBuilder();
        b.append(p.padX).append('|').append(p.padY).append('|').append(p.borderOuter).append('|').append(p.effect)
                .append('|').append(p.opacity).append('|').append(p.upper).append('|').append(p.fontFamily).append(p.fontWeight)
                .append('|').append(p.letterSpacing).append('|').append(p.bar == null ? "nobar" : p.bar.color + "," + p.bar.heightOfLine);
        for (LookPaintPlan.Op o : p.ops) {
            b.append('\n').append(o.kind).append(' ').append(o.layer);
            for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
                try {
                    Object v = f.get(o);
                    if (v instanceof LookPaintPlan.FillPaint) {
                        LookPaintPlan.FillPaint fp = (LookPaintPlan.FillPaint) v;
                        v = fp.isGradient() ? fp.gradient.x0 + "," + fp.gradient.y0 + "," + fp.gradient.x1 + ","
                                + fp.gradient.y1 + "," + fp.gradient.from + "," + fp.gradient.to : "c" + fp.color;
                    }
                    b.append(' ').append(f.getName()).append('=').append(v);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return b.toString();
    }

    // ---- the six shipped looks: every property is an operation ----

    @Test
    public void stickerHasStrokeHardShadowAndSolidFill() throws Exception {
        EngineContract c = contract();
        LookPaintPlan p = plan(c, "sticker");
        assertEquals(Arrays.asList(LookPaintPlan.Kind.TEXT_SHADOW, LookPaintPlan.Kind.TEXT_STROKE,
                LookPaintPlan.Kind.TEXT_FILL), kinds(p));
        LookPaintPlan.TextShadowOp sh = op(p, LookPaintPlan.Kind.TEXT_SHADOW);
        assertEquals(0.05, sh.dx, 0.0);
        assertEquals(0.06, sh.dy, 0.0);
        assertEquals(0.0, sh.blur, 0.0);
        assertEquals(0xFF111111, sh.color);
        assertEquals(0.14, sh.strokeWidth, 0.0);   // the stroked letters cast the shadow too
        LookPaintPlan.TextStrokeOp st = op(p, LookPaintPlan.Kind.TEXT_STROKE);
        assertEquals(0.14, st.width, 0.0);
        assertEquals("round", st.join);
        assertEquals(0xFF111111, st.color);
        LookPaintPlan.TextFillOp fill = op(p, LookPaintPlan.Kind.TEXT_FILL);
        assertFalse(fill.paint.isGradient());
        assertEquals(0xFFFF8040, fill.paint.color);
        assertTrue(p.upper);
        assertEquals(1.0, p.opacity, 0.0);
    }

    @Test
    public void labelPillHasASoftBoxShadowAndAPillFillAndNoTextShadow() throws Exception {
        LookPaintPlan p = plan(contract(), "label_pill");
        assertEquals(Arrays.asList(LookPaintPlan.Kind.BOX_SHADOW, LookPaintPlan.Kind.BOX_FILL,
                LookPaintPlan.Kind.TEXT_FILL), kinds(p));
        LookPaintPlan.BoxShadowOp sh = op(p, LookPaintPlan.Kind.BOX_SHADOW);
        assertEquals(0.0, sh.dx, 0.0);
        assertEquals(0.04, sh.dy, 0.0);
        assertEquals(0.08, sh.blur, 0.0);
        assertEquals(0.0, sh.expand, 0.0);
        assertEquals(PaletteResolver.withAlpha(0xFF111111, 0.35), sh.color);
        LookPaintPlan.BoxFillOp box = op(p, LookPaintPlan.Kind.BOX_FILL);
        assertEquals(0.5, box.radiusOfHeight, 0.0);
        assertEquals(0xFFFF8040, box.paint.color);
        assertEquals(0.35, p.padX, 0.0);
        assertEquals(0.2, p.padY, 0.0);
        assertEquals(0.0, p.borderOuter, 0.0);
        // on_accent on this orange: the better of ink and paper
        LookPaintPlan.TextFillOp fill = op(p, LookPaintPlan.Kind.TEXT_FILL);
        assertEquals(colors(contract()).resolve("on_accent", 0), fill.paint.color);
    }

    @Test
    public void threeDHasCopiesBeforeTheStrokeAndFill() throws Exception {
        LookPaintPlan p = plan(contract(), "3d");
        assertEquals(Arrays.asList(LookPaintPlan.Kind.EXTRUDE, LookPaintPlan.Kind.TEXT_STROKE,
                LookPaintPlan.Kind.TEXT_FILL), kinds(p));
        LookPaintPlan.ExtrudeOp ex = op(p, LookPaintPlan.Kind.EXTRUDE);
        assertEquals(6, ex.copies);
        assertEquals(0.012, ex.dx, 0.0);
        assertEquals(0.016, ex.dy, 0.0);
        assertEquals(PaletteResolver.darken(0xFFFF8040, 0.55), ex.color);
        assertNull(ex.layer);
    }

    @Test
    public void glowLookKeepsTheLegacyGlowBehindTheText() throws Exception {
        LookPaintPlan p = plan(contract(), "glow");
        assertEquals(Arrays.asList(LookPaintPlan.Kind.GLOW_BEHIND_TEXT, LookPaintPlan.Kind.TEXT_STROKE,
                LookPaintPlan.Kind.TEXT_FILL), kinds(p));
        LookPaintPlan.GlowBehindOp g = op(p, LookPaintPlan.Kind.GLOW_BEHIND_TEXT);
        assertEquals(0.35, g.blur, 0.0);
        assertEquals(PaletteResolver.withAlpha(0xFFFF8040, 0.85), g.color);
        assertEquals(0.35 * 1.6, p.effect, 1e-9);
        assertEquals(0xFFFFFFFF, ((LookPaintPlan.TextFillOp) op(p, LookPaintPlan.Kind.TEXT_FILL)).paint.color);
    }

    @Test
    public void gradientLookRunsTopToBottomOverTheText() throws Exception {
        LookPaintPlan p = plan(contract(), "gradient");
        LookPaintPlan.TextFillOp fill = op(p, LookPaintPlan.Kind.TEXT_FILL);
        assertTrue(fill.paint.isGradient());
        LookPaintPlan.Gradient g = fill.paint.gradient;
        assertEquals(0, g.x0 - g.x1, 0.0);
        assertEquals(0.0, g.y0, 0.0);
        assertEquals(1.0, g.y1, 0.0);
        assertEquals(0xFFFFFFFF, g.from);
        assertEquals(0xFFFF8040, g.to);
        assertEquals(0.1, ((LookPaintPlan.TextStrokeOp) op(p, LookPaintPlan.Kind.TEXT_STROKE)).width, 0.0);
    }

    @Test
    public void highlighterHasAMarkerBarAndNoStrokeOrShadow() throws Exception {
        LookPaintPlan p = plan(contract(), "highlighter");
        assertEquals(Arrays.asList(LookPaintPlan.Kind.TEXT_FILL), kinds(p));
        assertNotNull(p.bar);
        assertEquals(0.55, p.bar.heightOfLine, 0.0);
        assertEquals(PaletteResolver.withAlpha(0xFFFF8040, 0.85), p.bar.color);
        assertEquals(0xFF111111, ((LookPaintPlan.TextFillOp) op(p, LookPaintPlan.Kind.TEXT_FILL)).paint.color);
    }

    @Test
    public void theSixShippedLooksPlanTheSameFromTheNewBlocksAndFromTheLegacyFields() throws Exception {
        EngineContract now = contract();
        EngineContract old = legacyContract();
        for (String id : SHIPPED) {
            assertTrue(id, now.textLook(id).hasNewBlocks);
            assertFalse(id, old.textLook(id).hasNewBlocks);
            assertEquals(id, describe(plan(old, id)), describe(plan(now, id)));
            assertEquals(id, LookPaintPlan.room(old.textLook(id)).effect, LookPaintPlan.room(now.textLook(id)).effect, 0.0);
        }
    }

    @Test
    public void theEffectRoomOfTheShippedLooksIsWhatTheOldRendererKept() throws Exception {
        EngineContract c = contract();
        // old margin(): stroke/2, shadow offset + 1.5 blur, glow 1.6 blur, copies x offset
        assertEquals(0.07, LookPaintPlan.room(c.textLook("sticker")).effect, 1e-9);        // max(.07, .06 + 0)
        assertEquals(0.04 + 0.08 * 1.5, LookPaintPlan.room(c.textLook("label_pill")).effect, 1e-9);
        assertEquals(6 * 0.016, LookPaintPlan.room(c.textLook("3d")).effect, 1e-9);
        assertEquals(0.0, LookPaintPlan.room(c.textLook("highlighter")).effect, 0.0);
        assertEquals(0.35, LookPaintPlan.room(c.textLook("label_pill")).insetX, 0.0);
        assertEquals(0.2, LookPaintPlan.room(c.textLook("label_pill")).insetY, 0.0);
        assertEquals(0.0, LookPaintPlan.room(c.textLook("sticker")).insetX, 0.0);
    }

    // ---- the new looks ----

    @Test
    public void neonBoxDrawsBoxBorderTextThenBothGlowsOuterAndLast() throws Exception {
        EngineContract c = contract();
        LookPaintPlan p = plan(c, "neon_box");
        assertEquals(Arrays.asList(LookPaintPlan.Kind.BOX_FILL, LookPaintPlan.Kind.BOX_BORDER,
                LookPaintPlan.Kind.TEXT_FILL, LookPaintPlan.Kind.BOX_GLOW, LookPaintPlan.Kind.TEXT_GLOW), kinds(p));
        LookPaintPlan.BoxStrokeOp border = op(p, LookPaintPlan.Kind.BOX_BORDER);
        assertEquals(0.06, border.width, 0.0);
        assertEquals(0.0, border.offset, 0.0);
        assertEquals(0xFFFF8040, border.color);
        assertEquals(0.06, p.borderOuter, 0.0);
        assertEquals(0.45, p.padX, 0.0);
        LookPaintPlan.BoxGlowOp bg = op(p, LookPaintPlan.Kind.BOX_GLOW);
        assertEquals(0.35, bg.blur, 0.0);
        assertEquals(0.06, bg.expand, 0.0);          // around the whole outline, border included
        assertEquals(PaletteResolver.withAlpha(0xFFFF8040, 0.8), bg.color);
        LookPaintPlan.TextGlowOp tg = op(p, LookPaintPlan.Kind.TEXT_GLOW);
        assertEquals(0.25, tg.blur, 0.0);
        assertEquals(PaletteResolver.withAlpha(0xFFFF8040, 0.7), tg.color);
        // accent_light text: accent mixed with 65 percent paper
        assertEquals(PaletteResolver.mix(0xFFFF8040, 0xFFFFFFFF, 0.65),
                ((LookPaintPlan.TextFillOp) op(p, LookPaintPlan.Kind.TEXT_FILL)).paint.color);
        assertFalse(p.upper);
        assertEquals(500, p.fontWeight);
        assertEquals(0.35 * 1.6, p.effect, 1e-9);
        assertEquals(0.45 + 0.06, LookPaintPlan.room(c.textLook("neon_box")).insetX, 1e-9);
    }

    @Test
    public void tiltStickerHasABorderAndASecondRingTouchingIt() throws Exception {
        LookPaintPlan p = plan(contract(), "tilt_sticker");
        assertEquals(Arrays.asList(LookPaintPlan.Kind.BOX_FILL, LookPaintPlan.Kind.BOX_BORDER,
                LookPaintPlan.Kind.BOX_RING, LookPaintPlan.Kind.TEXT_FILL), kinds(p));
        LookPaintPlan.BoxStrokeOp border = op(p, LookPaintPlan.Kind.BOX_BORDER);
        LookPaintPlan.BoxStrokeOp ring = op(p, LookPaintPlan.Kind.BOX_RING);
        assertEquals(0.06, border.width, 0.0);
        assertEquals(0xFFFFFFFF, border.color);
        assertEquals(0.07, ring.width, 0.0);
        assertEquals(0.06, ring.offset, 1e-12);        // border width + gap 0
        assertEquals(0xFFFF8040, ring.color);
        assertEquals(0.13, p.borderOuter, 1e-12);
    }

    @Test
    public void gradientBlockHasALeftToRightBoxGradientAndAHardBlockShadow() throws Exception {
        LookPaintPlan p = plan(contract(), "gradient_block");
        assertEquals(Arrays.asList(LookPaintPlan.Kind.BOX_SHADOW, LookPaintPlan.Kind.BOX_FILL,
                LookPaintPlan.Kind.BOX_BORDER, LookPaintPlan.Kind.TEXT_FILL), kinds(p));
        LookPaintPlan.BoxShadowOp sh = op(p, LookPaintPlan.Kind.BOX_SHADOW);
        assertEquals(0.0, sh.blur, 0.0);
        assertEquals(0.1, sh.dx, 0.0);
        assertEquals(0.1, sh.dy, 0.0);
        assertEquals(0.1, sh.expand, 0.0);              // the block follows fill and border
        LookPaintPlan.BoxFillOp fill = op(p, LookPaintPlan.Kind.BOX_FILL);
        assertTrue(fill.paint.isGradient());
        assertEquals(0.3, fill.radiusOfHeight, 0.0);
        assertEquals(1.0, fill.paint.gradient.x1 - fill.paint.gradient.x0, 0.0);
        assertEquals(0.0, fill.paint.gradient.y1 - fill.paint.gradient.y0, 0.0);
        assertEquals(0xFFFF8040, fill.paint.gradient.from);
        assertEquals(PaletteResolver.mix(0xFFFF8040, 0xFFFFFFFF, 0.65), fill.paint.gradient.to);
    }

    @Test
    public void everyLookOfTheContractPlansForEveryStringOfTheMatrix() throws Exception {
        EngineContract c = contract();
        assertEquals(16, c.getTextLooks().size());
        for (TextLook look : c.getTextLooks()) {
            String first = null;
            for (TestStrings.Entry e : TestStrings.ALL) {
                TextLayoutPolicy.Result fit = new TextLayoutPolicy.Result(false, 64f, Arrays.asList(e.text), e.rtl, false);
                LookPaintPlan p = LookPaintPlan.build(look, colors(c), 1, fit);
                assertEquals(e.rtl, p.rtl);
                assertEquals(64f, p.sizePx, 0f);
                String d = describe(p);
                if (first == null) first = d;
                assertEquals(look.id + " " + e.id, first, d);   // the plan does not depend on the text
            }
        }
    }

    @Test
    public void everyLookPlansInTheContractsLayerOrder() throws Exception {
        EngineContract c = contract();
        JSONArray order = new JSONObject(TestContract.json()).getJSONArray("text_layer_order");
        assertEquals(LookPaintPlan.Layer.values().length, order.length());
        for (int i = 0; i < order.length(); i++) assertEquals(order.getString(i), LookPaintPlan.Layer.values()[i].id);
        for (TextLook look : c.getTextLooks()) {
            LookPaintPlan p = LookPaintPlan.build(look, colors(c), 0, FIT);
            int last = -1;
            for (LookPaintPlan.Op o : p.ops) {
                if (o.layer == null) continue;
                assertTrue(look.id + " " + o.kind, o.layer.ordinal() >= last);
                last = o.layer.ordinal();
            }
        }
    }

    @Test
    public void theLayersAPlanDrawsAreTheLayersTheServerVectorsListForTheLook() throws Exception {
        EngineContract c = contract();
        JSONArray vectors = new JSONObject(TestContract.json()).getJSONArray("look_vectors");
        assertEquals(c.getTextLooks().size(), vectors.length());
        for (int i = 0; i < vectors.length(); i++) {
            JSONObject v = vectors.getJSONObject(i);
            String id = v.getString("look");
            List<String> want = new ArrayList<>();
            JSONArray facts = v.getJSONArray("facts");
            for (int j = 0; j < facts.length(); j++) {
                if (!"layers".equals(facts.getJSONObject(j).getString("kind"))) continue;
                JSONArray d = facts.getJSONObject(j).getJSONArray("draws");
                for (int k = 0; k < d.length(); k++) want.add(d.getString(k));
            }
            List<String> got = new ArrayList<>();
            for (LookPaintPlan.Layer l : LookPaintPlan.Layer.values()) {
                for (LookPaintPlan.Op o : plan(c, id).ops) {
                    boolean behind = o.kind == LookPaintPlan.Kind.GLOW_BEHIND_TEXT && l == LookPaintPlan.Layer.GLOW;
                    if ((o.layer == l || behind) && !got.contains(l.id)) got.add(l.id);
                }
            }
            assertEquals(id, want, got);
        }
    }

    // ---- gradient directions ----

    @Test
    public void ltrTtbAndDiagGiveDifferentStops() throws Exception {
        double[] ltr = LookPaintPlan.gradientLine("ltr");
        double[] ttb = LookPaintPlan.gradientLine("ttb");
        double[] diag = LookPaintPlan.gradientLine("diag");
        assertFalse(Arrays.equals(ltr, ttb));
        assertFalse(Arrays.equals(ltr, diag));
        assertFalse(Arrays.equals(ttb, diag));
        assertTrue(ltr[2] > ltr[0] && ltr[3] == ltr[1]);          // moves along x only
        assertTrue(ttb[3] > ttb[1] && ttb[2] == ttb[0]);          // moves along y only
        assertTrue(diag[2] > diag[0] && diag[3] > diag[1]);       // corner to corner
        try {
            LookPaintPlan.gradientLine("radial");
            fail("an unknown direction has no handler");
        } catch (UnsupportedLookException e) {
            assertEquals("property_not_drawn", e.code());
        }
    }

    @Test
    public void aTextGradientOfEachDirectionReachesThePlan() throws Exception {
        for (String dir : new String[]{"ltr", "ttb", "diag"}) {
            EngineContract c = withLook("gradient", l -> l.getJSONObject("text_fill").put("direction", dir));
            LookPaintPlan.Gradient g = ((LookPaintPlan.TextFillOp) op(plan(c, "gradient"), LookPaintPlan.Kind.TEXT_FILL))
                    .paint.gradient;
            assertArrayEquals(dir, LookPaintPlan.gradientLine(dir), new double[]{g.x0, g.y0, g.x1, g.y1});
        }
    }

    private static void assertArrayEquals(String msg, double[] a, double[] b) {
        assertTrue(msg, Arrays.equals(a, b));
    }

    // ---- fail closed ----

    private static void assertRefused(EngineContract c, String id, String field) throws Exception {
        try {
            LookPaintPlan.build(c.textLook(id), colors(c), 0, FIT);
            fail("expected a refusal for " + field);
        } catch (UnsupportedLookException e) {
            assertEquals(field, e.field);
            assertEquals("property_not_drawn", e.code());
            assertFalse(e.getMessage().contains("SAVE"));    // a code and a field name, never a text
        }
    }

    @Test
    public void aLookKeyWithoutAHandlerIsRefused() throws Exception {
        EngineContract c = withLook("sticker", l -> l.put("blend_mode", "multiply"));
        assertEquals(Arrays.asList("blend_mode"), c.textLook("sticker").unknownKeys);
        assertRefused(c, "sticker", "blend_mode");
        // the other looks of the same contract still plan
        assertNotNull(plan(c, "label_pill"));
    }

    @Test
    public void aKeyInsideANewBlockWithoutAHandlerIsRefused() throws Exception {
        EngineContract c = withLook("neon_box", l -> l.getJSONObject("box").getJSONObject("border").put("dash", 0.5));
        assertRefused(c, "neon_box", "box.border.dash");
    }

    @Test
    public void anEnumValueWithoutAHandlerIsRefused() throws Exception {
        assertRefused(withLook("sticker", l -> l.getJSONObject("text_stroke").put("join", "arcs")), "sticker",
                "text_stroke.join");
        assertRefused(withLook("neon_box", l -> l.getJSONObject("box").put("shape", "hexagon")), "neon_box", "box.shape");
        assertRefused(withLook("neon_box", l -> l.getJSONObject("font").put("family", "cursive")), "neon_box",
                "font.family");
        assertRefused(withLook("neon_box", l -> l.put("case", "title")), "neon_box", "case");
        assertRefused(withLook("neon_box", l -> l.put("opacity", 1.5)), "neon_box", "opacity");
        assertRefused(withLook("gradient", l -> l.getJSONObject("text_fill").put("direction", "radial")), "gradient",
                "gradient_direction");
        assertRefused(withLook("gradient_block", l -> l.getJSONObject("box").getJSONObject("shadow").put("style", "inner")),
                "gradient_block", "box.shadow.style");
    }

    @Test
    public void everyKeyOfEveryLookOfTheServedContractHasAHandler() throws Exception {
        for (TextLook look : contract().getTextLooks()) assertTrue(look.id, look.unknownKeys.isEmpty());
    }

    // ---- geometry helpers and colour roles ----

    @Test
    public void aCornerFollowsTheFillAndASharpBoxStaysSharp() {
        assertEquals(30f, LookPaintPlan.cornerRadiusPx(0.5, 0, 60f, 96f), 1e-4f);
        assertEquals(30f + 0.1f * 96f, LookPaintPlan.cornerRadiusPx(0.5, 0.1, 60f, 96f), 1e-3f);
        assertEquals(0f, LookPaintPlan.cornerRadiusPx(0, 0.2, 60f, 96f), 0f);
    }

    @Test
    public void accentLightIsTheAccentMixedWithTheServedPaperShare() throws Exception {
        JSONObject roles = new JSONObject(TestContract.json()).getJSONObject("color_roles");
        assertEquals(PaletteResolver.LIGHT_PAPER_SHARE, roles.getJSONObject("accent_light").getDouble("paper_share"), 0.0);
        assertEquals(PaletteResolver.DARK_FACTOR, roles.getJSONObject("accent_dark").getDouble("factor"), 0.0);
        int light = colors(contract()).resolve("accent_light", 0);
        // 0xFF8040 with 65 percent white: 255, 128 * .35 + 255 * .65 = 210.55, 64 * .35 + 165.75 = 188.15
        assertEquals(0xFFFFD3BC, light);
        assertNotEquals(colors(contract()).resolve("paper", 0), light);
    }

    @Test
    public void theNewBlocksOfALookAreReadAsTheServerWroteThem() throws Exception {
        TextLook tilt = contract().textLook("tilt_sticker");
        assertTrue(tilt.hasNewBlocks);
        assertEquals("rounded", tilt.box.shape);
        assertEquals(0.07, tilt.box.border.ring.width, 0.0);
        assertEquals(800, tilt.font.weight);
        assertEquals("as_written", tilt.caseMode);
        assertEquals(-7.0, tilt.tiltDeg[0], 0.0);
        TextLook dark = contract().textLook("dark_accent");
        assertEquals("sans_condensed", dark.font.family);
        assertEquals("solid", dark.textFill.kind);
        assertNull(dark.textStroke);
        assertNull(dark.textShadow);
    }
}
