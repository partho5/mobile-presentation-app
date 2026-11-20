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
    public void turkishDottedAndDotlessIAreCapitalisedByTheLocaleWithTheServerLanguageCode() {
        String text = TestStrings.get("turkish_i").text;
        assertEquals("İğdır Işık", LookTypography.shownText(text, true, "tr"));
        assertEquals("İğdır Işık", LookTypography.shownText(text, true, "tur"));
        // Without the language the neutral rules give a plain I
        assertEquals("Iğdır Işık", LookTypography.shownText(text, true, null));
        // the rest of a word is lowered by the same locale: Turkish capital I becomes dotless i
        assertEquals("Işık", LookTypography.shownText("IŞIK", true, "tr"));
        assertEquals("Ik", LookTypography.shownText("IK", true, "en"));
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
    public void caseUpperIsTitleCaseNotCapitals() {
        assertEquals("Amazing", LookTypography.shownText("Amazing", true, "en"));
        assertEquals("Amazing", LookTypography.shownText("amazing", true, "en"));
        assertEquals("Save Time Now", LookTypography.shownText(TestStrings.get("all_caps_latin").text, true, "en"));
        assertEquals("Gypsy Jumping Quickly", LookTypography.shownText(TestStrings.get("descenders").text, true, "en"));
        assertEquals("Привет Мир", LookTypography.shownText("Привет мир", true, "ru"));
        assertEquals("Save שלום 50% مرحبا", LookTypography.shownText("Save שלום 50% مرحبا", true, "en"));
        assertEquals("Great 🔥 Job", LookTypography.shownText("Great 🔥 job", true, "en"));
    }

    @Test
    public void apostropheAndHyphenWordsAreSensible() {
        Locale en = Locale.ENGLISH;
        assertEquals("Don't Stop", LookTypography.titleCase("don't stop", en));
        assertEquals("Don\u2019t Stop", LookTypography.titleCase("DON\u2019T STOP", en));
        assertEquals("Well-Known Fact", LookTypography.titleCase("well-known fact", en));
        assertEquals("O'clock Now", LookTypography.titleCase("o'clock now", en));
        assertEquals("'Hello' World", LookTypography.titleCase("'hello' world", en));
        assertEquals("3rd Place 50% Off", LookTypography.titleCase("3rd place 50% off", en));
    }

    @Test
    public void titleCaseIsDeterministicAndKeepsAcronymsDecisionDocumented() {
        // decision: only the first letter of a word is a capital, an all-caps word is lowered too
        assertEquals("Nasa Launch", LookTypography.titleCase("NASA launch", Locale.ENGLISH));
        assertEquals("Iphone", LookTypography.titleCase("iPhone", Locale.ENGLISH));
        assertEquals("Straße", LookTypography.titleCase("straße", Locale.GERMAN));
        // a capital that would need two letters (sharp s) keeps its title form, not SS
        assertEquals("ß", LookTypography.titleCase("ß", Locale.GERMAN));
        for (TestStrings.Entry e : TestStrings.ALL) {
            String once = LookTypography.shownText(e.text, true, e.language);
            assertEquals(e.id, once, LookTypography.shownText(once, true, e.language));
        }
    }

    @Test
    public void titleCaseOnlyChangesCaseOfLetters() {
        for (TestStrings.Entry e : TestStrings.ALL) {
            Locale l = LookTypography.localeOf(e.language);
            String shown = LookTypography.shownText(e.text, true, e.language);
            assertEquals(e.id, e.text.toLowerCase(l), shown.toLowerCase(l));
        }
    }

    /** Ink heights by script: Latin 70 per 100, the listed scripts by their own table value (tall); unknown otherwise. */
    private static final class InkFake implements TextLayoutPolicy.TextMeasurer {
        final float latin;
        final float tall;
        int calls;

        InkFake(float latin, float tall) {
            this.latin = latin;
            this.tall = tall;
        }

        @Override
        public float width(String line, float size) {
            return line.length() * size * 0.6f;
        }

        @Override
        public float available(float size) {
            return 1e9f;
        }

        @Override
        public float inkHeight(String sample, float size) {
            calls++;
            boolean isLatin = Character.UnicodeScript.of(sample.codePointAt(0)) == Character.UnicodeScript.LATIN;
            return (isLatin ? latin : tall) * size / 100f;
        }
    }

    @Test
    public void latinAndUntabledScriptsKeepTheirSize() {
        InkFake m = new InkFake(100, 150);
        for (String id : new String[]{"one_word", "all_caps_latin", "cyrillic", "arabic", "hebrew", "chinese", "japanese",
                "thai", "turkish_i", "emoji_inside", "descenders"}) {
            assertEquals(id, 1f, LookTypography.scriptSizeFactor(TestStrings.get(id).text, m), 0f);
        }
        assertEquals(0, m.calls);   // nothing was even measured
    }

    @Test
    public void bengaliAndTheOtherTallIndicScriptsAreDrawnSmallerByTheMeasuredRatio() {
        InkFake m = new InkFake(100, 125);
        assertEquals(0.8f, LookTypography.scriptSizeFactor(TestStrings.get("bengali_conjunct").text, m), 1e-6f);
        assertEquals(0.8f, LookTypography.scriptSizeFactor(TestStrings.get("devanagari").text, m), 1e-6f);
        // never larger than asked, never below the floor
        assertEquals(1f, LookTypography.scriptSizeFactor(TestStrings.get("bengali_conjunct").text, new InkFake(100, 80)), 0f);
        assertEquals(LookTypography.MIN_SCRIPT_FACTOR,
                LookTypography.scriptSizeFactor(TestStrings.get("bengali_conjunct").text, new InkFake(100, 500)), 1e-6f);
        // a measurer that cannot measure changes nothing
        assertEquals(1f, LookTypography.scriptSizeFactor(TestStrings.get("bengali_conjunct").text,
                new InkFake(0, 0)), 0f);
        assertEquals(1f, LookTypography.scriptSizeFactor(TestStrings.get("bengali_conjunct").text, new Plain()), 0f);
    }

    @Test
    public void mixedTextGetsTheLetterWeightedAverage() {
        InkFake m = new InkFake(100, 200);          // 0.5 is clamped up to the 0.6 floor
        float f = LookTypography.scriptSizeFactor("abcd \u0995\u09CD\u09B7\u0995\u09CD\u09B7", m);
        // letters: 4 Latin + 4 Bengali consonants (the virama is a mark, not a letter)
        assertEquals((4f + 4f * 0.6f) / 8f, f, 1e-6f);
        assertTrue(f < 1f && f > LookTypography.MIN_SCRIPT_FACTOR);
    }

    @Test
    public void everyIndicScriptOfTheServersJoiningListIsInTheSizeTable() {
        for (String name : new String[]{"Bengali", "Devanagari", "Gurmukhi", "Gujarati", "Oriya", "Tamil", "Telugu",
                "Kannada", "Malayalam", "Sinhala"}) {
            assertTrue(name, LookTypography.isTallScript(Character.UnicodeScript.valueOf(name.toUpperCase())));
        }
        assertFalse(LookTypography.isTallScript(Character.UnicodeScript.LATIN));
        assertFalse(LookTypography.isTallScript(Character.UnicodeScript.ARABIC));
    }

    @Test
    public void theFitAppliesTheFactorSoTheDrawnSizeIsTheEvenedOneAndIsNotNotedAsShrunk() {
        InkFake m = new InkFake(100, 125);
        TextLayoutPolicy.Result bengali = TextLayoutPolicy.fit(TestStrings.get("bengali_conjunct").text, 100f, m);
        assertFalse(bengali.skipped);
        assertEquals(80f, bengali.size, 1e-3f);
        assertFalse(bengali.shrunk);
        TextLayoutPolicy.Result latin = TextLayoutPolicy.fit(TestStrings.get("one_word").text, 100f, m);
        assertEquals(100f, latin.size, 0f);
    }

    /** A measurer that never measures ink (the interface default). */
    private static final class Plain implements TextLayoutPolicy.TextMeasurer {
        @Override
        public float width(String line, float size) {
            return line.length() * size;
        }

        @Override
        public float available(float size) {
            return 1e9f;
        }
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
