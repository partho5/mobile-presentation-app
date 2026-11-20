package com.jovoc.facecampresentationrecorder.account;

import static org.junit.Assert.assertEquals;

import com.jovoc.facecampresentationrecorder.account.BrandSwitch.Outcome;

import org.junit.Test;

public class BrandSwitchTest {

    @Test
    public void theSameStoredBrandIsSame() {
        assertEquals(Outcome.SAME, BrandSwitch.decide("b1", "b1", "b2", false));
    }

    @Test
    public void aDifferentBrandChanges() {
        assertEquals(Outcome.CHANGED, BrandSwitch.decide("b1", "b2", "b1", false));
    }

    @Test
    public void nothingStoredPickingTheServerDefaultIsSame() {
        assertEquals(Outcome.SAME, BrandSwitch.decide(null, "b1", "b1", false));
        assertEquals(Outcome.SAME, BrandSwitch.decide("", "b1", "b1", false));
    }

    @Test
    public void nothingStoredPickingAnotherBrandChanges() {
        assertEquals(Outcome.CHANGED, BrandSwitch.decide(null, "b2", "b1", false));
    }

    @Test
    public void nothingStoredAndNoKnownDefaultChanges() {
        assertEquals(Outcome.CHANGED, BrandSwitch.decide(null, "b1", null, false));
        assertEquals(Outcome.CHANGED, BrandSwitch.decide("", "b1", "", false));
    }

    @Test
    public void aRecordingBlocksEveryPick() {
        assertEquals(Outcome.BLOCKED_RECORDING, BrandSwitch.decide("b1", "b2", "b1", true));
        assertEquals(Outcome.BLOCKED_RECORDING, BrandSwitch.decide("b1", "b1", "b1", true));
        assertEquals(Outcome.BLOCKED_RECORDING, BrandSwitch.decide(null, "b1", null, true));
    }
}
