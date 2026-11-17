package com.jovoc.facecampresentationrecorder.aiedit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.Capabilities;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.ContractException;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.OutputSpec;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class EngineContractTest {

    @Test
    public void bundledContractLoads() throws Exception {
        EngineContract c = TestContract.load();
        assertEquals(1, c.getVersion());
        assertEquals(3, c.level("basic").maxFeatureEffects);
        assertEquals(0, c.level("basic").maxIcons);
        assertFalse(c.level("basic").transitions);
        assertEquals(Arrays.asList("keyword_echo", "hook", "commentary", "complementary"),
                c.level("heavy").textKinds);
        assertTrue(c.level("heavy").writer);
        assertNull(c.level("ultra"));
        assertEquals(2500, c.getSpeed().textHoldMs(100));
        assertEquals(500 + 150 * 3, c.getSpeed().textHoldMs(3));
        assertEquals(3.0, c.getSpeed().lottieMaxFactor, 0.0);
        assertEquals(300, c.getProtection().paddingMs);
        assertEquals(5, c.getPalette().multi.size());
        assertEquals(7, c.getSounds().size());
        assertTrue(c.getPhrases().size() >= 40);
        assertNotNull(c.getPhrases().get(0).text.get("en"));
        assertEquals(0.5, c.zone("upper_center")[0], 0.0);
        assertEquals(0.2, c.zone("upper_center")[1], 0.0);
        assertNull(c.zone("nowhere"));
    }

    @Test
    public void ninesixteenIsFirstInTheOutputs() throws Exception {
        EngineContract c = TestContract.load();
        assertEquals(Arrays.asList("9:16", "4:5", "1:1"), c.outputRatios());
        OutputSpec o = c.output("9:16");
        assertEquals(1080, o.width);
        assertEquals(1920, o.height);
        assertEquals(12000000, o.bitrate);
        assertEquals(0.62, o.safeY1, 0.0);
        assertEquals(720, c.output("4:5").width);
        assertEquals(900, c.output("4:5").height);
        // The zone centre maps into the safe rectangle: 0.5 across 0.06..0.94 is 0.5.
        assertEquals(0.5, o.zoneX(0.5), 1e-9);
        assertEquals(0.10 + 0.20 * 0.52, o.zoneY(0.20), 1e-9);
    }

    @Test
    public void registryHasTheFirstWaveWithDefaults() throws Exception {
        EngineContract c = TestContract.load();
        RegistryEntry punch = c.effect("v_punch_in");
        assertNotNull(punch);
        assertEquals("basic", punch.minLevel);
        assertEquals(1.15, punch.defaultNumber("scale_to", 0), 0.0);
        assertEquals("out_back", punch.defaultParams.get("ease"));
        assertTrue(punch.withinLimit("scale_to", 1.2));
        assertFalse(punch.withinLimit("scale_to", 1.5));
        assertTrue(punch.withinLimit("no_limit_here", 99));
        assertEquals(150, punch.minDurationMs);
        assertEquals(400, punch.maxDurationMs);
        for (String id : Arrays.asList("v_push_in", "v_alt_crop", "l_flash", "l_rgb_glitch",
                "y_face_grow_slide_shrink", "y_slide_grow_face_shrink", "t_jump_cut", "x_sticker",
                "i_scale_pop", "tr_whip", "c_word_highlight")) {
            assertNotNull(id, c.effect(id));
        }
        assertNull(c.effect("v_does_not_exist"));
        assertTrue(c.effect("x_sticker").brandColorable);
        assertEquals(Arrays.asList("lottie", "icon_image"), c.effect("i_scale_pop").allowedComponents);
        assertEquals(Arrays.asList("person_segmentation"), c.effect("p_bg_blur").requires);
    }

    @Test
    public void effectsForLevelFiltersByLevelAndCapabilities() throws Exception {
        EngineContract c = TestContract.load();
        List<RegistryEntry> basic = c.effectsForLevel("basic", null);
        assertTrue(basic.size() > 0);
        for (RegistryEntry e : basic) assertEquals("basic", e.minLevel);
        assertTrue(c.effectsForLevel("heavy", null).size() > c.effectsForLevel("standard", null).size());
        assertTrue(c.effectsForLevel("standard", null).size() > basic.size());

        List<RegistryEntry> mine = c.effectsForLevel("basic", Capabilities.current());
        assertEquals(2, mine.size());
        assertTrue(Arrays.asList(mine.get(0).id, mine.get(1).id).containsAll(
                Arrays.asList("t_jump_cut", "c_word_highlight")));
        assertEquals(0, c.effectsForLevel("nope", null).size());
    }

    @Test
    public void textLooksKeepTheExplicitShape() throws Exception {
        EngineContract c = TestContract.load();
        assertEquals(6, c.getTextLooks().size());

        TextLook sticker = c.textLook("sticker");
        assertEquals("accent", sticker.fill.role);
        assertFalse(sticker.fill.isGradient());
        assertEquals("ink", sticker.stroke.color);
        assertEquals(0.14, sticker.stroke.width, 0.0);
        assertEquals(0.05, sticker.shadow.dx, 0.0);
        assertEquals(0.0, sticker.shadow.blur, 0.0);
        assertNull(sticker.glow);
        assertNull(sticker.extrude);
        assertNull(sticker.background);
        assertNull(sticker.bar);
        assertEquals(-6.0, sticker.tiltDeg[0], 0.0);
        assertEquals(6.0, sticker.tiltDeg[1], 0.0);

        TextLook pill = c.textLook("label_pill");
        assertEquals("on_accent", pill.fill.role);
        assertNull(pill.stroke);
        assertEquals(0.5, pill.background.radius, 0.0);
        assertEquals(0.35, pill.background.padX, 0.0);
        assertEquals(0.35, pill.shadow.alpha, 0.0);
        assertNull(pill.tiltDeg);

        TextLook threeD = c.textLook("3d");
        assertEquals(6, threeD.extrude.copies);
        assertEquals("accent_dark", threeD.extrude.color);

        assertEquals(0.35, c.textLook("glow").glow.blur, 0.0);

        TextLook gradient = c.textLook("gradient");
        assertTrue(gradient.fill.isGradient());
        assertNull(gradient.fill.role);
        assertEquals("paper", gradient.fill.gradientFrom);
        assertEquals("accent", gradient.fill.gradientTo);

        TextLook marker = c.textLook("highlighter");
        assertEquals("ink", marker.fill.role);
        assertEquals(0.55, marker.bar.height, 0.0);
        assertEquals(0.85, marker.bar.alpha, 0.0);
        assertNull(c.textLook("missing"));
    }

    @Test
    public void unknownVersionIsRefused() throws Exception {
        String json = TestContract.json().replaceFirst("\"contract_version\": 1", "\"contract_version\": 2");
        try {
            EngineContract.fromJson(json);
            fail("version 2 must be refused");
        } catch (ContractException expected) {
            // refused, as the card requires
        }
    }

    @Test
    public void damagedOrMissingTextIsRefused() {
        for (String bad : new String[]{null, "", "{}", "[]", "{\"contract_version\":1}"}) {
            try {
                EngineContract.fromJson(bad);
                fail("must be refused: " + bad);
            } catch (ContractException expected) {
                // refused
            }
        }
    }

    @Test
    public void capabilitiesStartWithCaptionsAndJumpCutAndAreKnownToTheContract() throws Exception {
        Capabilities caps = Capabilities.current();
        assertTrue(caps.canRender("t_jump_cut"));
        assertTrue(caps.canRender("c_word_highlight"));
        assertFalse(caps.canRender("v_punch_in"));
        assertTrue(caps.knownTo(TestContract.load()));
        assertFalse(new Capabilities(Arrays.asList("v_made_up"), Arrays.<String>asList())
                .knownTo(TestContract.load()));
    }

    @Test
    public void levelIndexOrdersTheLevels() {
        assertEquals(0, EngineContract.levelIndex("basic"));
        assertEquals(2, EngineContract.levelIndex("heavy"));
        assertEquals(-1, EngineContract.levelIndex("x"));
    }
}
