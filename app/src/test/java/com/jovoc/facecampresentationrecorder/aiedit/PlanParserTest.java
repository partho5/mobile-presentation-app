package com.jovoc.facecampresentationrecorder.aiedit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanComponent;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanParseException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanParser;

import org.junit.Before;
import org.junit.Test;

public class PlanParserTest {

    private EngineContract contract;

    @Before
    public void setUp() throws Exception {
        contract = TestContract.load();
    }

    /** A plan in the spec section 16 shape; {@code %EFFECT%} and {@code %VERSION%} are filled per test. */
    private static String plan(int version, String effect) {
        return "{"
                + "\"contract_version\":" + version + ",\"plan_version\":1,\"session_id\":\"s-1\","
                + "\"level\":\"standard\",\"video\":{\"output_duration_ms\":96400},"
                + "\"structure\":{\"cuts\":[{\"src_start_ms\":5200,\"src_end_ms\":5900,\"reason\":\"silence\"}],"
                + "\"speed_segments\":[{\"src_start_ms\":20000,\"src_end_ms\":21000,\"rate\":1.4}]},"
                + "\"facecam_track\":[{\"t_ms\":5000,\"cx\":0.5,\"cy\":0.5,\"radius\":0.1},"
                + "{\"t_ms\":0,\"cx\":0.78,\"cy\":0.80,\"radius\":0.13}],"
                + "\"palette\":{\"mode\":\"multi\",\"colors\":[\"#FF3B30\",\"#FFD60A\"]},"
                + "\"items\":["
                + "{\"id\":\"e2\",\"effect\":\"i_scale_pop\",\"layer\":\"icon\",\"target\":\"overlay\","
                + "\"start_ms\":40000,\"end_ms\":41800,\"anchor\":{\"word_index\":77,\"offset_ms\":0},"
                + "\"position\":{\"zone\":\"upper_right\"},\"component\":{\"type\":\"icon_video\","
                + "\"asset_id\":\"is_1\",\"native_duration_ms\":4000,\"fit\":{\"mode\":\"speed_up\",\"factor\":2.2}},"
                + "\"params\":{}},"
                + "{\"id\":\"e1\",\"effect\":\"" + effect + "\",\"layer\":\"text\",\"target\":\"overlay\","
                + "\"start_ms\":31200,\"end_ms\":32600,\"anchor\":{\"word_index\":42,\"offset_ms\":-80},"
                + "\"position\":{\"zone\":\"upper_center\"},\"component\":{\"type\":\"styled_text\","
                + "\"kind\":\"keyword_echo\",\"text\":\"SEVERE\",\"style_preset\":\"sticker\",\"color_slot\":1},"
                + "\"params\":{\"entry_ms\":200,\"tilt\":-3.5,\"flag\":true,\"entry\":\"pop\"},"
                + "\"sound\":{\"id\":\"pop_1\",\"offset_ms\":0},\"reason\":\"Key claim\"}"
                + "],"
                + "\"captions\":{\"effect\":\"c_word_highlight\",\"emphasis_words\":[12,42,77]},"
                + "\"warnings\":[]}";
    }

    private static final String ASSETS = "[{\"id\":\"is_1\",\"kind\":\"video\",\"url\":\"https://x/y.mp4\","
            + "\"native_duration_ms\":4000,\"width\":512,\"height\":512},"
            + "{\"id\":\"pop_1\",\"kind\":\"sound\",\"url\":\"https://x/pop_1.wav\"}]";

    @Test
    public void validPlanParses() throws Exception {
        Plan p = PlanParser.parse(plan(2, "x_sticker"), contract);
        assertEquals(2, p.contractVersion);
        assertEquals(1, p.planVersion);
        assertEquals("s-1", p.sessionId);
        assertEquals("standard", p.level);
        assertEquals(96400L, p.outputDurationMs);
        assertEquals(1, p.cuts.size());
        assertEquals(5200L, p.cuts.get(0).srcStartMs);
        assertEquals("silence", p.cuts.get(0).reason);
        assertEquals(1.4, p.speedSegments.get(0).rate, 0.0);
        assertEquals("c_word_highlight", p.captions.effect);
        assertTrue(p.captions.isEmphasis(42));
        assertFalse(p.captions.isEmphasis(43));
        assertTrue(p.warnings.isEmpty());
        assertTrue(p.assets.isEmpty());

        // Facecam keys are sorted by time even when the server sent them out of order.
        assertEquals(0L, p.facecamTrack.get(0).tMs);
        assertEquals(0.13, p.facecamTrack.get(0).radius, 0.0);
        assertEquals(5000L, p.facecamTrack.get(1).tMs);

        // Items are sorted by start time and keep the registry's param names and kinds of value.
        assertEquals(2, p.items.size());
        PlanItem text = p.items.get(0);
        assertEquals("e1", text.id);
        assertEquals("x_sticker", text.effect);
        assertEquals(31200L, text.startMs);
        assertEquals(1400L, text.durationMs());
        assertTrue(text.activeAt(31200));
        assertFalse(text.activeAt(32600));
        assertEquals(42, text.anchorWordIndex);
        assertEquals(-80L, text.anchorOffsetMs);
        assertEquals("upper_center", text.zone);
        assertEquals("pop_1", text.soundId);
        assertEquals(200.0, text.paramNumber("entry_ms", 0), 0.0);
        assertEquals(-3.5, text.paramNumber("tilt", 0), 0.0);
        assertEquals("pop", text.paramString("entry", ""));
        assertEquals(Boolean.TRUE, text.params.get("flag"));
        assertEquals(7.0, text.paramNumber("missing", 7.0), 0.0);
        PlanComponent c = text.component;
        assertTrue(c.isText());
        assertEquals("keyword_echo", c.kind);
        assertEquals("SEVERE", c.text);
        assertEquals("sticker", c.stylePreset);
        assertEquals(1, c.colorSlot);

        PlanItem icon = p.items.get(1);
        assertEquals("icon_video", icon.component.type);
        assertEquals("is_1", icon.component.assetId);
        assertEquals(4000, icon.component.nativeDurationMs);
        assertEquals("speed_up", icon.component.fitMode);
        assertEquals(2.2, icon.component.fitFactor, 0.0);
        assertNull(icon.soundId);
        assertNull(icon.reason);
    }

    @Test
    public void theOldLottieNamesAreRefused() {
        expect(plan(2, "x_sticker").replace("\"type\":\"icon_video\"", "\"type\":\"lottie\""),
                PlanParseException.BAD_PLAN);
        String job = "{\"id\":\"j1\",\"status\":\"READY\",\"cost\":2,\"plan\":" + plan(2, "x_sticker")
                + ",\"assets\":" + ASSETS.replace("\"kind\":\"video\"", "\"kind\":\"lottie\"") + "}";
        expect(job, PlanParseException.BAD_PLAN);
    }

    @Test
    public void paletteRotatesThroughTheServersColours() throws Exception {
        Plan p = PlanParser.parse(plan(2, "x_sticker"), contract);
        assertEquals("multi", p.palette.mode);
        assertEquals("#FF3B30", p.palette.colorFor(0));
        assertEquals("#FFD60A", p.palette.colorFor(1));
        assertEquals("#FF3B30", p.palette.colorFor(2));
        assertEquals("#FFD60A", p.palette.colorFor(-1));
    }

    @Test
    public void jobResponseGivesThePlanAndItsAssets() throws Exception {
        String job = "{\"id\":\"j1\",\"status\":\"READY\",\"cost\":2,\"plan\":" + plan(2, "x_sticker")
                + ",\"assets\":" + ASSETS + "}";
        Plan p = PlanParser.parse(job, contract);
        assertEquals(2, p.assets.size());
        assertEquals("https://x/y.mp4", p.asset("is_1").url);
        assertEquals(512, p.asset("is_1").width);
        assertEquals("sound", p.asset("pop_1").kind);
        assertEquals(0, p.asset("pop_1").nativeDurationMs);
        assertNull(p.asset("nope"));
        assertEquals(2, p.items.size());
    }

    @Test
    public void missingPaletteFallsBackToTheContractColours() throws Exception {
        String json = plan(2, "x_sticker").replaceFirst("\"palette\":\\{[^}]*\\},", "");
        Plan p = PlanParser.parse(json, contract);
        assertEquals(contract.getPalette().multi, p.palette.colors);
    }

    @Test
    public void badVersionIsRefusedWithItsOwnCode() {
        expect(plan(3, "x_sticker"), PlanParseException.UNSUPPORTED_VERSION);
    }

    @Test
    public void unknownEffectIsRefused() {
        expect(plan(2, "x_does_not_exist"), PlanParseException.UNKNOWN_EFFECT);
    }

    @Test
    public void unknownCaptionEffectIsRefused() {
        expect(plan(2, "x_sticker").replace("c_word_highlight", "c_made_up"), PlanParseException.UNKNOWN_EFFECT);
    }

    @Test
    public void damagedPlansAreRefused() {
        expect(null, PlanParseException.BAD_PLAN);
        expect("", PlanParseException.BAD_PLAN);
        expect("{\"contract_version\":2}", PlanParseException.BAD_PLAN);
        expect(plan(2, "x_sticker").replace("\"level\":\"standard\"", "\"level\":\"ultra\""),
                PlanParseException.BAD_PLAN);
        expect(plan(2, "x_sticker").replace("\"zone\":\"upper_center\"", "\"zone\":\"nowhere\""),
                PlanParseException.BAD_PLAN);
        expect(plan(2, "x_sticker").replace("\"end_ms\":32600", "\"end_ms\":31000"),
                PlanParseException.BAD_PLAN);
        expect(plan(2, "x_sticker").replace("\"id\":\"e2\"", "\"id\":\"e1\""), PlanParseException.BAD_PLAN);
        expect(plan(2, "x_sticker").replace("\"src_end_ms\":5900", "\"src_end_ms\":5200"),
                PlanParseException.BAD_PLAN);
        // A styled text on an effect that only takes icon clip or icon image components.
        expect(plan(2, "i_scale_pop"), PlanParseException.BAD_PLAN);
    }

    private void expect(String json, String code) {
        try {
            PlanParser.parse(json, contract);
            fail("must be refused with " + code);
        } catch (PlanParseException e) {
            assertEquals(code, e.code);
            assertNotNull(e.getMessage());
        }
    }
}
