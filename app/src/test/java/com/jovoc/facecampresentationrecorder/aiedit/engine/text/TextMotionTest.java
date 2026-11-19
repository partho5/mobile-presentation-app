package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestStrings;

import org.junit.Test;

/** The shared text facts of the entry motions: typewriter steps, reading direction, typing time, signed tilt. */
public class TextMotionTest {

    private static String unit(String text, int[] ends, int i) {
        return text.substring(i == 0 ? 0 : ends[i - 1], ends[i]);
    }

    @Test
    public void unitsCoverTheWholeTextInOrderForEveryMatrixString() {
        for (TestStrings.Entry e : TestStrings.ALL) {
            int[] ends = TextMotion.revealUnits(e.text);
            assertTrue(e.id, ends.length >= 1);
            assertEquals(e.id, e.text.length(), ends[ends.length - 1]);
            for (int i = 1; i < ends.length; i++) assertTrue(e.id, ends[i] > ends[i - 1]);
            for (int i = 0; i < ends.length; i++) {
                // Never ends between the two halves of a surrogate pair
                if (ends[i] < e.text.length()) assertTrue(e.id, !Character.isLowSurrogate(e.text.charAt(ends[i])));
            }
        }
    }

    @Test
    public void latinTypesOneLetterAtATimeWithSpacesJoiningThePreviousStep() {
        int[] ends = TextMotion.revealUnits("Hi you");
        assertEquals(5, ends.length);
        assertEquals("i ", unit("Hi you", ends, 1));
    }

    @Test
    public void aBengaliConjunctIsNeverShownHalf() {
        String bn = TestStrings.get("bengali_conjunct").text;
        int[] ends = TextMotion.revealUnits(bn);
        assertEquals(1, ends.length);   // one word, one step
        assertEquals(bn.length(), ends[0]);
    }

    @Test
    public void anArabicWordIsOneStepSoNoJoinIsBroken() {
        String ar = TestStrings.get("arabic").text;
        int[] ends = TextMotion.revealUnits(ar);
        assertEquals(2, ends.length);
        assertEquals("مرحبا ", unit(ar, ends, 0));
        assertEquals("بالعالم", unit(ar, ends, 1));
    }

    @Test
    public void devanagariTypesWordByWord() {
        String hi = TestStrings.get("devanagari").text;
        int[] ends = TextMotion.revealUnits(hi);
        assertEquals(2, ends.length);
    }

    @Test
    public void joiningScriptsNeverEndInsideAMarkOrAJoiner() {
        for (String id : new String[]{"bengali_conjunct", "devanagari", "arabic", "thai"}) {
            String t = TestStrings.get(id).text;
            for (int end : TextMotion.revealUnits(t)) {
                if (end >= t.length()) continue;
                assertTrue(id, !TextLayoutPolicy.joinsToPrevious(t.codePointAt(end)));
            }
        }
    }

    @Test
    public void anEmojiAndAFlagAndACombiningMarkStayWhole() {
        String t = "a🔥b";
        assertEquals(3, TextMotion.revealUnits(t).length);
        String zwj = "👨‍👩‍👧";
        assertEquals(1, TextMotion.revealUnits(zwj).length);
        String flags = "🇧🇩🇫🇷";   // BD FR
        assertEquals(2, TextMotion.revealUnits(flags).length);
        String combined = "éx";
        assertEquals(2, TextMotion.revealUnits(combined).length);
    }

    @Test
    public void chineseTypesCharacterByCharacter() {
        assertEquals(4, TextMotion.revealUnits(TestStrings.get("chinese").text).length);
    }

    @Test
    public void deterministic() {
        for (TestStrings.Entry e : TestStrings.ALL) {
            assertEquals(e.id, java.util.Arrays.toString(TextMotion.revealUnits(e.text)),
                    java.util.Arrays.toString(TextMotion.revealUnits(e.text)));
        }
    }

    @Test
    public void readingDirectionFollowsTheText() {
        for (TestStrings.Entry e : TestStrings.ALL) {
            if (e.id.equals("mixed_ltr_rtl")) continue;
            assertEquals(e.id, e.rtl ? TextMotion.RTL : TextMotion.LTR, TextMotion.readingDirection(e.text));
        }
        assertEquals(TextMotion.LTR, TextMotion.readingDirection(TestStrings.get("mixed_ltr_rtl").text));
        assertEquals(TextMotion.LTR, TextMotion.readingDirection(""));
    }

    @Test
    public void joiningTableMatchesTheServersList() {
        assertEquals(18, TextMotion.JOINING_SCRIPTS.size());
        assertTrue(TextMotion.hasJoiningScript("Save مرحبا"));
        assertTrue(!TextMotion.hasJoiningScript("Привет Hello 节省 שלום"));
    }

    @Test
    public void typingTakesAboutThirtyMsAStepInsideTwoHundredToFourFifty() {
        assertEquals(200, TextMotion.typewriterMs(1, 400));
        assertEquals(300, TextMotion.typewriterMs(10, 400));
        assertEquals(400, TextMotion.typewriterMs(60, 400));
        assertEquals(450, TextMotion.typewriterMs(60, 500));
        assertEquals(200, TextMotion.typewriterMs(60, 10));
    }

    @Test
    public void tiltKeepsTheSignAndStaysInTheLooksRange() {
        double[] range = {-4, 6};
        assertEquals(-3f, TextMotion.tilt(-3.0, range, "a"), 1e-6f);
        assertEquals(3f, TextMotion.tilt(3.0, range, "a"), 1e-6f);
        assertEquals(6f, TextMotion.tilt(20.0, range, "a"), 1e-6f);
        assertEquals(-4f, TextMotion.tilt(-20, range, "a"), 1e-6f);
        assertEquals(0f, TextMotion.tilt(3.0, null, "a"), 0f);
    }

    @Test
    public void tiltFallsBackToAStableHashValueInTheRange() {
        double[] range = {-4, 6};
        float a = TextMotion.tilt(null, range, "item-7");
        assertEquals(a, TextMotion.tilt("x", range, "item-7"), 0f);
        assertTrue(a >= -4 && a <= 6);
    }
}
