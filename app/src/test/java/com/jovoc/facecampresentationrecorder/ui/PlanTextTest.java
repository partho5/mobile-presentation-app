package com.jovoc.facecampresentationrecorder.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.entitlements.Feature;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The pure parts of the upgrade UI: prices, which tiers the plans sheet lists, the crown rule. */
public class PlanTextTest {

    @Test
    public void wholeAmountsHaveNoDecimals() {
        assertEquals("$19", PlanPrice.amount(1900, "USD", Locale.US));
        assertEquals("$5", PlanPrice.amount(500, "USD", Locale.US));
    }

    @Test
    public void otherAmountsKeepTwoDecimals() {
        assertEquals("$9.50", PlanPrice.amount(950, "USD", Locale.US));
        assertEquals("$0.99", PlanPrice.amount(99, "USD", Locale.US));
    }

    @Test
    public void aMissingCurrencyIsDollars() {
        assertEquals("$19", PlanPrice.amount(1900, null, Locale.US));
    }

    @Test
    public void anUnknownCurrencyCodeIsShownAsItIs() {
        assertEquals("ZZZZ 19", PlanPrice.amount(1900, "ZZZZ", Locale.US));
        assertEquals("ZZZZ 9.05", PlanPrice.amount(905, "ZZZZ", Locale.US));
    }

    @Test
    public void beforeTheFirstFetchTheAppsOwnPlanOrderIsListed() {
        assertSame(Feature.PLAN_ORDER, PlanFeatures.planKeys(null));
        assertSame(Feature.PLAN_ORDER, PlanFeatures.planKeys(Collections.<String>emptyList()));
    }

    @Test
    public void freeComesFirstWhenTheCatalogueOnlyListsPaidPlans() {
        assertEquals(Arrays.asList("free", "starter", "pro", "team"),
                PlanFeatures.planKeys(Arrays.asList("starter", "pro", "team")));
    }

    @Test
    public void aCatalogueThatListsFreeIsKeptAsSent() {
        assertEquals(Arrays.asList("free", "starter"), PlanFeatures.planKeys(Arrays.asList("free", "starter")));
    }

    @Test
    public void eachTierListsOnlyTheFeaturesItAdds() {
        Map<String, String> lowest = new LinkedHashMap<>();
        lowest.put(Feature.CROP_9_16, "free");
        lowest.put(Feature.CROP_4_5, "starter");
        lowest.put(Feature.CROP_1_1, "STARTER");
        lowest.put(Feature.BG_REPLACE, "pro");
        lowest.put("unplaced", null);

        assertEquals(Collections.singletonList(Feature.CROP_9_16), PlanFeatures.featuresOf("free", lowest));
        assertEquals(Arrays.asList(Feature.CROP_4_5, Feature.CROP_1_1), PlanFeatures.featuresOf("starter", lowest));
        assertEquals(Collections.singletonList(Feature.BG_REPLACE), PlanFeatures.featuresOf("pro", lowest));
        assertTrue(PlanFeatures.featuresOf("agency", lowest).isEmpty());
    }

    @Test
    public void onlyFeaturesAboveFreeGetTheCrown() {
        assertFalse(PremiumMark.isPaid(null));
        assertFalse(PremiumMark.isPaid(""));
        assertFalse(PremiumMark.isPaid("free"));
        assertFalse(PremiumMark.isPaid("FREE"));
        assertTrue(PremiumMark.isPaid("starter"));
        assertTrue(PremiumMark.isPaid("pro"));
        // A tier the server adds later is paid too.
        assertTrue(PremiumMark.isPaid("enterprise"));
    }

    @Test
    public void anIdWithoutTextIsPrettified() {
        assertEquals("Bg replace", FeatureLabels.pretty("bg_replace"));
        assertEquals("", FeatureLabels.pretty(null));
    }
}
