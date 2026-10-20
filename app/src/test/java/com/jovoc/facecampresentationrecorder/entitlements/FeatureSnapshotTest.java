package com.jovoc.facecampresentationrecorder.entitlements;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class FeatureSnapshotTest {

    @After
    public void endAnyRecording() {
        FeatureSnapshot.endRecording();
    }

    @Test
    public void holdsExactlyTheFeaturesItWasGiven() {
        FeatureSnapshot s = FeatureSnapshot.of(Feature.CROP_4_5, Feature.BG_REPLACE);

        assertTrue(s.has(Feature.CROP_4_5));
        assertTrue(s.has(Feature.BG_REPLACE));
        assertFalse(s.has(Feature.CROP_1_1));
        assertFalse(s.has(Feature.BG_CUSTOM));
        assertFalse(s.has("unknown_feature"));
    }

    @Test
    public void withoutAnInitialisedPlanTakeIsTheFreeDefaults() {
        FeatureSnapshot s = FeatureSnapshot.take();

        assertTrue(s.has(Feature.CROP_9_16));
        assertTrue(s.has(Feature.FLOATING_CAM));
        assertFalse(s.has(Feature.CROP_4_5));
        assertFalse(s.has(Feature.BG_REPLACE));
        assertFalse(s.has(Feature.BG_CUSTOM));
    }

    @Test
    public void aRunningRecordingKeepsItsSnapshotWhateverThePlanSaysNow() {
        // The live plan reads as Free here; the recording started on a plan with 4:5 and no 9:16.
        FeatureSnapshot.beginRecording(FeatureSnapshot.of(Feature.CROP_4_5));

        assertTrue(FeatureSnapshot.allows(Feature.CROP_4_5));
        assertFalse(FeatureSnapshot.allows(Feature.CROP_9_16));
    }

    @Test
    public void afterTheRecordingTheLivePlanAppliesAgain() {
        FeatureSnapshot.beginRecording(FeatureSnapshot.of(Feature.CROP_4_5));
        FeatureSnapshot.endRecording();

        assertFalse(FeatureSnapshot.allows(Feature.CROP_4_5));
        assertTrue(FeatureSnapshot.allows(Feature.CROP_9_16));
    }

    @Test
    public void beginRecordingReturnsTheSnapshotItKeeps() {
        FeatureSnapshot s = FeatureSnapshot.of(Feature.BG_REPLACE);

        assertSame(s, FeatureSnapshot.beginRecording(s));
    }
}
