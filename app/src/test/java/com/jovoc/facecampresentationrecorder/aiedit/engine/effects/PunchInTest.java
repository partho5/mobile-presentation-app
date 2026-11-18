package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** {@code v_punch_in}: jump, hold, settle back, all inside the contract's beat time. */
public class PunchInTest {

    private EngineContract contract;

    @Before
    public void setUp() throws Exception {
        contract = TestContract.load();
    }

    private PlanItem item(String effect, long start, long end, Map<String, Object> params) {
        return new PlanItem("p1", effect, "video", "full_frame", start, end, -1, 0, null, null, params, null, 0, null);
    }

    @Test
    public void zoomsInHoldsAndReturnsToFullFrame() {
        PunchIn p = new PunchIn(item("v_punch_in", 1000, 1300, new HashMap<String, Object>()),
                contract.effect("v_punch_in"), contract.getSpeed());
        assertEquals(1.0, p.scaleAt(999), 1e-9);
        assertEquals(1.0, p.scaleAt(1000), 1e-9);
        assertEquals(p.scaleTo, p.scaleAt(1000 + Math.round(p.durationMs * PunchIn.IN_SHARE) + 5), 1e-6);
        assertEquals(1.0, p.scaleAt(1000 + p.durationMs), 1e-9);
        assertTrue(p.scaleTo >= 1.05 && p.scaleTo <= 1.25);
    }

    @Test
    public void scaleAndLengthAreClamped() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("scale_to", 3.0);
        PunchIn p = new PunchIn(item("v_punch_in", 0, 5000, params), contract.effect("v_punch_in"), contract.getSpeed());
        assertEquals(1.25, p.scaleTo, 1e-9);
        assertTrue(p.durationMs <= contract.getSpeed().beatMs[1]);
        assertTrue(p.durationMs >= contract.getSpeed().beatMs[0]);
    }

    @Test
    public void appliesAZoomOnlyWhileActive() {
        PunchIn p = new PunchIn(item("v_punch_in", 0, 300, new HashMap<String, Object>()),
                contract.effect("v_punch_in"), contract.getSpeed());
        VideoTransform t = new VideoTransform();
        assertTrue(p.apply(100, t));
        assertTrue(t.scale > 1);
        t.reset();
        assertFalse(p.apply(500, t));
        assertTrue(t.isIdentity());
    }

    @Test
    public void factoryBuildsPunchInsAndSkipsTheRest() {
        assertNotNull(EffectFactory.create(item("v_punch_in", 0, 300, new HashMap<String, Object>()), contract));
        assertNull(EffectFactory.create(item("v_shake", 0, 300, new HashMap<String, Object>()), contract));
    }
}
