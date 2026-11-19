package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gate 2, the JVM conformance runner: the vectors the server generated ({@code look_vectors} in the bundled contract)
 * against the paint plan, for all 16 looks and the whole string matrix. The ten new looks are asserted here even though
 * they are not enabled until C10. The second half tests the runner itself: it must fail when a handler is missing and
 * when a vector fact disagrees with the plan.
 */
public class LookConformanceTest {

    private static final String[] ALL_LOOKS = {"sticker", "label_pill", "3d", "glow", "gradient", "highlighter",
            "neon_box", "pill_ring", "gradient_block", "tilt_sticker", "solid_round", "pastel_pill", "flat_rect",
            "yellow_bold", "dark_accent", "coral_pill"};

    private static JSONArray vectors() throws Exception {
        return new JSONArray(TestContract.load().lookVectors().toString());
    }

    private static List<String> run(LookConformance runner, String contractJson, JSONArray vectors) throws Exception {
        return runner.run(contractJson, vectors);
    }

    private static JSONObject vectorOf(JSONArray vectors, String look) throws Exception {
        for (int i = 0; i < vectors.length(); i++) if (look.equals(vectors.getJSONObject(i).getString("look"))) return vectors.getJSONObject(i);
        throw new AssertionError(look);
    }

    private static JSONObject fact(JSONArray vectors, String look, String kind) throws Exception {
        JSONObject f = LookConformance.find(vectorOf(vectors, look).getJSONArray("facts"), kind);
        assertNotNull(look + "/" + kind, f);
        return f;
    }

    private static void assertFails(String why, List<String> violations, String mention) {
        assertFalse(why + ": the runner passed", violations.isEmpty());
        boolean found = false;
        for (String v : violations) found |= v.contains(mention);
        assertTrue(why + ": no violation mentions '" + mention + "' in " + violations, found);
    }

    // ---- the phone against the server's vectors ----

    @Test
    public void theBundledContractServesAllSixteenLooksWithVectors() throws Exception {
        EngineContract c = TestContract.load();
        assertEquals(Arrays.asList(ALL_LOOKS), idsOf(c));
        JSONArray v = vectors();
        assertEquals(16, v.length());
        for (int i = 0; i < ALL_LOOKS.length; i++) {
            assertEquals(ALL_LOOKS[i], v.getJSONObject(i).getString("look"));
            assertEquals(TestStrings.ALL.size(), v.getJSONObject(i).getJSONArray("probes").length());
        }
    }

    @Test
    public void everyLookAndEveryVectorConformsToThePaintPlan() throws Exception {
        List<String> violations = run(LookConformance.standard(), TestContract.json(), vectors());
        assertTrue("conformance violations:\n" + String.join("\n", violations), violations.isEmpty());
    }

    @Test
    public void everyTextLookFieldHasAHandler() {
        assertEquals(new ArrayList<String>(), LookConformance.standard().fieldsWithoutHandler());
    }

    @Test
    public void theGlowBehindTheTextIsTheOnlyLookThatBendsTheContractsLayerOrder() throws Exception {
        // Documented exception (C5, gate 5): the shipped glow look has no box and keeps its legacy glow behind the text
        EngineContract c = TestContract.load();
        PaletteResolver colors = new PaletteResolver(new Palette("multi", Arrays.asList("#FF8040")), c.getPalette());
        TextLayoutPolicy.Result fit = new TextLayoutPolicy.Result(false, 96f, Arrays.asList("A"), false, false);
        List<String> behind = new ArrayList<>();
        for (TextLook look : c.getTextLooks()) {
            for (LookPaintPlan.Op o : LookPaintPlan.build(look, colors, 0, fit).ops) {
                if (o.kind == LookPaintPlan.Kind.GLOW_BEHIND_TEXT) behind.add(look.id);
            }
        }
        assertEquals(Arrays.asList("glow"), behind);
    }

    @Test
    public void shippedLooksNeedNoFeatureAndTheTenNewOnesNeedBoxBorderAndCaseAsWritten() throws Exception {
        EngineContract c = TestContract.load();
        for (TextLook look : c.getTextLooks()) {
            List<String> need = TextLookSupport.requiredFeatures(look);
            assertTrue(look.id, c.getTextFeatures().containsAll(need));
            if (TextLookSupport.SHIPPED_LOOKS.contains(look.id)) {
                assertTrue(look.id, need.isEmpty());
            } else {
                assertTrue(look.id + " " + need, need.contains("box_border") && need.contains("case_as_written"));
            }
        }
    }

    // ---- the runner itself: a missing handler fails it ----

    @Test
    public void aRunnerWithoutTheHandlerOfAFactKindFails() throws Exception {
        String[] kinds = {"text_fill", "text_stroke", "text_shadow", "box", "box_fill", "box_border", "box_shadow",
                "box_glow", "glow", "extrude", "bar", "tilt", "opacity", "font", "case", "letter_spacing", "layers"};
        for (String kind : kinds) {
            List<String> v = run(LookConformance.without(kind), TestContract.json(), vectors());
            assertFails("no handler for " + kind, v, "no handler for the " + kind + " fact");
        }
    }

    @Test
    public void aTextLookFieldWithoutAHandlerIsReported() {
        // Every field is owned by one check; drop it and the class reflection finds the field unhandled
        assertTrue(LookConformance.without("opacity").fieldsWithoutHandler().contains("opacity"));
        assertTrue(LookConformance.without("extrude").fieldsWithoutHandler().contains("extrude"));
        assertTrue(LookConformance.without("tilt").fieldsWithoutHandler().contains("tiltDeg"));
        assertTrue(LookConformance.without("bar").fieldsWithoutHandler().contains("bar"));
        assertTrue(LookConformance.without("letter_spacing").fieldsWithoutHandler().contains("letterSpacing"));
    }

    @Test
    public void aSyntheticLookWithAFieldThePainterHasNoHandlerForFailsTheRunner() throws Exception {
        JSONObject root = new JSONObject(TestContract.json());
        JSONArray styles = root.getJSONArray("text_styles");
        JSONObject look = styles.getJSONObject(styles.length() - 1);   // coral_pill
        look.put("sparkle", new JSONObject().put("amount", 3));
        JSONArray v = vectors();
        JSONObject facts = vectorOf(v, look.getString("id"));
        facts.getJSONArray("facts").put(new JSONObject().put("kind", "sparkle").put("fields", new JSONArray().put("sparkle"))
                .put("amount", 3));
        List<String> violations = run(LookConformance.standard(), root.toString(), v);
        assertFails("a synthetic field", violations, "no handler for field sparkle");
        assertFails("a synthetic fact kind", violations, "no handler for the sparkle fact");
        assertFails("the painter refuses the look", violations, "the painter refuses the look (sparkle)");
        // The other fifteen looks are not blamed
        for (String s : violations) assertTrue(s, s.startsWith("coral_pill") || s.startsWith("TextLook") || s.startsWith("looks"));
    }

    @Test
    public void aSyntheticFieldOnANestedBlockIsRefusedToo() throws Exception {
        JSONObject root = new JSONObject(TestContract.json());
        JSONArray styles = root.getJSONArray("text_styles");
        styles.getJSONObject(6).getJSONObject("box").put("bevel", 0.2);   // neon_box
        List<String> violations = run(LookConformance.standard(), root.toString(), vectors());
        assertFails("an unknown key inside box", violations, "neon_box: the painter refuses the look (box.bevel)");
    }

    @Test
    public void aFieldWithoutAVectorFailsTheRunner() throws Exception {
        JSONArray v = vectors();
        // Take the extrude vector away from the 3d look: the look still carries extrude, so it has no vector
        JSONArray facts = vectorOf(v, "3d").getJSONArray("facts");
        for (int i = 0; i < facts.length(); i++) if ("extrude".equals(facts.getJSONObject(i).getString("kind"))) facts.remove(i);
        assertFails("no vector for extrude", run(LookConformance.standard(), TestContract.json(), v), "3d: field extrude has no vector");
    }

    // ---- the runner itself: a vector that disagrees with the plan fails it ----

    @Test
    public void aVectorThatDisagreesWithThePlanFails() throws Exception {
        Object[][] cases = {
                // look, fact kind, edit, what the violation mentions
                {"sticker", "text_stroke", (Edit) f -> f.put("width_s", 0.3), "stroke width"},
                {"sticker", "text_stroke", (Edit) f -> f.put("join", "miter"), "stroke join"},
                {"sticker", "text_shadow", (Edit) f -> f.put("dx_s", -0.05), "text shadow dx"},
                {"sticker", "text_shadow", (Edit) f -> f.put("dy_s", 0.6), "text shadow dy"},
                {"sticker", "text_shadow", (Edit) f -> f.put("hard", false), "text shadow hard/soft"},
                {"sticker", "text_fill", (Edit) f -> f.getJSONObject("fill").put("color", "paper"), "text fill colour"},
                {"gradient", "text_fill", (Edit) f -> f.getJSONObject("fill").put("direction", "ltr"), "text fill direction"},
                {"gradient", "text_fill", (Edit) f -> f.getJSONObject("fill").put("from", "accent"), "text fill from colour"},
                {"gradient_block", "box_fill", (Edit) f -> f.getJSONObject("fill").put("direction", "diag"), "box fill direction"},
                {"gradient_block", "box_fill", (Edit) f -> f.getJSONObject("fill").put("kind", "solid").put("color", "accent"), "box fill must be solid"},
                {"gradient_block", "box_shadow", (Edit) f -> f.put("dx_s", 0.0), "box shadow dx"},
                {"gradient_block", "box_shadow", (Edit) f -> f.put("style", "soft"), "box shadow hard/soft"},
                {"gradient_block", "box_border", (Edit) f -> f.put("width_s", 0.2), "border width"},
                {"tilt_sticker", "box_border", (Edit) f -> f.getJSONObject("ring").put("gap_s", 0.05), "ring offset"},
                {"tilt_sticker", "box_border", (Edit) f -> f.getJSONObject("ring").put("width_s", 0.2), "ring width"},
                {"tilt_sticker", "box_border", (Edit) f -> f.put("ring", JSONObject.NULL), "operations"},
                {"pill_ring", "box_border", (Edit) f -> f.put("ring", new JSONObject().put("color", "ink").put("width_s", 0.05).put("gap_s", 0.02)), "operations"},
                {"tilt_sticker", "tilt", (Edit) f -> f.put("range_deg", new JSONArray().put(5).put(7)), "tilt range"},
                {"pill_ring", "box", (Edit) f -> f.put("radius_of_height", 0.3), "box corner radius"},
                {"pill_ring", "box", (Edit) f -> f.getJSONObject("padding_s").put("x", 0.9), "padding x"},
                {"pill_ring", "box", (Edit) f -> f.getJSONObject("contains_text_with_inset_s").put("top", 0.9), "inset"},
                {"flat_rect", "box", (Edit) f -> f.put("radius_of_height", 0.1), "box corner radius"},
                {"neon_box", "box_glow", (Edit) f -> f.put("blur_s", 0.9), "box glow blur"},
                {"neon_box", "glow", (Edit) f -> f.put("alpha", 0.1), "glow colour and alpha"},
                {"3d", "extrude", (Edit) f -> f.put("copies", 3), "extrude copies"},
                {"3d", "extrude", (Edit) f -> f.put("color", "ink"), "extrude colour"},
                {"highlighter", "bar", (Edit) f -> f.put("height_of_line", 0.9), "bar height"},
                {"neon_box", "font", (Edit) f -> f.put("weight", 900), "font weight"},
                {"dark_accent", "font", (Edit) f -> f.put("family", "serif"), "font family"},
                {"dark_accent", "font", (Edit) f -> f.put("family", "script"), "is not a family"},
                {"neon_box", "opacity", (Edit) f -> f.put("value", 0.5), "opacity"},
                {"sticker", "case", (Edit) f -> f.put("mode", "as_written"), "case mode"},
                {"sticker", "letter_spacing", (Edit) f -> f.put("spacing_s", 0.1), "letter spacing"},
        };
        for (Object[] c : cases) {
            JSONArray v = vectors();
            ((Edit) c[2]).apply(fact(v, (String) c[0], (String) c[1]));
            assertFails(c[0] + "/" + c[1], run(LookConformance.standard(), TestContract.json(), v), (String) c[3]);
        }
    }

    @Test
    public void aWrongLayerListOrOrderFails() throws Exception {
        JSONArray v = vectors();
        JSONArray draws = fact(v, "label_pill", "layers").getJSONArray("draws");
        draws.put(1, "box_border");   // box_shadow, box_border instead of box_shadow, box_fill, text_fill
        assertFails("wrong layer", run(LookConformance.standard(), TestContract.json(), v), "layers drawn");

        v = vectors();
        JSONObject l = fact(v, "pill_ring", "layers");
        l.put("draws", new JSONArray().put("box_border").put("box_fill").put("text_fill"));
        assertFails("wrong order", run(LookConformance.standard(), TestContract.json(), v), "not in the contract's order");

        v = vectors();
        fact(v, "sticker", "layers").put("draws", new JSONArray().put("text_stroke").put("text_fill"));
        assertFails("a layer the vectors drop but the plan draws", run(LookConformance.standard(), TestContract.json(), v), "layers drawn");
    }

    @Test
    public void aWrongProbeFails() throws Exception {
        JSONArray v = vectors();
        JSONArray probes = vectorOf(v, "sticker").getJSONArray("probes");
        // Turkish: 'i' must become the dotted capital
        for (int i = 0; i < probes.length(); i++) {
            if ("turkish_i".equals(probes.getJSONObject(i).getString("probe"))) probes.getJSONObject(i).put("text_drawn", "IĞDIR ISIK");
        }
        assertFails("locale blind case", run(LookConformance.standard(), TestContract.json(), v), "text drawn for turkish_i");
    }

    @Test
    public void turkishDottedIIsUppercasedWithTheLanguageInTheRealVectors() throws Exception {
        JSONArray probes = vectorOf(vectors(), "sticker").getJSONArray("probes");
        for (int i = 0; i < probes.length(); i++) {
            JSONObject p = probes.getJSONObject(i);
            if ("turkish_i".equals(p.getString("probe"))) assertEquals("İĞDIR IŞIK", p.getString("text_drawn"));
            if ("bengali_conjunct".equals(p.getString("probe"))) assertEquals(p.getString("text"), p.getString("text_drawn"));
        }
    }

    // ---- letter spacing: the real vectors are all 0, so a synthetic look proves the rule ----

    private static final Set<String> NO_SPACING_PROBES = new HashSet<>(Arrays.asList("bengali_conjunct", "devanagari",
            "arabic", "thai", "mixed_ltr_rtl", "over_cap_thai"));

    /** The contract with {@code sticker} given a letter spacing, and vectors as the server would write them. */
    private static JSONObject spacedContract(double spacing) throws Exception {
        JSONObject root = new JSONObject(TestContract.json());
        root.getJSONArray("text_styles").getJSONObject(0).put("letter_spacing", spacing);
        return root;
    }

    private static JSONArray spacedVectors(double spacing, Set<String> zeroProbes) throws Exception {
        JSONArray v = vectors();
        JSONObject sticker = vectorOf(v, "sticker");
        fact(v, "sticker", "letter_spacing").put("spacing_s", spacing);
        JSONArray probes = sticker.getJSONArray("probes");
        for (int i = 0; i < probes.length(); i++) {
            JSONObject p = probes.getJSONObject(i);
            p.put("letter_spacing_s", zeroProbes.contains(p.getString("probe")) ? 0 : spacing);
        }
        return v;
    }

    @Test
    public void letterSpacingIsKeptForLatinAndForcedToZeroForJoiningScripts() throws Exception {
        JSONObject root = spacedContract(0.08);
        List<String> ok = run(LookConformance.standard(), root.toString(), spacedVectors(0.08, NO_SPACING_PROBES));
        assertTrue(ok.toString(), ok.isEmpty());
        // Spacing on an Arabic probe would pull the join apart: the runner must see the vector disagree
        Set<String> wrong = new HashSet<>(NO_SPACING_PROBES);
        wrong.remove("arabic");
        assertFails("spacing on Arabic", run(LookConformance.standard(), root.toString(), spacedVectors(0.08, wrong)),
                "letter spacing drawn for arabic");
        // And no spacing on Latin where the look has some
        Set<String> latinZero = new HashSet<>(NO_SPACING_PROBES);
        latinZero.add("one_word");
        assertFails("no spacing on Latin", run(LookConformance.standard(), root.toString(), spacedVectors(0.08, latinZero)),
                "letter spacing drawn for one_word");
    }

    private interface Edit {
        void apply(JSONObject fact) throws Exception;
    }

    private static List<String> idsOf(EngineContract c) {
        List<String> ids = new ArrayList<>();
        for (TextLook l : c.getTextLooks()) ids.add(l.id);
        return ids;
    }
}
