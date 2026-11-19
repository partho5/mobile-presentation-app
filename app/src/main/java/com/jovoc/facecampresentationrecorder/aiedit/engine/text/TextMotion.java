package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The two text facts every reveal animation shares, each with one owner: which units a typewriter types
 * ({@link #revealUnits}) and which way a reveal runs ({@link #readingDirection}). Pure logic, no Android classes.
 *
 * <p>Units are graphemes, except in a script whose letters join or reorder ({@link #JOINING_SCRIPTS}): there they are
 * words, so a half-drawn conjunct or a broken Arabic join is never shown. No per-language rule: only Unicode data.
 */
public final class TextMotion {

    /** Reveal runs left to right. */
    public static final int LTR = 1;
    /** Reveal runs right to left. */
    public static final int RTL = -1;

    /** Typewriter: about this long a step, over at least {@link #TYPING_MIN_MS} and at most {@link #TYPING_CAP_MS}. */
    public static final long TYPING_STEP_MS = 30;
    public static final long TYPING_MIN_MS = 200;
    public static final long TYPING_CAP_MS = 450;

    /**
     * Scripts whose letters join, reorder or stack, so a letter cluster cannot be shown piece by piece (and where
     * letter spacing must be 0). The same 18 scripts as the server's {@code NO_SPACING_SCRIPTS}
     * ({@code contract/edit.vectors.ts}); C6 reads this table too.
     */
    public static final Set<Character.UnicodeScript> JOINING_SCRIPTS = EnumSet.of(
            Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.MONGOLIAN,
            Character.UnicodeScript.NKO, Character.UnicodeScript.BENGALI, Character.UnicodeScript.DEVANAGARI,
            Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI, Character.UnicodeScript.ORIYA,
            Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA,
            Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.SINHALA, Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO);

    private TextMotion() {}

    /** Whether any letter of {@code text} is in a joining or reordering script. */
    public static boolean hasJoiningScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript script;
            try {
                script = Character.UnicodeScript.of(cp);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (JOINING_SCRIPTS.contains(script)) return true;
        }
        return false;
    }

    /** {@link #RTL} when the paragraph reads right to left (from the text, by the bidi algorithm), else {@link #LTR}. */
    public static int readingDirection(String text) {
        return TextLayoutPolicy.isRtl(text) ? RTL : LTR;
    }

    /**
     * The steps of a typewriter, as the end offsets (exclusive, ascending, the last is the text length) of its units in
     * reading (logical) order; a unit starts where the one before ends. White space joins the unit before it, so
     * no step shows nothing. Never ends inside a grapheme cluster, a joiner sequence or a flag.
     */
    public static int[] revealUnits(String text) {
        if (text == null || text.isEmpty()) return new int[0];
        boolean words = hasJoiningScript(text);
        BreakIterator it = words ? BreakIterator.getWordInstance(Locale.ROOT) : BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(text);
        List<Integer> cuts = new ArrayList<>();
        for (int p = it.next(); p != BreakIterator.DONE; p = it.next()) {
            if (p < text.length() && !canCutAt(text, p)) continue;
            cuts.add(p);
        }
        if (cuts.isEmpty() || cuts.get(cuts.size() - 1) != text.length()) cuts.add(text.length());
        List<Integer> ends = new ArrayList<>();
        int start = 0;
        for (int cut : cuts) {
            boolean weak = weak(text, start, cut, words);
            if (weak && !ends.isEmpty()) {
                ends.set(ends.size() - 1, cut);
            } else if (!weak) {
                ends.add(cut);
            } else {
                continue;   // leading white space: the next unit starts at 0
            }
            start = cut;
        }
        if (ends.isEmpty()) ends.add(text.length());
        int[] r = new int[ends.size()];
        for (int i = 0; i < r.length; i++) r[i] = ends.get(i);
        return r;
    }

    /** How long a typewriter over {@code units} steps runs: about 30 ms a step, 200 ms at least, never past the plan or 450 ms. */
    public static long typewriterMs(int units, long plannedMs) {
        long planned = Math.max(TYPING_MIN_MS, plannedMs);
        long top = Math.min(planned, TYPING_CAP_MS);
        long natural = Math.max(1, units) * TYPING_STEP_MS;
        return Math.max(TYPING_MIN_MS, Math.min(top, natural));
    }

    /**
     * The tilt of an overlay in degrees: {@code asked} (the plan's {@code params.tilt_deg}) as given, sign included,
     * kept inside {@code range} (the look's range; null means the look does not tilt: 0). Without a number, a value in
     * the range picked from {@code itemId}, so a re-render matches.
     */
    public static float tilt(Object asked, double[] range, String itemId) {
        if (range == null) return 0;
        double lo = Math.min(range[0], range[1]);
        double hi = Math.max(range[0], range[1]);
        if (asked instanceof Number && !Double.isNaN(((Number) asked).doubleValue())) {
            return (float) Math.max(lo, Math.min(hi, ((Number) asked).doubleValue()));
        }
        int h = itemId != null ? itemId.hashCode() : 0;
        double u = (h & 0x7fffffff) % 1000 / 999.0;
        return (float) (lo + (hi - lo) * u);
    }

    /** A boundary at {@code p} is allowed: not before a mark, joiner or selector, not after a joiner, not inside a flag. */
    private static boolean canCutAt(String text, int p) {
        int before = text.codePointBefore(p);
        int at = text.codePointAt(p);
        if (before == 0x200D || TextLayoutPolicy.joinsToPrevious(at)) return false;
        if (isRegionalIndicator(before) && isRegionalIndicator(at)) {
            int run = 0;
            for (int i = p; i > 0; ) {
                int cp = text.codePointBefore(i);
                if (!isRegionalIndicator(cp)) break;
                run++;
                i -= Character.charCount(cp);
            }
            return run % 2 == 0;
        }
        return true;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    /** A piece that gets no step of its own: white space (grapheme steps), or no letter or digit (word steps). */
    private static boolean weak(String text, int from, int to, boolean words) {
        for (int i = from; i < to; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean space = Character.isWhitespace(cp) || Character.isSpaceChar(cp);
            if (words ? Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK : !space) {
                return false;
            }
        }
        return true;
    }
}
