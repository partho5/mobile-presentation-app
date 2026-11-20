package com.jovoc.facecampresentationrecorder.aiedit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.Capabilities;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.ContractException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextLookSupport;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Text styles and features are listed by evidence (painter supports it AND the owner ticked it); one bad look is only dropped. */
public class CapabilitiesByEvidenceTest {

    private static final List<String> SIX = TextLookSupport.SHIPPED_LOOKS;
    private static final List<String> NONE = Collections.emptyList();

    private interface Edit {
        void apply(JSONObject look) throws Exception;
    }

    private static EngineContract withLook(String id, Edit edit) throws Exception {
        JSONObject o = new JSONObject(TestContract.json());
        JSONArray a = o.getJSONArray("text_styles");
        for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).getString("id"))) edit.apply(a.getJSONObject(i));
        return EngineContract.fromJson(o.toString());
    }

    private static List<String> all(EngineContract c) {
        List<String> ids = new ArrayList<>();
        for (TextLook l : c.getTextLooks()) ids.add(l.id);
        return ids;
    }

    @Test
    public void todayTheAppListsAllSixteenLooksAndTheirSevenFeatures() throws Exception {
        EngineContract c = TestContract.load();
        Capabilities caps = Capabilities.current(c);
        assertEquals(all(c), new ArrayList<>(caps.getTextStyles()));
        assertEquals(SIX, new ArrayList<>(caps.getTextStyles()).subList(0, 6));
        assertEquals(Arrays.asList("box_gradient", "box_border", "box_border_ring", "box_glow", "font_family",
                "font_weight", "case_as_written"), new ArrayList<>(caps.getTextFeatures()));
        assertEquals(7, caps.toJson().getJSONArray("text_features").length());
        assertEquals(16, caps.toJson().getJSONArray("text_styles").length());
        assertTrue(caps.knownTo(c));
        for (String id : Arrays.asList("neon_box", "pill_ring", "gradient_block", "tilt_sticker", "solid_round",
                "pastel_pill", "flat_rect", "yellow_bold", "dark_accent", "coral_pill")) {
            assertTrue(id, caps.canStyle(id));
        }
    }

    @Test
    public void aNewLookIsNotListedUntilTheOwnerTickedItAndItsFeatures() throws Exception {
        EngineContract c = TestContract.load();
        List<String> features = TextLookSupport.IMPLEMENTED_FEATURES;
        // everything the painter supports, but not ticked: still the six
        assertEquals(SIX, new ArrayList<>(Capabilities.current(c, SIX, NONE, NONE).getTextStyles()));
        // the look is ticked, its features are not: not listed (the server could not plan it anyway)
        assertFalse(Capabilities.current(c, all(c), NONE, NONE).canStyle("pill_ring"));
        // look and features ticked: listed
        Capabilities caps = Capabilities.current(c, all(c), features, NONE);
        assertTrue(caps.canStyle("pill_ring"));
        assertTrue(caps.canDraw("box_border"));
        assertEquals(16, caps.getTextStyles().size());
        // features ticked but the look is not
        assertFalse(Capabilities.current(c, SIX, features, NONE).canStyle("pill_ring"));
    }

    @Test
    public void aFeatureIsListedOnlyWhenTickedAndNamedByTheContract() throws Exception {
        EngineContract c = TestContract.load();
        Capabilities caps = Capabilities.current(c, all(c), Arrays.asList("box_border", "made_up_feature"), NONE);
        assertEquals(Arrays.asList("box_border"), new ArrayList<>(caps.getTextFeatures()));
        // a look needing more than is ticked stays out
        assertFalse(caps.canStyle("neon_box"));
    }

    @Test
    public void aLookWithAFieldWithoutAHandlerIsNotListedButTheOthersAre() throws Exception {
        EngineContract c = withLook("sticker", l -> l.put("blend_mode", "multiply"));
        Capabilities caps = Capabilities.current(c, all(c), TextLookSupport.IMPLEMENTED_FEATURES, NONE);
        assertFalse(caps.canStyle("sticker"));
        assertTrue(caps.canStyle("label_pill"));
        assertTrue(caps.canStyle("neon_box"));
    }

    @Test
    public void whatIsListedIsASubsetOfSupportedAndTicked() throws Exception {
        EngineContract c = TestContract.load();
        List<String> ticked = Arrays.asList("sticker", "neon_box", "coral_pill", "not_in_contract");
        Capabilities caps = Capabilities.current(c, ticked, TextLookSupport.IMPLEMENTED_FEATURES, NONE);
        for (String id : caps.getTextStyles()) {
            assertTrue(id, ticked.contains(id));
            assertTrue(id, TextLookSupport.drawable(c.textLook(id)));
        }
        assertEquals(3, caps.getTextStyles().size());
        for (String f : caps.getTextFeatures()) assertTrue(TextLookSupport.IMPLEMENTED_FEATURES.contains(f));
    }

    @Test
    public void theTickedListsOfTheBuildAreTheSixPlusTheTenAndNeverDriftFromTheLooks() throws Exception {
        EngineContract c = TestContract.load();
        List<String> expectedLooks = new ArrayList<>(SIX);
        expectedLooks.addAll(Arrays.asList("neon_box", "pill_ring", "gradient_block", "tilt_sticker", "solid_round",
                "pastel_pill", "flat_rect", "yellow_bold", "dark_accent", "coral_pill"));
        assertEquals(expectedLooks, Capabilities.DEVICE_PASSED_TEXT_LOOKS);
        // the ticked features are exactly what the ticked looks need, derived (not typed twice)
        java.util.Set<String> needed = new java.util.HashSet<>();
        for (String id : Capabilities.DEVICE_PASSED_TEXT_LOOKS) {
            needed.addAll(TextLookSupport.requiredFeatures(c.textLook(id)));
        }
        assertEquals(needed, new java.util.HashSet<>(Capabilities.DEVICE_PASSED_TEXT_FEATURES));
        List<String> inPainterOrder = new ArrayList<>();
        for (String f : TextLookSupport.IMPLEMENTED_FEATURES) if (needed.contains(f)) inPainterOrder.add(f);
        assertEquals(inPainterOrder, Capabilities.DEVICE_PASSED_TEXT_FEATURES);
        // the specific needs the owner named
        assertTrue(TextLookSupport.requiredFeatures(c.textLook("neon_box")).contains("font_weight"));
        assertTrue(TextLookSupport.requiredFeatures(c.textLook("dark_accent")).contains("font_family"));
        assertTrue(TextLookSupport.requiredFeatures(c.textLook("coral_pill")).contains("font_family"));
        assertTrue(TextLookSupport.requiredFeatures(c.textLook("tilt_sticker")).contains("box_border_ring"));
        for (String id : expectedLooks.subList(6, 16)) {
            assertTrue(id, TextLookSupport.requiredFeatures(c.textLook(id)).containsAll(
                    Arrays.asList("box_border", "case_as_written")));
        }
        // every ticked look and feature is one the painter draws and the contract names
        for (String id : Capabilities.DEVICE_PASSED_TEXT_LOOKS) assertTrue(id, TextLookSupport.drawable(c.textLook(id)));
        for (String f : Capabilities.DEVICE_PASSED_TEXT_FEATURES) {
            assertTrue(f, TextLookSupport.IMPLEMENTED_FEATURES.contains(f) && c.getTextFeatures().contains(f));
        }
    }

    @Test
    public void aContractWithAnotherLayerOrderListsTheShippedSixOnly() throws Exception {
        JSONObject o = new JSONObject(TestContract.json());
        o.put("text_layer_order", new JSONArray(Arrays.asList("box_fill", "box_shadow", "box_border", "text_shadow",
                "text_stroke", "text_fill", "glow")));
        EngineContract c = EngineContract.fromJson(o.toString());
        Capabilities caps = Capabilities.current(c, all(c), TextLookSupport.IMPLEMENTED_FEATURES, NONE);
        assertEquals(SIX, new ArrayList<>(caps.getTextStyles()));
    }

    // ---- one bad look never drops the whole contract ----

    @Test
    public void aLookThatCannotBeReadIsDroppedAndTheContractStays() throws Exception {
        EngineContract c = withLook("neon_box", l -> l.remove("font"));
        assertEquals(15, c.getTextLooks().size());
        assertEquals(Arrays.asList("neon_box"), c.getRejectedLookIds());
        assertEquals(null, c.textLook("neon_box"));
        assertTrue(c.textLook("sticker") != null);
        assertFalse(Capabilities.current(c, all(c), TextLookSupport.IMPLEMENTED_FEATURES, NONE).canStyle("neon_box"));
        assertEquals(16, TestContract.load().getTextLooks().size());
        assertTrue(TestContract.load().getRejectedLookIds().isEmpty());
    }

    @Test
    public void anUnknownGradientOfAShippedLookDropsThatLookOnly() throws Exception {
        EngineContract c = withLook("gradient", l -> l.getJSONObject("fill").put("gradient", "radial"));
        assertEquals(Arrays.asList("gradient"), c.getRejectedLookIds());
        assertEquals(15, c.getTextLooks().size());
    }

    @Test
    public void aContractWithNoReadableLookIsStillRefused() throws Exception {
        JSONObject o = new JSONObject(TestContract.json());
        o.put("text_styles", new JSONArray("[{\"id\":\"x\"}]"));
        try {
            EngineContract.fromJson(o.toString());
            fail("must be refused");
        } catch (ContractException expected) {
            // refused
        }
    }

    @Test
    public void theNewTopLevelKeysAreRead() throws Exception {
        EngineContract c = TestContract.load();
        assertEquals(12, c.getTextFeatures().size());
        assertEquals(Arrays.asList("box_shadow", "box_fill", "box_border", "text_shadow", "text_stroke", "text_fill",
                "glow"), c.getTextLayerOrder());
        assertEquals(0.65, c.getColorRoles().get("accent_light.paper_share"), 0.0);
        assertEquals(0.55, c.getColorRoles().get("accent_dark.factor"), 0.0);
        assertEquals(16, c.lookVectors().length());
        // an older contract without them still reads
        JSONObject o = new JSONObject(TestContract.json());
        o.remove("text_features");
        o.remove("text_layer_order");
        o.remove("color_roles");
        o.remove("look_vectors");
        EngineContract old = EngineContract.fromJson(o.toString());
        assertTrue(old.getTextFeatures().isEmpty());
        assertEquals(0, old.lookVectors().length());
    }
}
