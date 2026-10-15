package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public class ProgressThrottleTest {

    private final AtomicLong now = new AtomicLong(0);

    private ProgressThrottle throttle(long total) {
        return new ProgressThrottle(total, 100, 250, now::get);
    }

    @Test
    public void firstCountAlwaysPasses() {
        assertTrue(throttle(1000).accept(0));
    }

    @Test
    public void needsBothAStepAndAnInterval() {
        ProgressThrottle t = throttle(10_000);
        assertTrue(t.accept(0));
        now.set(1000);
        assertFalse(t.accept(50));   // not a full step
        now.set(100);
        assertFalse(t.accept(500));  // a step, but too soon
        now.set(1250);
        assertTrue(t.accept(500));
    }

    @Test
    public void neverGoesBackwardsAndTheEndAlwaysPasses() {
        ProgressThrottle t = throttle(1000);
        assertTrue(t.accept(600));
        now.set(5000);
        assertFalse(t.accept(500));
        assertTrue(t.accept(1000));
        assertFalse(t.accept(1000));
    }
}
