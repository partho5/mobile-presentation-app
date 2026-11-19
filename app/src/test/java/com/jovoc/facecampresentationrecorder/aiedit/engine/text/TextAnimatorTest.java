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

    // ---- C3: holds and exits. The plan's params.hold / hold_ms and params.exit / exit_ms / exit_dir pick them.

    private static final String[] HOLDS = {"float", "pulse", "shake", "shimmer", "neon_flicker"};
    private static final String[] EXITS = {"fade", "slide_out", "shrink", "pop_out", "typewriter_delete", "wipe_out"};

    private TextAnimator motion(Map<String, Object> params, long start, long end, int units, boolean rtl) {
        Map<String, Object> p = new HashMap<String, Object>(params);
        if (!p.containsKey("entry")) p.put("entry", "fade");
        PlanItem item = new PlanItem("i1", "x_pop", "text", "overlay", start, end, -1, 0, "middle_center",
                PlanComponent.styledText("keyword_echo", "Hello", "sticker", 0), p, null, 0, null);
        return new TextAnimator(item, contract.effect("x_pop"), contract.getSpeed(), 2, units, rtl);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void everyHoldIsKnownRepeatsEachCycleAndIsStillBeforeItAndAfterNothing() {
        TextAnimator.State s = new TextAnimator.State();
        TextAnimator.State t = new TextAnimator.State();
        for (String h : HOLDS) {
            TextAnimator a = motion(params("hold", h), 0, 6000, 5, false);
            assertEquals(h, h, a.hold);
            assertTrue(h, a.holdMs > 0);
            // No hold while the entry plays
            a.at(a.entryTotalMs() - 1, s);
            assertEquals(h, -1f, s.shimmer, 0f);
            // The same moment of two cycles looks the same
            long base = a.entryTotalMs() + 37;
            a.at(base, s);
            a.at(base + 3 * a.holdMs, t);
            assertEquals(h, s.dx, t.dx, 1e-5f);
            assertEquals(h, s.dy, t.dy, 1e-5f);
            assertEquals(h, s.scale, t.scale, 1e-5f);
            assertEquals(h, s.alpha, t.alpha, 1e-5f);
            assertEquals(h, s.shimmer, t.shimmer, 1e-5f);
        }
    }

    @Test
    public void withoutAHoldTheTextStaysStill() {
        TextAnimator a = motion(params("hold", "none"), 0, 3000, 5, false);
        assertEquals(null, a.hold);
        TextAnimator b = motion(params("hold", "spin"), 0, 3000, 5, false);
        assertEquals(null, b.hold);
        TextAnimator.State s = new TextAnimator.State();
        for (long t = a.entryTotalMs(); t < 3000 - a.exitMs; t += 41) {
            a.at(t, s);
            assertSettled("none", s);
            assertEquals(-1f, s.shimmer, 0f);
        }
    }

    @Test
    public void floatPulseShakeStartAtRestAndMoveInsideTheirAmplitude() {
        TextAnimator.State s = new TextAnimator.State();
        TextAnimator f = motion(params("hold", "float"), 0, 8000, 5, false);
        float lo = 0, hi = 0;
        for (long t = f.entryTotalMs(); t < f.entryTotalMs() + f.holdMs; t++) {
            f.at(t, s);
            lo = Math.min(lo, s.dy);
            hi = Math.max(hi, s.dy);
            assertEquals(0f, s.dx, 0f);
        }
        assertEquals(TextAnimator.FLOAT_AMPLITUDE, hi, 1e-3f);
        assertEquals(-TextAnimator.FLOAT_AMPLITUDE, lo, 1e-3f);
        f.at(f.entryTotalMs(), s);
        assertEquals(0f, s.dy, 1e-6f);

        TextAnimator p = motion(params("hold", "pulse"), 0, 8000, 5, false);
        float maxScale = 1;
        for (long t = p.entryTotalMs(); t < p.entryTotalMs() + p.holdMs; t++) {
            p.at(t, s);
            maxScale = Math.max(maxScale, s.scale);
            assertTrue(s.scale >= 1f);
        }
        assertEquals(1 + TextAnimator.PULSE_GROW, maxScale, 1e-3f);
        p.at(p.entryTotalMs(), s);
        assertEquals(1f, s.scale, 1e-6f);

        TextAnimator k = motion(params("hold", "shake"), 0, 8000, 5, false);
        float maxDx = 0;
        for (long t = k.entryTotalMs(); t < k.entryTotalMs() + 4 * k.holdMs; t++) {
            k.at(t, s);
            maxDx = Math.max(maxDx, Math.abs(s.dx));
        }
        assertTrue(maxDx > 0.9f * TextAnimator.HOLD_SHAKE_AMPLITUDE && maxDx <= TextAnimator.HOLD_SHAKE_AMPLITUDE + 1e-6f);
    }

    @Test
    public void shimmerSweepsLeftToRightThenRestsAndNeonFlickerDipsAndRecovers() {
        TextAnimator.State s = new TextAnimator.State();
        TextAnimator a = motion(params("hold", "shimmer"), 0, 8000, 5, false);
        long base = a.entryTotalMs();
        float last = -0.5f;
        int frames = 0;
        for (long t = base; t < base + a.holdMs; t++) {
            a.at(t, s);
            if (s.shimmer < 0) continue;
            assertTrue(s.shimmer >= last);
            assertTrue(s.shimmer <= 1f);
            last = s.shimmer;
            frames++;
        }
        assertTrue(frames > 0 && last > 0.95f);
        a.at(base + a.holdMs - 1, s);
        assertEquals(-1f, s.shimmer, 0f);

        TextAnimator n = motion(params("hold", "neon_flicker"), 0, 8000, 5, false);
        float min = 1;
        for (long t = n.entryTotalMs(); t < n.entryTotalMs() + n.holdMs; t++) {
            n.at(t, s);
            min = Math.min(min, s.alpha);
            assertTrue(s.alpha <= 1f && s.alpha > 0.3f);
        }
        assertEquals(TextAnimator.FLICKER_DEEP, min, 1e-6f);
        n.at(n.entryTotalMs() + n.holdMs / 5 + 1, s);   // between dips
        assertEquals(1f, s.alpha, 1e-6f);
    }

    @Test
    public void holdCycleIsClampedToItsLimits() {
        long[][] limits = {{1200, 3000}, {500, 1200}, {80, 200}, {600, 1600}, {80, 400}};
        for (int i = 0; i < HOLDS.length; i++) {
            assertEquals(HOLDS[i], limits[i][1], motion(params("hold", HOLDS[i], "hold_ms", 99999L), 0, 9000, 5, false).holdMs);
            assertEquals(HOLDS[i], limits[i][0], motion(params("hold", HOLDS[i], "hold_ms", 1L), 0, 9000, 5, false).holdMs);
        }
        assertEquals(1800, motion(params("hold", "float"), 0, 9000, 5, false).holdMs);
    }

    @Test
    public void noExitNamedKeepsTheShippedFadeAndShrink() {
        TextAnimator a = motion(params("hold", "none"), 0, 3000, 5, false);
        assertEquals(null, a.exit);
        TextAnimator b = motion(params("exit", "teleport"), 0, 3000, 5, false);
        assertEquals(null, b.exit);
        TextAnimator.State s = new TextAnimator.State();
        b.at(2999, s);
        assertEquals(TextAnimator.EXIT_SCALE, s.scale, 0.01f);
        assertEquals(0f, s.alpha, 0.02f);
    }

    @Test
    public void everyExitStartsClean_andEndsExactlyAtTheItemsEnd_withNothingLeftToSee() {
        TextAnimator.State s = new TextAnimator.State();
        for (String e : EXITS) {
            TextAnimator a = motion(params("exit", e, "exit_ms", 150L), 1000, 4000, 5, false);
            assertEquals(e, e, a.exit);
            long exitStart = 4000 - a.exitMs;
            // The frame before the exit is still, and the exit's first frame has barely moved
            assertTrue(e, a.at(exitStart - 1, s));
            assertSettled(e, s);
            assertTrue(e, a.at(exitStart, s));
            assertTrue(e, s.alpha >= 0.99f && Math.abs(s.dx) < 1e-4 && Math.abs(s.dy) < 1e-4);
            // The last frame has nothing left to see
            assertTrue(e, a.at(3999, s));
            boolean gone = s.alpha < 0.02f || s.units == 0 || s.reveal < 0.02f;
            assertTrue(e, gone);
            // And it is never drawn at or after the end
            assertFalse(e, a.at(4000, s));
            assertFalse(e, a.at(4100, s));
        }
    }

    @Test
    public void exitNeverRunsPastTheItemAndFollowsItsOwnLimits() {
        EngineContract.SpeedRules speed = contract.getSpeed();
        for (String e : EXITS) {
            long hi = motion(params("exit", e, "exit_ms", 9000L), 0, 3000, 5, false).exitMs;
            long lo = motion(params("exit", e, "exit_ms", 1L), 0, 3000, 5, false).exitMs;
            assertEquals(e, 200, hi);
            assertEquals(e, 100, lo);
            assertTrue(e, hi <= speed.exitMs[1] && lo >= speed.exitMs[0]);
            // A short item: entry, then exit, inside it; the exit is at most half
            TextAnimator shortItem = motion(params("exit", e, "exit_ms", 200L, "entry_ms", 300L), 0, 300, 5, false);
            assertTrue(e, shortItem.exitMs <= 150);
            assertTrue(e, shortItem.entryTotalMs() + shortItem.exitMs <= 300);
        }
        assertEquals(150, motion(params("exit", "fade"), 0, 3000, 5, false).exitMs);
        assertEquals(200, motion(params("exit", "typewriter_delete"), 0, 3000, 5, false).exitMs);
    }

    @Test
    public void slideOutLeavesBySideAndFadesAway() {
        TextAnimator.State s = new TextAnimator.State();
        String[] sides = {"left", "right", "top", "bottom"};
        for (String side : sides) {
            TextAnimator a = motion(params("exit", "slide_out", "exit_dir", side), 0, 3000, 5, false);
            a.at(2999, s);
            switch (side) {
                case "left": assertTrue(s.dx < -0.1f && s.dy == 0f); break;
                case "right": assertTrue(s.dx > 0.1f && s.dy == 0f); break;
                case "top": assertTrue(s.dy < -0.05f && s.dx == 0f); break;
                default: assertTrue(s.dy > 0.05f && s.dx == 0f);
            }
            assertTrue(side, s.alpha < 0.05f);
            a.at(3000 - a.exitMs + a.exitMs / 2, s);
            assertTrue(side, s.alpha > 0.2f && s.alpha < 0.8f);
        }
        // No exit_dir: the side the entry came from, else the bottom
        TextAnimator fromLeft = motion(params("entry", "slide_left", "exit", "slide_out"), 0, 3000, 5, false);
        assertEquals("left", fromLeft.exitDir);
        assertEquals("bottom", motion(params("exit", "slide_out"), 0, 3000, 5, false).exitDir);
        assertEquals("bottom", motion(params("exit", "slide_out", "exit_dir", "diagonal"), 0, 3000, 5, false).exitDir);
    }

    @Test
    public void shrinkAndPopOutAndFadeChangeScaleAndAlphaAsNamed() {
        TextAnimator.State s = new TextAnimator.State();
        TextAnimator shrink = motion(params("exit", "shrink"), 0, 3000, 5, false);
        shrink.at(2999, s);
        assertEquals(TextAnimator.SHRINK_END, s.scale, 0.02f);
        assertTrue(s.alpha < 0.02f);

        TextAnimator pop = motion(params("exit", "pop_out"), 0, 3000, 5, false);
        float peak = 0;
        for (long t = 3000 - pop.exitMs; t < 3000; t++) {
            pop.at(t, s);
            peak = Math.max(peak, s.scale);
        }
        assertEquals(TextAnimator.POP_OUT_PEAK, peak, 0.01f);
        pop.at(2999, s);
        assertEquals(TextAnimator.POP_OUT_END, s.scale, 0.02f);
        assertTrue(s.alpha < 0.02f);

        TextAnimator fade = motion(params("exit", "fade"), 0, 3000, 5, false);
        fade.at(2999, s);
        assertEquals(1f, s.scale, 1e-6f);
        assertTrue(s.alpha < 0.02f);
    }

    @Test
    public void typewriterDeleteRemovesStepsFromTheEndInOrder() {
        TextAnimator.State s = new TextAnimator.State();
        TextAnimator a = motion(params("exit", "typewriter_delete"), 0, 3000, 5, false);
        long start = 3000 - a.exitMs;
        a.at(start, s);
        assertEquals(5, s.units);
        int last = 5;
        for (long t = start; t < 3000; t++) {
            a.at(t, s);
            assertTrue(s.units <= last && s.units >= 0);
            last = s.units;
        }
        assertEquals(0, last);
        a.at(2999, s);
        assertEquals(0f, s.alpha, 0f);
        a.at(start + a.exitMs / 2, s);
        assertTrue(s.units > 0 && s.units < 5);
    }

    @Test
    public void wipeOutIsTheWipeBackwardsFromTheReadingDirectionEdge() {
        TextAnimator.State s = new TextAnimator.State();
        TextAnimator ltr = motion(params("exit", "wipe_out"), 0, 3000, 5, false);
        ltr.at(3000 - ltr.exitMs, s);
        assertEquals(1f, s.reveal, 1e-6f);
        assertFalse(s.revealFromRight);
        ltr.at(3000 - ltr.exitMs / 2, s);
        assertTrue(s.reveal > 0.3f && s.reveal < 0.7f);
        ltr.at(2999, s);
        assertTrue(s.reveal < 0.02f);
        TextAnimator rtl = motion(params("exit", "wipe_out"), 0, 3000, 5, true);
        rtl.at(3000 - rtl.exitMs / 2, s);
        assertTrue(s.revealFromRight);
        assertTrue(s.reveal < 1f);
    }

    @Test
    public void anExitRunsOnTopOfTheHoldWithoutAJump() {
        TextAnimator.State s = new TextAnimator.State();
        TextAnimator a = motion(params("hold", "float", "exit", "shrink"), 0, 4000, 5, false);
        TextAnimator.State before = new TextAnimator.State();
        a.at(4000 - a.exitMs - 1, before);
        a.at(4000 - a.exitMs, s);
        assertEquals(before.dy, s.dy, 0.001f);
        assertEquals(before.scale, s.scale, 0.01f);
        // The shimmer band is gone once the exit starts
        TextAnimator b = motion(params("hold", "shimmer", "hold_ms", 600L, "exit", "fade"), 0, 4000, 5, false);
        for (long t = 4000 - b.exitMs; t < 4000; t++) {
            b.at(t, s);
            assertEquals(-1f, s.shimmer, 0f);
        }
    }

    @Test
    public void holdsAndExitsReplayIdentically() {
        TextAnimator a = motion(params("hold", "neon_flicker", "exit", "pop_out"), 0, 4000, 5, false);
        TextAnimator b = motion(params("hold", "neon_flicker", "exit", "pop_out"), 0, 4000, 5, false);
        TextAnimator.State x = new TextAnimator.State();
        TextAnimator.State y = new TextAnimator.State();
        for (long t = 0; t < 4000; t += 7) {
            assertEquals(a.at(t, x), b.at(t, y));
            assertEquals(x.alpha, y.alpha, 0f);
            assertEquals(x.scale, y.scale, 0f);
        }
    }
}
