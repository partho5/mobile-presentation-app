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
}
