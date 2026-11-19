package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestStrings;

import org.junit.Test;

import java.util.Locale;

/** Family table, case and letter spacing: pure rules, the same for every script. */
public class LookTypographyTest {

    @Test
    public void familyTableMapsTheFourContractFamiliesToSystemFamilies() {
        assertEquals("sans-serif", LookTypography.systemFamily("sans"));
        assertEquals("sans-serif-condensed", LookTypography.systemFamily("sans_condensed"));
        assertEquals("serif", LookTypography.systemFamily("serif"));
        assertEquals("monospace", LookTypography.systemFamily("mono"));
        for (String f : new String[]{"sans", "sans_condensed", "serif", "mono"}) {
            assertTrue(f, LookTypography.isKnownFamily(f));
            assertEquals(f, LookTypography.effectiveFamily(f));
        }
    }

    @Test
    public void anUnknownFamilyFallsBackToSans() {
        for (String f : new String[]{"cursive", "", "SANS", null}) {
            assertFalse(String.valueOf(f), LookTypography.isKnownFamily(f));
            assertEquals("sans", LookTypography.effectiveFamily(f));
            assertEquals("sans-serif", LookTypography.systemFamily(f));
        }
    }

    @Test
    public void weightStaysInsideTheContractRange() {
        assertEquals(100, LookTypography.weight(0));
        assertEquals(700, LookTypography.weight(700));
        assertEquals(900, LookTypography.weight(1200));
    }

    @Test
    public void localeAcceptsTwoLetterThreeLetterAndTags() {
        assertEquals("tr", LookTypography.localeOf("tr").getLanguage());
        assertEquals("tr", LookTypography.localeOf("tur").getLanguage());
        assertEquals("tr", LookTypography.localeOf("tr-TR").getLanguage());
        assertEquals("tr", LookTypography.localeOf("tr_TR").getLanguage());
        assertEquals("en", LookTypography.localeOf("eng").getLanguage());
        assertEquals("bn", LookTypography.localeOf("ben").getLanguage());
        assertSame(Locale.ROOT, LookTypography.localeOf(null));
        assertSame(Locale.ROOT, LookTypography.localeOf(""));
        assertSame(Locale.ROOT, LookTypography.localeOf("und"));
    }

    @Test
    public void turkishDottedIUppercasesToCapitalDottedIWithTheServerLanguageCode() {
        String text = TestStrings.get("turkish_i").text;
        assertEquals("İĞDIR IŞIK", LookTypography.shownText(text, true, "tr"));
        assertEquals("İĞDIR IŞIK", LookTypography.shownText(text, true, "tur"));
        // Without the language the neutral rules give a plain I
        assertEquals("IĞDIR IŞIK", LookTypography.shownText(text, true, null));
    }

    @Test
    public void asWrittenNeverChangesTheText() {
        for (TestStrings.Entry e : TestStrings.ALL) {
            assertEquals(e.id, e.text, LookTypography.shownText(e.text, false, e.language));
        }
    }

    @Test
    public void scriptsWithoutCaseAreUnchangedByUpper() {
        for (String id : new String[]{"bengali_conjunct", "devanagari", "arabic", "hebrew", "chinese", "japanese",
                "thai"}) {
            TestStrings.Entry e = TestStrings.get(id);
            assertFalse(id, LookTypography.hasCase(e.text));
            assertEquals(id, e.text, LookTypography.shownText(e.text, true, e.language));
        }
    }

    @Test
    public void casedScriptsBecomeCapitalsAndMixedTextKeepsItsUncasedLetters() {
        assertEquals("AMAZING", LookTypography.shownText("Amazing", true, "en"));
        assertEquals("ПРИВЕТ МИР", LookTypography.shownText("Привет мир", true, "ru"));
        assertEquals("SAVE שלום 50% مرحبا", LookTypography.shownText("Save שלום 50% مرحبا", true, "en"));
        assertEquals("GREAT 🔥 JOB", LookTypography.shownText("Great 🔥 job", true, "en"));
    }

    @Test
    public void spacingIsZeroForJoiningScriptsAndKeptOtherwise() {
        for (String id : new String[]{"arabic", "bengali_conjunct", "devanagari", "thai"}) {
            assertEquals(id, 0.0, LookTypography.letterSpacing(TestStrings.get(id).text, 0.08), 0.0);
        }
        for (String id : new String[]{"one_word", "cyrillic", "hebrew", "chinese", "japanese", "turkish_i"}) {
            assertEquals(id, 0.08, LookTypography.letterSpacing(TestStrings.get(id).text, 0.08), 0.0);
        }
        // one joining letter anywhere in a mixed text turns it off for the whole text (the server's rule)
        assertEquals(0.0, LookTypography.letterSpacing(TestStrings.get("mixed_ltr_rtl").text, 0.08), 0.0);
        assertEquals(0.05, LookTypography.letterSpacing("Save שלום", 0.05), 0.0);
    }

    @Test
    public void aBrokenSpacingValueIsZero() {
        assertEquals(0.0, LookTypography.letterSpacing("abc", Double.NaN), 0.0);
        assertEquals(0.0, LookTypography.letterSpacing("abc", Double.POSITIVE_INFINITY), 0.0);
    }

    @Test
    public void theJoiningTableIsTheServersNoSpacingList() {
        // edit.vectors.ts NO_SPACING_SCRIPTS: 18 scripts, compared by name
        String[] server = {"Arabic", "Syriac", "Mongolian", "Nko", "Bengali", "Devanagari", "Gurmukhi", "Gujarati",
                "Oriya", "Tamil", "Telugu", "Kannada", "Malayalam", "Sinhala", "Khmer", "Myanmar", "Thai", "Lao"};
        assertEquals(server.length, TextMotion.JOINING_SCRIPTS.size());
        for (String name : server) {
            assertTrue(name, TextMotion.JOINING_SCRIPTS.contains(Character.UnicodeScript.valueOf(name.toUpperCase())));
        }
    }
}
