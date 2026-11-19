package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderNotes;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Which looks the painter draws completely, and the plain look an overlay falls back to (gate 3). */
public class TextLookSupportTest {

    private interface Edit {
        void apply(JSONObject look) throws Exception;
    }

    private static EngineContract withLook(String id, Edit edit) throws Exception {
        JSONObject o = new JSONObject(TestContract.json());
        JSONArray a = o.getJSONArray("text_styles");
        for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).getString("id"))) edit.apply(a.getJSONObject(i));
        return EngineContract.fromJson(o.toString());
    }

    @Test
    public void theShippedSixNeedNoFeatureAndTheTenNewOnesNeedTheBoxBorderAndCaseAsWritten() throws Exception {
        EngineContract c = TestContract.load();
        for (String id : TextLookSupport.SHIPPED_LOOKS) {
            assertTrue(id, TextLookSupport.requiredFeatures(c.textLook(id)).isEmpty());
        }
        for (TextLook look : c.getTextLooks()) {
            if (TextLookSupport.SHIPPED_LOOKS.contains(look.id)) continue;
            List<String> need = TextLookSupport.requiredFeatures(look);
            assertTrue(look.id, need.contains("box_border"));
            assertTrue(look.id, need.contains("case_as_written"));
        }
        assertTrue(TextLookSupport.requiredFeatures(c.textLook("neon_box")).containsAll(
                Arrays.asList("box_glow", "font_weight")));
    }

    @Test
    public void everyServedFeatureIdIsOneThePainterImplements() throws Exception {
        EngineContract c = TestContract.load();
        assertEquals(c.getTextFeatures(), TextLookSupport.IMPLEMENTED_FEATURES);
        for (TextLook look : c.getTextLooks()) {
            assertTrue(look.id, c.getTextFeatures().containsAll(TextLookSupport.requiredFeatures(look)));
        }
    }

    @Test
    public void everyLookOfTheServedContractIsDrawable() throws Exception {
        for (TextLook look : TestContract.load().getTextLooks()) assertTrue(look.id, TextLookSupport.drawable(look));
    }

    @Test
    public void aLookWithAFieldWithoutAHandlerIsNotDrawable() throws Exception {
        EngineContract c = withLook("sticker", l -> l.put("blend_mode", "multiply"));
        assertFalse(TextLookSupport.drawable(c.textLook("sticker")));
        assertTrue(TextLookSupport.drawable(c.textLook("label_pill")));
        try {
            TextLookSupport.require(c.textLook("sticker"));
            fail("must be refused");
        } catch (UnsupportedLookException e) {
            assertEquals("blend_mode", e.field);
            assertEquals("property_not_drawn", e.code());
        }
    }

    @Test
    public void aNeededFeatureTheBuildLacksIsRefusedByItsId() throws Exception {
        TextLook neon = TestContract.load().textLook("neon_box");
        List<String> without = new ArrayList<>(TextLookSupport.IMPLEMENTED_FEATURES);
        without.remove("box_glow");
        try {
            TextLookSupport.require(neon, without);
            fail("must be refused");
        } catch (UnsupportedLookException e) {
            assertEquals("box_glow", e.field);
        }
        // a shipped look needs nothing, so it still passes with no feature at all
        TextLook sticker = TestContract.load().textLook("sticker");
        try {
            TextLookSupport.require(sticker, new ArrayList<String>());
        } catch (UnsupportedLookException e) {
            fail("a shipped look needs no feature");
        }
    }

    @Test
    public void thePlainLookIsBoldTextWithAnAccentStrokeAndNothingElse() throws Exception {
        TextLook plain = TextLook.plain();
        assertEquals(plain, TextLook.plain());
        assertTrue(plain.unknownKeys.isEmpty());
        assertTrue(TextLookSupport.drawable(plain));
        assertNull(plain.box);
        assertNull(plain.tiltDeg);
        assertEquals(700, plain.font.weight);
        assertEquals("accent", plain.textStroke.color);
        EngineContract c = TestContract.load();
        PaletteResolver colors = new PaletteResolver(new Palette("multi", Arrays.asList("#FF8040", "#2979FF")), c.getPalette());
        TextLayoutPolicy.Result fit = new TextLayoutPolicy.Result(false, 96f, Arrays.asList("HELLO"), false, false);
        LookPaintPlan plan = LookPaintPlan.build(plain, colors, 0, fit);
        assertEquals(Arrays.asList(LookPaintPlan.Kind.TEXT_STROKE, LookPaintPlan.Kind.TEXT_FILL), kinds(plan));
        LookPaintPlan.TextStrokeOp stroke = (LookPaintPlan.TextStrokeOp) plan.ops.get(0);
        assertEquals(0xFFFF8040, stroke.color);
    }

    private static List<LookPaintPlan.Kind> kinds(LookPaintPlan p) {
        List<LookPaintPlan.Kind> k = new ArrayList<>();
        for (LookPaintPlan.Op o : p.ops) k.add(o.kind);
        return k;
    }

    @Test
    public void theNoteOfAFallbackCarriesTheItemIdOnly() {
        RenderNotes notes = new RenderNotes();
        notes.add(RenderNotes.PROPERTY_NOT_DRAWN, "item_7");
        notes.add(RenderNotes.PROPERTY_NOT_DRAWN, "save time with this text");   // not a plain id: kept without it
        List<RenderNotes.Note> n = notes.notes();
        assertEquals(2, n.size());
        assertEquals("property_not_drawn", n.get(0).code);
        assertEquals("item_7", n.get(0).itemId);
        assertNull(n.get(1).itemId);
        assertNotNull(n.get(1).code);
    }

    @Test
    public void aContractWhoseLayerOrderOrColourRolesDifferDoesNotMatch() throws Exception {
        assertTrue(TextLookSupport.contractMatches(TestContract.load()));
        JSONObject o = new JSONObject(TestContract.json());
        o.put("text_layer_order", new JSONArray(Arrays.asList("box_fill", "box_shadow", "box_border", "text_shadow",
                "text_stroke", "text_fill", "glow")));
        assertFalse(TextLookSupport.contractMatches(EngineContract.fromJson(o.toString())));
        JSONObject p = new JSONObject(TestContract.json());
        p.getJSONObject("color_roles").getJSONObject("accent_light").put("paper_share", 0.5);
        assertFalse(TextLookSupport.contractMatches(EngineContract.fromJson(p.toString())));
        JSONObject q = new JSONObject(TestContract.json());
        q.remove("text_layer_order");
        q.remove("color_roles");
        assertTrue(TextLookSupport.contractMatches(EngineContract.fromJson(q.toString())));
    }
}
