package com.jovoc.facecampresentationrecorder.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The brand row's label, avatar letters and locked rule. */
public class BrandRowTest {

    private static final String PLACEHOLDER = "Brand";
    private static final String CHOOSE = "Choose a brand";

    @Test
    public void aStoredPickIsShownBeforeTheServerDefault() {
        assertEquals("Acme", BrandRow.label("id-1", "Acme", "Other", PLACEHOLDER));
    }

    @Test
    public void withoutAPickTheServerDefaultIsShown() {
        assertEquals("Other", BrandRow.label(null, null, "Other", PLACEHOLDER));
    }

    @Test
    public void withoutAPickAndAnUnknownDefaultThePlaceholderIsShown() {
        assertEquals(PLACEHOLDER, BrandRow.label(null, null, null, PLACEHOLDER));
        assertEquals(PLACEHOLDER, BrandRow.label(null, "", "  ", PLACEHOLDER));
    }

    @Test
    public void aPickWithoutANameNeverShowsTheDefaultBrand() {
        assertEquals(PLACEHOLDER, BrandRow.label("id-1", null, "Other", PLACEHOLDER));
        assertEquals(PLACEHOLDER, BrandRow.label("id-1", "  ", "Other", PLACEHOLDER));
    }

    @Test
    public void thePlaceholderIsNeutral_notDefaultBrand() {
        assertEquals("Brand", PLACEHOLDER);
        assertEquals("Brand", BrandRow.label("id-1", "", null, PLACEHOLDER));
    }

    @Test
    public void aLostBrandAsksForAChoiceAndCanStillBeChangedWhenNoRecordingRuns() {
        BrandRow row = BrandRow.lost(CHOOSE, false);
        assertEquals(CHOOSE, row.name);
        assertEquals("?", row.initials);
        assertTrue(row.changeable);
    }

    @Test
    public void aLostBrandIsLockedWhileARecordingRuns() {
        BrandRow row = BrandRow.lost(CHOOSE, true);
        assertEquals(CHOOSE, row.name);
        assertFalse(row.changeable);
    }

    @Test
    public void anEmptyPickIdCountsAsNoPick() {
        assertEquals("Other", BrandRow.label("", "Acme", "Other", PLACEHOLDER));
    }

    @Test
    public void initialsAreTheFirstLettersOfTheFirstTwoWords() {
        assertEquals("AB", BrandRow.initials("acme brand"));
        assertEquals("AC", BrandRow.initials("  Acme   Coffee  Roasters "));
        assertEquals("A", BrandRow.initials("Acme"));
    }

    @Test
    public void initialsOfAnEmptyNameAreAQuestionMark() {
        assertEquals("?", BrandRow.initials(null));
        assertEquals("?", BrandRow.initials("   "));
    }

    @Test
    public void aBrandCanBeChangedWhenNoRecordingRuns() {
        BrandRow row = BrandRow.of("id-1", "Acme", null, PLACEHOLDER, false);
        assertEquals("Acme", row.name);
        assertEquals("A", row.initials);
        assertTrue(row.changeable);
    }

    @Test
    public void aBrandIsLockedWhileARecordingRuns() {
        BrandRow row = BrandRow.of("id-1", "Acme", null, PLACEHOLDER, true);
        assertEquals("Acme", row.name);
        assertFalse(row.changeable);
    }

    @Test
    public void theLockedRowStillShowsTheServerDefaultWhenThereIsNoPick() {
        BrandRow row = BrandRow.of(null, null, "Other", PLACEHOLDER, true);
        assertEquals("Other", row.name);
        assertFalse(row.changeable);
    }
}
