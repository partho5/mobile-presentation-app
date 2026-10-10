package com.jovoc.facecampresentationrecorder.bgreplace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WarmUpProgressTest {

    @Test
    public void estimateStartsAtZero() {
        assertEquals(0, new WarmUpProgress(true).estimate(0));
    }

    @Test
    public void estimateRisesMonotonicallyAndStaysBelowCap() {
        WarmUpProgress p = new WarmUpProgress(true);
        int previous = 0;
        for (long ms = 0; ms <= 10 * 60_000L; ms += 250) {
            int value = p.estimate(ms);
            assertTrue(value >= previous);
            assertTrue(value < WarmUpProgress.CAP_PERCENT);
            previous = value;
        }
        // Never reaches the cap, but is close to it long past the assumed duration
        assertEquals(WarmUpProgress.CAP_PERCENT - 1, previous);
    }

    @Test
    public void estimateIsMostlyThereAtAssumedDuration() {
        int value = new WarmUpProgress(true).estimate(WarmUpProgress.ASSUMED_WARM_UP_MS);
        assertTrue("was " + value, value >= WarmUpProgress.CAP_PERCENT * 9 / 10);
        assertTrue(value < WarmUpProgress.CAP_PERCENT);
    }

    @Test
    public void realProgressIsMappedAboveTheCap() {
        WarmUpProgress p = new WarmUpProgress(true);
        p.estimate(5_000);
        assertEquals(WarmUpProgress.CAP_PERCENT, p.real(0));
        assertEquals(100, p.real(100));
        assertFalse(p.isEstimating());
    }

    @Test
    public void barNeverGoesBackwardsAcrossThePhases() {
        WarmUpProgress p = new WarmUpProgress(true);
        int previous = p.estimate(40_000);
        for (int real = 0; real <= 100; real += 5) {
            int value = p.real(real);
            assertTrue(value >= previous);
            previous = value;
        }
        // A late estimate tick and a smaller real value cannot pull the bar down
        assertEquals(previous, p.estimate(90_000));
        assertEquals(previous, p.real(10));
    }

    @Test
    public void realProgressIsClamped() {
        WarmUpProgress p = new WarmUpProgress(true);
        assertEquals(WarmUpProgress.CAP_PERCENT, p.real(-20));
        assertEquals(100, p.real(250));
    }

    @Test
    public void noEstimationWhenWarmUpNotNeeded() {
        WarmUpProgress p = new WarmUpProgress(false);
        assertFalse(p.isEstimating());
        assertEquals(0, p.estimate(0));
        assertEquals(0, p.estimate(25_000));
        assertEquals(0, p.real(0));
        assertEquals(37, p.real(37));
        assertEquals(100, p.real(100));
    }
}
