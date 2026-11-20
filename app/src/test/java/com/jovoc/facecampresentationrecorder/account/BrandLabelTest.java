package com.jovoc.facecampresentationrecorder.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BrandLabelTest {

    private static final List<Workspace> TWO = Arrays.asList(
            new Workspace("w1", "Acme", ""), new Workspace("w2", "Beta", ""));

    @Test
    public void oneBrandNamesNothing() {
        List<Workspace> one = Collections.singletonList(new Workspace("w1", "Acme", ""));
        assertNull(BrandLabel.brandName(one, "w1"));
        assertNull(BrandLabel.brandName(Collections.emptyList(), "w1"));
        assertNull(BrandLabel.brandName(null, "w1"));
    }

    @Test
    public void severalBrandsNameTheJobsOwnBrand() {
        assertEquals("Acme", BrandLabel.brandName(TWO, "w1"));
        assertEquals("Beta", BrandLabel.brandName(TWO, "w2"));
    }

    @Test
    public void unknownBrandOrNoBrandNamesNothing() {
        assertNull(BrandLabel.brandName(TWO, "w9"));
        assertNull(BrandLabel.brandName(TWO, null));
        assertNull(BrandLabel.brandName(TWO, ""));
    }

    @Test
    public void aBrandWithoutAUsableNameNamesNothingNotTheId() {
        List<Workspace> noName = Arrays.asList(
                new Workspace("w1", "", ""), new Workspace("w2", "Beta", ""));
        assertNull(BrandLabel.brandName(noName, "w1"));
        List<Workspace> nameIsId = Arrays.asList(
                new Workspace("w1", "w1", ""), new Workspace("w2", "Beta", ""));
        assertNull(BrandLabel.brandName(nameIsId, "w1"));
        List<Workspace> blank = Arrays.asList(
                new Workspace("w1", "   ", ""), new Workspace("w2", "Beta", ""));
        assertNull(BrandLabel.brandName(blank, "w1"));
    }

    @Test
    public void withBrandAddsTheNameAfterTheFirstLine() {
        assertEquals("Failed: reason · Acme", BrandLabel.withBrand("Failed: reason", "Acme"));
        assertEquals("Live · Acme\nPosted with your text",
                BrandLabel.withBrand("Live\nPosted with your text", "Acme"));
    }

    @Test
    public void withBrandLeavesTheTextAloneWithoutAName() {
        assertEquals("Failed: reason", BrandLabel.withBrand("Failed: reason", null));
        assertNull(BrandLabel.withBrand(null, "Acme"));
    }

    @Test
    public void aJobInAnotherBrandIsDetectedAgainstThePick() {
        assertTrue(BrandLabel.isOtherBrand("w2", "w1"));
        assertFalse(BrandLabel.isOtherBrand("w1", "w1"));
        assertFalse(BrandLabel.isOtherBrand(null, "w1"));
        assertFalse(BrandLabel.isOtherBrand("w2", null));
    }
}
