package com.jovoc.facecampresentationrecorder.entitlements;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;

public class FeatureTest {

    @Test
    public void plansRankFromFreeUp() {
        assertEquals(Arrays.asList("free", "starter", "pro", "agency"), Feature.PLAN_ORDER);
        assertEquals(0, Feature.rank("free"));
        assertEquals(1, Feature.rank("starter"));
        assertEquals(2, Feature.rank("pro"));
        assertEquals(3, Feature.rank("agency"));
    }

    @Test
    public void unknownPlansRankMinusOneAndCaseDoesNotMatter() {
        assertEquals(-1, Feature.rank("enterprise"));
        assertEquals(-1, Feature.rank(""));
        assertEquals(-1, Feature.rank(null));
        assertEquals(2, Feature.rank("PRO"));
    }

    @Test
    public void aPlanCoversItsOwnLevelAndBelow() {
        assertTrue(Feature.planCovers("pro", "pro"));
        assertTrue(Feature.planCovers("pro", "starter"));
        assertTrue(Feature.planCovers("agency", "free"));
        assertFalse(Feature.planCovers("starter", "pro"));
        assertFalse(Feature.planCovers("free", "starter"));
    }

    @Test
    public void unknownPlansCoverNothingAndAreCoveredByNothing() {
        assertFalse(Feature.planCovers("enterprise", "free"));
        assertFalse(Feature.planCovers("pro", "enterprise"));
        assertFalse(Feature.planCovers(null, "free"));
        assertFalse(Feature.planCovers("pro", null));
    }

    @Test
    public void startingMapIsTheDecidedOne() {
        assertEquals("free", Feature.DEFAULT_LOWEST.get(Feature.CROP_9_16));
        assertEquals("free", Feature.DEFAULT_LOWEST.get(Feature.FLOATING_CAM));
        assertEquals("free", Feature.DEFAULT_LOWEST.get(Feature.DRAWING_BOARD));
        assertEquals("free", Feature.DEFAULT_LOWEST.get(Feature.SLIDE_INK));
        assertEquals("free", Feature.DEFAULT_LOWEST.get(Feature.AUTO_PUBLISH));
        assertEquals("starter", Feature.DEFAULT_LOWEST.get(Feature.CROP_4_5));
        assertEquals("starter", Feature.DEFAULT_LOWEST.get(Feature.CROP_1_1));
        assertEquals("pro", Feature.DEFAULT_LOWEST.get(Feature.BG_REPLACE));
        assertEquals("pro", Feature.DEFAULT_LOWEST.get(Feature.BG_CUSTOM));
        assertEquals("starter", Feature.DEFAULT_LOWEST.get(Feature.AI_CAPTIONS));
        assertEquals(10, Feature.DEFAULT_LOWEST.size());
        // 9:16 comes first wherever aspect ratios are listed.
        ArrayList<String> order = new ArrayList<>(Feature.DEFAULT_LOWEST.keySet());
        assertTrue(order.indexOf(Feature.CROP_9_16) < order.indexOf(Feature.CROP_4_5));
        assertTrue(order.indexOf(Feature.CROP_4_5) < order.indexOf(Feature.CROP_1_1));
    }

    @Test
    public void everyDefaultPlanIsAKnownPlan() {
        for (String plan : Feature.DEFAULT_LOWEST.values()) assertTrue(plan, Feature.rank(plan) >= 0);
    }

    @Test
    public void onlyDefaultFreeFeaturesAreFreeByDefault() {
        assertTrue(Feature.isFreeByDefault(Feature.CROP_9_16));
        assertTrue(Feature.isFreeByDefault(Feature.AUTO_PUBLISH));
        assertFalse(Feature.isFreeByDefault(Feature.CROP_4_5));
        assertFalse(Feature.isFreeByDefault(Feature.BG_REPLACE));
        assertFalse(Feature.isFreeByDefault("something_new"));
        assertFalse(Feature.isFreeByDefault(null));
    }

    @Test
    public void defaultNameCapitalisesTheKey() {
        assertEquals("Pro", Feature.defaultName("pro"));
        assertEquals("Free", Feature.defaultName("free"));
        assertEquals("Free", Feature.defaultName(null));
        assertEquals("Free", Feature.defaultName(""));
        assertNull(Feature.DEFAULT_LOWEST.get("nope"));
    }
}
