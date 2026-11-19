package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestStrings;

import org.junit.Test;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The fit rule on the string matrix, with a fake ruler (one width per grapheme, wider for wide scripts). */
public class TextLayoutPolicyTest {

    private static final float WANTED = 96f;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    /** 0.6 em a grapheme, 1.0 em for Han, kana, Hangul and emoji; width limit is {@code limit} px at every size. */
    private static final class Ruler implements TextLayoutPolicy.TextMeasurer {
        final float limit;

        Ruler(float limit) {
            this.limit = limit;
        }

        @Override
        public float width(String line, float size) {
            float w = 0;
            for (String g : graphemes(line)) w += weight(g) * size;
            return w;
        }

        @Override
        public float available(float size) {
            return limit;
        }
    }

    private static float weight(String g) {
        int cp = g.codePointAt(0);
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        boolean wide = s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
                || Character.isSupplementaryCodePoint(cp) && s == Character.UnicodeScript.COMMON;
        return wide ? 1.0f : 0.6f;
    }

    private static List<String> graphemes(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = GRAPHEME.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static TextLayoutPolicy.Result fit(TestStrings.Entry e, float limit) {
        return TextLayoutPolicy.fit(e.text.toUpperCase(Locale.ROOT), WANTED, new Ruler(limit));
    }

    @Test
    public void shortTextsFitOneLineAtTheWantedSize() {
        for (String id : new String[]{"one_grapheme", "one_word", "chinese", "japanese", "bengali_conjunct"}) {
            TextLayoutPolicy.Result r = fit(TestStrings.get(id), 864);
            assertFalse(id, r.skipped);
            assertEquals(id, 1, r.lines.size());
            assertEquals(id, WANTED, r.size, 0f);
            assertFalse(id, r.shrunk);
            assertFalse(id, r.wrapped);
        }
    }

    @Test
    public void everyResultOfTheMatrixFitsAndKeepsTheTextWhole() {
        for (float limit : new float[]{864, 600, 400, 250}) {
            for (TestStrings.Entry e : TestStrings.ALL) {
                String shown = e.text.toUpperCase(Locale.ROOT);
                TextLayoutPolicy.Result r = TextLayoutPolicy.fit(shown, WANTED, new Ruler(limit));
                if (r.skipped) {
                    assertTrue(e.id, r.lines.isEmpty());
                    continue;
                }
                assertTrue(e.id, r.lines.size() >= 1 && r.lines.size() <= TextLayoutPolicy.MAX_LINES);
                assertTrue(e.id, r.size <= WANTED && r.size >= WANTED * TextLayoutPolicy.MIN_SIZE_SHARE - 1e-3f);
                for (String line : r.lines) {
                    assertFalse(e.id, line.isEmpty());
                    assertEquals(e.id, line.trim(), line);
                    assertTrue(e.id + " line too wide", new Ruler(limit).width(line, r.size) <= limit);
                }
                // Nothing is lost or invented: the lines are the text with only the break space removed
                assertEquals(e.id, TextLayoutPolicy.collapse(shown).replace(" ", ""),
                        r.joined().replace("\n", "").replace(" ", ""));
            }
        }
    }

    @Test
    public void wrapsOnlyAtALineBreakOpportunityAndNeverInsideAGrapheme() {
        for (float limit : new float[]{600, 400, 300}) {
            for (TestStrings.Entry e : TestStrings.ALL) {
                String clean = TextLayoutPolicy.collapse(e.text.toUpperCase(Locale.ROOT));
                TextLayoutPolicy.Result r = TextLayoutPolicy.fit(clean, WANTED, new Ruler(limit));
                if (r.skipped || !r.wrapped) continue;
                String first = r.lines.get(0);
                int at = first.length();
                // the first line is a prefix of the text; the break is after it (a space may follow)
                assertTrue(e.id, clean.startsWith(first));
                BreakIterator line = BreakIterator.getLineInstance(Locale.ROOT);
                line.setText(clean);
                int p = at;
                while (p < clean.length() && clean.charAt(p) == ' ') p++;
                assertTrue(e.id + " not a line-break opportunity at " + p, line.isBoundary(p));
                // and not inside a grapheme cluster
                int pos = 0;
                boolean onBoundary = false;
                for (String g : graphemes(clean)) {
                    if (pos == at) onBoundary = true;
                    pos += g.length();
                }
                assertTrue(e.id + " broke inside a grapheme", onBoundary || at == clean.length());
            }
        }
    }

    @Test
    public void bengaliConjunctAndEmojiSequencesAreNeverSplit() {
        // Even where every code point is a candidate for the ruler, no break lands inside a cluster
        String[] texts = {"শ্রেষ্ঠ শ্রেষ্ঠ", "👨‍👩‍👧 👨‍👩‍👧", "👍🏽 👍🏽 👍🏽", "éé éé"};
        for (String t : texts) {
            for (float limit : new float[]{500, 300, 200}) {
                TextLayoutPolicy.Result r = TextLayoutPolicy.fit(t, WANTED, new Ruler(limit));
                if (r.skipped) continue;
                StringBuilder lines = new StringBuilder();
                for (String l : r.lines) {
                    lines.append(l);
                    // each line starts and ends on a cluster edge of the original text
                    assertTrue(t, t.contains(l));
                    assertEquals(t, l, String.join("", graphemes(l)));
                }
                assertEquals(t, t.replace(" ", ""), lines.toString().replace(" ", ""));
            }
        }
    }

    @Test
    public void aChineseSentenceWrapsWithoutSpaces() {
        TestStrings.Entry e = TestStrings.get("over_cap_chinese");
        String text = e.text.substring(0, 14);
        // 14 Han at 96 px = 1344 px: too wide for one line at 700, fits on two lines at a smaller size
        TextLayoutPolicy.Result r = TextLayoutPolicy.fit(text, WANTED, new Ruler(700));
        assertFalse(r.skipped);
        assertTrue(r.wrapped);
        assertEquals(2, r.lines.size());
        assertEquals(text, r.joined().replace("\n", ""));
        assertFalse(text.contains(" "));
    }

    @Test
    public void linesAreBalanced() {
        TextLayoutPolicy.Result r = TextLayoutPolicy.fit("AAAA AAAA AAAA AAAA", WANTED, new Ruler(1000));
        assertFalse(r.skipped);
        assertEquals(2, r.lines.size());
        assertEquals("AAAA AAAA", r.lines.get(0));
        assertEquals("AAAA AAAA", r.lines.get(1));
        // not greedy: a greedy fill would leave "AAAA" alone on line two
        r = TextLayoutPolicy.fit("AAAA AAAA AAAA", WANTED, new Ruler(700));
        assertEquals(2, r.lines.size());
        assertTrue(Math.abs(r.lines.get(0).length() - r.lines.get(1).length()) <= 5);
    }

    @Test
    public void shrinksBeforeSkippingAndNotesIt() {
        // 5 Latin letters = 288 px at 96: fits at 240 only after shrinking (one word, no break)
        TextLayoutPolicy.Result r = TextLayoutPolicy.fit("ABCDE", WANTED, new Ruler(240));
        assertFalse(r.skipped);
        assertTrue(r.shrunk);
        assertFalse(r.wrapped);
        assertTrue(r.size < WANTED);
        assertTrue(new Ruler(240).width("ABCDE", r.size) <= 240);
    }

    @Test
    public void skipsWhenNothingFitsAndNeverCutsWithAnEllipsis() {
        // one unbreakable word wider than the limit even at half size
        TextLayoutPolicy.Result r = TextLayoutPolicy.fit("ABCDEFGHIJKLMNOPQRSTUVWXYZ", WANTED, new Ruler(300));
        assertTrue(r.skipped);
        assertTrue(r.lines.isEmpty());
        assertEquals("", r.joined());
        // a text that can only ever lose letters would need an ellipsis: it is skipped instead
        for (TestStrings.Entry e : TestStrings.ALL) {
            TextLayoutPolicy.Result x = fit(e, 120);
            for (String line : x.lines) assertFalse(e.id, line.contains("…") || line.endsWith("..."));
        }
        assertTrue(TextLayoutPolicy.fit("   ", WANTED, new Ruler(864)).skipped);
    }

    @Test
    public void sameInputSameResult() {
        for (TestStrings.Entry e : TestStrings.ALL) {
            TextLayoutPolicy.Result a = fit(e, 500);
            TextLayoutPolicy.Result b = fit(e, 500);
            assertEquals(e.id, a.skipped, b.skipped);
            assertEquals(e.id, a.size, b.size, 0f);
            assertEquals(e.id, a.joined(), b.joined());
            assertEquals(e.id, a.rtl, b.rtl);
        }
    }

    @Test
    public void rightToLeftIsDetectedFromTheText() {
        for (TestStrings.Entry e : TestStrings.ALL) {
            assertEquals(e.id, e.rtl, TextLayoutPolicy.isRtl(e.text));
            assertEquals(e.id, e.rtl, fit(e, 864).rtl);
        }
        // the first strong letter decides a mixed text
        assertFalse(TextLayoutPolicy.isRtl("Save שלום 50% مرحبا"));
        assertTrue(TextLayoutPolicy.isRtl("שלום Save"));
        assertFalse(TextLayoutPolicy.isRtl("12345"));
    }

    @Test
    public void whiteSpaceIsCollapsed() {
        TextLayoutPolicy.Result r = TextLayoutPolicy.fit("  hello \n  world  ", WANTED, new Ruler(2000));
        assertEquals("hello world", r.joined());
    }
}
