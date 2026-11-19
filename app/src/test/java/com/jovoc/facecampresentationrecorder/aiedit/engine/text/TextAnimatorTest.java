package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanComponent;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Text animation: entry shapes, exit, and every duration inside the contract's speed rules. */
public class TextAnimatorTest {

    private EngineContract contract;

    @Before
    public void setUp() throws Exception {
        contract = TestContract.load();
    }

    private TextAnimator animator(String effect, long start, long end, Map<String, Object> params, int words) {
        PlanItem item = new PlanItem("i1", effect, "text", "overlay", start, end, -1, 0, "middle_center",
                PlanComponent.styledText("keyword_echo", "Hello", "sticker", 0), params, null, 0, null);
        return new TextAnimator(item, contract.effect(effect), contract.getSpeed(), words);
    }

    @Test
    public void popOvershootsThenSettles() {
        TextAnimator a = animator("x_pop", 1000, 2500, new HashMap<String, Object>(), 1);
        TextAnimator.State s = new TextAnimator.State();
        assertTrue(a.at(1000, s));
        assertEquals(0.6f, s.scale, 0.01f);
        assertTrue(a.at(1000 + Math.round(a.entryMs * TextAnimator.POP_PEAK_AT), s));
        assertEquals(TextAnimator.POP_PEAK, s.scale, 0.02f);
        assertTrue(a.at(1000 + a.entryMs + 50, s));
        assertEquals(1f, s.scale, 1e-6f);
        assertEquals(1f, s.alpha, 1e-6f);
    }

    @Test
    public void notShownOutsideTheItem() {
        TextAnimator a = animator("x_pop", 1000, 2000, new HashMap<String, Object>(), 1);
        TextAnimator.State s = new TextAnimator.State();
        assertFalse(a.at(999, s));
        assertFalse(a.at(2000, s));
        assertTrue(a.at(1999, s));
    }

    @Test
    public void exitShrinksToEightyFivePercentAndFades() {
        TextAnimator a = animator("x_pop", 1000, 2500, new HashMap<String, Object>(), 1);
        TextAnimator.State s = new TextAnimator.State();
        assertTrue(a.at(2499, s));
        assertEquals(TextAnimator.EXIT_SCALE, s.scale, 0.01f);
        assertEquals(0f, s.alpha, 0.02f);
        assertTrue(a.at(2500 - a.exitMs - 1, s));
        assertEquals(1f, s.scale, 1e-6f);
    }

    @Test
    public void stampHitsFromDoubleSizeThenShakesWithinTheEntryLimit() {
        TextAnimator a = animator("x_stamp", 0, 1500, new HashMap<String, Object>(), 1);
        EngineContract.SpeedRules speed = contract.getSpeed();
        assertTrue(a.entryTotalMs() <= speed.entryMs[1]);
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(TextAnimator.STAMP_FROM, s.scale, 0.01f);
        float maxShake = 0;
        for (long t = a.entryMs; t < a.entryTotalMs(); t++) {
            a.at(t, s);
            assertEquals(1f, s.scale, 1e-6f);
            maxShake = Math.max(maxShake, Math.abs(s.dx));
        }
        assertTrue(maxShake > 0 && maxShake <= TextAnimator.SHAKE_AMPLITUDE + 1e-6f);
    }

    @Test
    public void slideUpStartsLowAndTransparent() {
        TextAnimator a = animator("x_slide_up", 0, 1500, new HashMap<String, Object>(), 1);
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(0.08f, s.dy, 1e-4f);
        assertEquals(0f, s.alpha, 1e-6f);
        a.at(a.entryMs + 10, s);
        assertEquals(0f, s.dy, 1e-6f);
        assertEquals(1f, s.alpha, 1e-6f);
    }

    @Test
    public void markerSweepGrowsTheBar() {
        TextAnimator a = animator("x_marker_sweep", 0, 1500, new HashMap<String, Object>(), 1);
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(0f, s.barProgress, 1e-6f);
        a.at(a.entryMs + 10, s);
        assertEquals(1f, s.barProgress, 1e-6f);
    }

    @Test
    public void wordByWordRevealsAtTheSpokenPace() {
        TextAnimator a = animator("x_one_word", 0, 2500, new HashMap<String, Object>(), 3);
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(1, s.words);
        a.at(contract.getSpeed().textHoldPerWordMs + 1, s);
        assertEquals(2, s.words);
        a.at(2000, s);
        assertEquals(3, s.words);
    }

    @Test
    public void timesAreClampedToTheContractSpeed() {
        EngineContract.SpeedRules speed = contract.getSpeed();
        Map<String, Object> wild = new HashMap<String, Object>();
        wild.put("entry_ms", 5000L);
        wild.put("exit_ms", 1L);
        TextAnimator a = animator("x_pop", 0, 2500, wild, 1);
        assertTrue(a.entryMs >= speed.entryMs[0] && a.entryMs <= speed.entryMs[1]);
        assertTrue(a.exitMs >= speed.exitMs[0] && a.exitMs <= speed.exitMs[1]);
    }

    @Test
    public void everyBasicTextEffectStaysInsideTheSpeedRules() {
        EngineContract.SpeedRules speed = contract.getSpeed();
        for (String id : new String[]{"x_pop", "x_stamp", "x_sticker", "x_3d", "x_glow", "x_label_tag", "x_marker_sweep"}) {
            TextAnimator a = animator(id, 0, 500, new HashMap<String, Object>(), 2);
            assertTrue(id, a.entryTotalMs() <= speed.entryMs[1]);
            assertTrue(id, a.exitMs <= speed.exitMs[1]);
            // A short item still fits entry plus exit
            assertTrue(id, a.entryTotalMs() + a.exitMs <= 500);
        }
    }

    // ---- C2: the new entries. The effect carries no entry of its own; the plan's params.entry picks it.

    private TextAnimator entry(String kind, Map<String, Object> extra, int units, boolean rtl) {
        Map<String, Object> params = new HashMap<String, Object>(extra);
        params.put("entry", kind);
        PlanItem item = new PlanItem("i1", "x_pop", "text", "overlay", 0, 3000, -1, 0, "middle_center",
                PlanComponent.styledText("keyword_echo", "Hello", "sticker", 0), params, null, 0, null);
        return new TextAnimator(item, contract.effect("x_pop"), contract.getSpeed(), 2, units, rtl);
    }

    private TextAnimator entry(String kind) {
        return entry(kind, new HashMap<String, Object>(), 5, false);
    }

    private static final String[] NEW_ENTRIES = {"fade", "slide_left", "slide_right", "slide_top", "slide_bottom",
            "typewriter", "wipe", "drop_bounce", "blur_in", "flip", "glitch"};

    private static void assertSettled(String id, TextAnimator.State s) {
        assertEquals(id, 1f, s.scale, 1e-6f);
        assertEquals(id, 1f, s.alpha, 1e-6f);
        assertEquals(id, 0f, s.dx, 1e-6f);
        assertEquals(id, 0f, s.dy, 1e-6f);
        assertEquals(id, 1f, s.scaleY, 1e-6f);
        assertEquals(id, 0f, s.blur, 1e-6f);
        assertEquals(id, 1f, s.reveal, 1e-6f);
        assertEquals(id, TextAnimator.ALL_WORDS, s.units);
    }

    @Test
    public void everyNewEntryIsKnownAndSettlesToTheStillTextWithNoMotionAfterwards() {
        TextAnimator.State s = new TextAnimator.State();
        for (String kind : NEW_ENTRIES) {
            TextAnimator a = entry(kind);
            assertEquals(kind, kind, a.entry);
            long end = a.entryTotalMs();
            for (long t = end; t < end + 1000; t += 37) {
                assertTrue(kind, a.at(t, s));
                assertSettled(kind, s);
            }
            // A frame just before the entry ends may still move, never past it
            assertTrue(kind, a.at(end - 1, s));
        }
    }

    @Test
    public void unknownEntryStillBecomesPop() {
        assertEquals("pop", entry("spin_wildly").entry);
    }

    @Test
    public void fadeGoesFromTransparentToOpaqueWithoutMoving() {
        TextAnimator a = entry("fade");
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(0f, s.alpha, 1e-6f);
        a.at(a.entryMs / 2, s);
        assertTrue(s.alpha > 0.3f && s.alpha < 1f);
        assertEquals(0f, s.dx, 0f);
        assertEquals(0f, s.dy, 0f);
        a.at(a.entryMs, s);
        assertSettled("fade", s);
    }

    @Test
    public void eachSlideStartsOnItsOwnEdge() {
        TextAnimator.State s = new TextAnimator.State();
        entry("slide_left").at(0, s);
        assertEquals(-TextAnimator.SLIDE_DX, s.dx, 1e-6f);
        assertEquals(0f, s.dy, 0f);
        entry("slide_right").at(0, s);
        assertEquals(TextAnimator.SLIDE_DX, s.dx, 1e-6f);
        entry("slide_top").at(0, s);
        assertEquals(-0.08f, s.dy, 1e-6f);
        assertEquals(0f, s.dx, 0f);
        entry("slide_bottom").at(0, s);
        assertEquals(0.08f, s.dy, 1e-6f);
        for (String kind : new String[]{"slide_left", "slide_right", "slide_top", "slide_bottom"}) {
            TextAnimator a = entry(kind);
            a.at(0, s);
            assertEquals(kind, 0f, s.alpha, 1e-6f);
            // Mid way is between the start and the place
            a.at(a.entryMs / 2, s);
            assertTrue(kind, Math.abs(s.dx) + Math.abs(s.dy) > 0);
            a.at(a.entryMs, s);
            assertSettled(kind, s);
        }
    }

    @Test
    public void slideDistanceIsClampedAndFollowsTheParam() {
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("from_dx", 5.0);
        TextAnimator.State s = new TextAnimator.State();
        entry("slide_right", p, 5, false).at(0, s);
        assertEquals(TextAnimator.SLIDE_DX_MAX, s.dx, 1e-6f);
    }

    @Test
    public void typewriterShowsMoreStepsAsTimePassesAndAllAtTheEnd() {
        TextAnimator a = entry("typewriter", new HashMap<String, Object>(), 12, false);
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(0, s.units);
        assertEquals(1f, s.alpha, 0f);
        int last = 0;
        for (long t = 0; t < a.entryMs; t += 5) {
            a.at(t, s);
            assertTrue(s.units >= last && s.units <= 12);
            last = s.units;
        }
        a.at(a.entryMs / 2, s);
        assertTrue(s.units > 1 && s.units < 12);
        a.at(a.entryMs + 1, s);
        assertEquals(TextAnimator.ALL_WORDS, s.units);
    }

    @Test
    public void typewriterTimeFollowsTheStepCountAndTheTypingLimits() {
        assertEquals(200, entry("typewriter", new HashMap<String, Object>(), 2, false).entryMs);
        assertEquals(300, entry("typewriter", new HashMap<String, Object>(), 10, false).entryMs);
        Map<String, Object> wild = new HashMap<String, Object>();
        wild.put("typing_ms", 9000L);
        assertEquals(450, entry("typewriter", wild, 80, false).entryMs);
        wild.put("typing_ms", 1L);
        assertEquals(200, entry("typewriter", wild, 80, false).entryMs);
    }

    @Test
    public void wipeRevealsFromTheLeftForLeftToRightAndTheRightForRightToLeft() {
        TextAnimator.State s = new TextAnimator.State();
        TextAnimator ltr = entry("wipe", new HashMap<String, Object>(), 5, false);
        ltr.at(0, s);
        assertEquals(0f, s.reveal, 1e-6f);
        assertFalse(s.revealFromRight);
        ltr.at(ltr.entryMs / 2, s);
        assertTrue(s.reveal > 0.3f && s.reveal < 1f);
        TextAnimator rtl = entry("wipe", new HashMap<String, Object>(), 5, true);
        rtl.at(rtl.entryMs / 2, s);
        assertTrue(s.revealFromRight);
        assertTrue(s.reveal > 0f && s.reveal < 1f);
        rtl.at(rtl.entryMs, s);
        assertEquals(1f, s.reveal, 1e-6f);
    }

    @Test
    public void dropBounceStartsAboveAndLandsOnItsPlace() {
        TextAnimator a = entry("drop_bounce");
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(-TextAnimator.DROP_DY, s.dy, 1e-6f);
        boolean moved = false;
        for (long t = 0; t < a.entryMs; t += 3) {
            a.at(t, s);
            assertTrue(s.dy <= 1e-6f);   // bounces back up, never through the floor
            moved |= s.dy < -0.001f;
        }
        assertTrue(moved);
        a.at(a.entryMs, s);
        assertSettled("drop_bounce", s);
    }

    @Test
    public void blurInStartsBlurredAndEndsSharp() {
        TextAnimator a = entry("blur_in");
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(1f, s.blur, 1e-6f);
        assertEquals(0f, s.alpha, 1e-6f);
        a.at(a.entryMs / 2, s);
        assertTrue(s.blur > 0f && s.blur < 1f);
        a.at(a.entryMs, s);
        assertSettled("blur_in", s);
    }

    @Test
    public void flipStartsEdgeOnAndEndsUpright() {
        TextAnimator a = entry("flip");
        TextAnimator.State s = new TextAnimator.State();
        a.at(0, s);
        assertEquals(0f, s.scaleY, 1e-6f);
        a.at(a.entryMs / 2, s);
        assertTrue(s.scaleY > 0.3f && s.scaleY < 1f);
        a.at(a.entryMs, s);
        assertSettled("flip", s);
    }

    @Test
    public void glitchJittersThenSettlesAndRepeatsExactly() {
        TextAnimator a = entry("glitch");
        TextAnimator.State s = new TextAnimator.State();
        float max = 0;
        float[] first = new float[(int) a.entryMs];
        for (int t = 0; t < a.entryMs; t++) {
            a.at(t, s);
            first[t] = s.dx;
            max = Math.max(max, Math.abs(s.dx));
            assertTrue(Math.abs(s.dx) <= TextAnimator.GLITCH_AMPLITUDE + 1e-6f);
        }
        assertTrue(max > 0.001f);
        TextAnimator b = entry("glitch");
        for (int t = 0; t < b.entryMs; t++) {
            b.at(t, s);
            assertEquals(first[t], s.dx, 0f);
        }
        a.at(a.entryMs, s);
        assertSettled("glitch", s);
    }

    @Test
    public void newEntriesStayInsideTheirLimitsAndTheItem() {
        EngineContract.SpeedRules speed = contract.getSpeed();
        Map<String, Object> wild = new HashMap<String, Object>();
        wild.put("entry_ms", 9000L);
        Map<String, Object> tiny = new HashMap<String, Object>();
        tiny.put("entry_ms", 1L);
        for (String kind : NEW_ENTRIES) {
            if (kind.equals("typewriter")) continue;
            long hi = entry(kind, wild, 5, false).entryMs;
            long lo = entry(kind, tiny, 5, false).entryMs;
            assertTrue(kind, hi <= speed.entryMs[1] && lo >= speed.entryMs[0]);
            assertTrue(kind, hi >= lo);
        }
        assertEquals(300, entry("wipe", wild, 5, false).entryMs);
        assertEquals(150, entry("wipe", tiny, 5, false).entryMs);
        assertEquals(260, entry("glitch", wild, 5, false).entryMs);
        assertEquals(120, entry("glitch", tiny, 5, false).entryMs);
        // A short item still fits entry and exit
        PlanItem item = new PlanItem("i2", "x_pop", "text", "overlay", 0, 400, -1, 0, "middle_center",
                PlanComponent.styledText("keyword_echo", "Hello", "sticker", 0), new HashMap<String, Object>(wild), null, 0, null);
        item.params.put("entry", "drop_bounce");
        TextAnimator a = new TextAnimator(item, contract.effect("x_pop"), speed, 1, 5, false);
        assertTrue(a.entryTotalMs() + a.exitMs <= 400);
    }

    @Test
    public void exitOfANewEntryEndsClean() {
        TextAnimator a = entry("flip");
        TextAnimator.State s = new TextAnimator.State();
        a.at(2999, s);
        assertEquals(1f, s.scaleY, 1e-6f);
        assertEquals(TextAnimator.EXIT_SCALE, s.scale, 0.01f);
    }
}
