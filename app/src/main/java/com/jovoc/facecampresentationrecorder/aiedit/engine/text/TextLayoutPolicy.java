package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one written rule for fitting a text of any length in any script into its box (the contract's fit rule). Pure
 * logic: no {@code Bitmap}, {@code Canvas}, {@code Paint}; the real width of a line comes from a {@link TextMeasurer}.
 *
 * <p>The rule, from the wanted size down to {@link #MIN_SIZE_SHARE} of it in steps of {@link #SHRINK}: the text fits on
 * one line, else it fits on two balanced lines broken at a Unicode line-break opportunity that is also a grapheme
 * boundary (so never inside a letter cluster, a conjunct or an emoji sequence, and in a spaced script only between
 * words); the first size that fits wins. When nothing fits, the overlay is skipped. There is never an ellipsis.
 * Paragraph direction comes from the text ({@link Bidi}); alignment is centred, so direction does not change a size.
 *
 * <p>Before the rule runs, the wanted size is multiplied by {@link LookTypography#scriptSizeFactor} (1 for Latin and
 * every script the table does not list), so a script with tall glyphs is drawn optically the same size as Latin. The
 * rule (minimum share, shrink steps, the shrunk flag) then works from that evened size, and {@link Result#size} is the
 * size that is drawn; the box padding and every effect follow it, as the fit rule says.
 */
public final class TextLayoutPolicy {

    /** The text never gets smaller than this share of the wanted size. */
    public static final float MIN_SIZE_SHARE = 0.5f;
    /** Each smaller size is this share of the one before. */
    public static final float SHRINK = 0.92f;
    /** Most lines a text overlay has. */
    public static final int MAX_LINES = 2;

    /** Measures real text; the Android one wraps a {@code TextPaint}, tests use a fake. */
    public interface TextMeasurer {
        /** The width in pixels of {@code line} drawn on one line at {@code size}. */
        float width(String line, float size);

        /** The width in pixels the lines may use at {@code size}: the block limit less padding and effect margin. */
        float available(float size);

        /**
         * The height in pixels of the ink of {@code sample} (a short run in one script) drawn on one line at
         * {@code size}, by the real font; 0 or less when it cannot be measured. {@link LookTypography#scriptSizeFactor}
         * compares it with Latin to even out the optical size of scripts whose glyphs are tall. The default (unknown)
         * leaves every size as asked.
         */
        default float inkHeight(String sample, float size) {
            return 0f;
        }
    }

    /** What the rule decided. */
    public static final class Result {
        /** True when no size and no wrapping fits: the overlay must not be drawn. */
        public final boolean skipped;
        /** The text size the lines were measured at (the wanted size when not shrunk). */
        public final float size;
        /** One or two lines (empty when skipped), trimmed, in reading (logical) order. */
        public final List<String> lines;
        /** True when the paragraph reads right to left. */
        public final boolean rtl;
        /** True when {@link #size} is smaller than the wanted size (after the script size factor, which is not a shrink). */
        public final boolean shrunk;
        /** True when the text is on two lines. */
        public final boolean wrapped;

        Result(boolean skipped, float size, List<String> lines, boolean rtl, boolean shrunk) {
            this.skipped = skipped;
            this.size = size;
            this.lines = lines;
            this.rtl = rtl;
            this.shrunk = shrunk;
            this.wrapped = lines.size() > 1;
        }

        /** The lines joined with a newline, the string to hand to a text layout. */
        public String joined() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) b.append('\n');
                b.append(lines.get(i));
            }
            return b.toString();
        }
    }

    private TextLayoutPolicy() {}

    /**
     * Fits {@code text} (already in the case it is drawn in).
     *
     * @param sizePx the wanted text size
     */
    public static Result fit(String text, float sizePx, TextMeasurer measurer) {
        String clean = collapse(text);
        boolean rtl = isRtl(clean);
        if (clean.isEmpty()) return new Result(true, sizePx, new ArrayList<String>(), rtl, false);
        int[] breaks = null;
        float wanted = sizePx * LookTypography.scriptSizeFactor(clean, measurer);
        float floor = wanted * MIN_SIZE_SHARE;
        float size = wanted;
        while (true) {
            float avail = measurer.available(size);
            if (measurer.width(clean, size) <= avail) {
                return new Result(false, size, single(clean), rtl, size < wanted);
            }
            if (breaks == null) breaks = breakOffsets(clean);
            List<String> two = balanced(clean, breaks, size, avail, measurer);
            if (two != null) return new Result(false, size, two, rtl, size < wanted);
            if (size <= floor) break;
            size = Math.max(floor, size * SHRINK);
        }
        return new Result(true, sizePx, new ArrayList<String>(), rtl, false);
    }

    /** Whether the paragraph direction of {@code text} is right to left (first strong character, left to right if none). */
    public static boolean isRtl(String text) {
        if (text == null || text.isEmpty()) return false;
        return !new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight();
    }

    /** Trims and turns every run of white space (a newline too) into one space. */
    static String collapse(String text) {
        if (text == null) return "";
        StringBuilder b = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = b.length() > 0;
            } else {
                if (space) b.append(' ');
                space = false;
                b.appendCodePoint(cp);
            }
        }
        return b.toString();
    }

    /**
     * The offsets where the text may be broken: Unicode line-break opportunities that are also grapheme boundaries
     * and not before a combining mark, joiner or variation selector nor after a joiner. Ascending, never 0 or the end.
     */
    static int[] breakOffsets(String text) {
        BreakIterator line = BreakIterator.getLineInstance(Locale.ROOT);
        BreakIterator chars = BreakIterator.getCharacterInstance(Locale.ROOT);
        line.setText(text);
        chars.setText(text);
        boolean[] grapheme = new boolean[text.length() + 1];
        for (int p = chars.first(); p != BreakIterator.DONE; p = chars.next()) grapheme[p] = true;
        List<Integer> out = new ArrayList<>();
        for (int p = line.first(); p != BreakIterator.DONE; p = line.next()) {
            if (p <= 0 || p >= text.length() || !grapheme[p]) continue;
            int before = text.codePointBefore(p);
            int at = text.codePointAt(p);
            if (before == 0x200D || joinsToPrevious(at)) continue;
            out.add(p);
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Marks, joiners, variation selectors and emoji modifiers: code points that belong to the letter before them. */
    static boolean joinsToPrevious(int cp) {
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK) return true;
        return cp == 0x200D || cp == 0x200C || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF)
                || (cp >= 0x1F3FB && cp <= 0x1F3FF);
    }

    /** The two-line split with the shortest longer line that fits {@code avail}, or null when none fits. */
    private static List<String> balanced(String text, int[] breaks, float size, float avail, TextMeasurer m) {
        String best1 = null;
        String best2 = null;
        float bestMax = Float.MAX_VALUE;
        float bestDiff = Float.MAX_VALUE;
        for (int p : breaks) {
            String a = text.substring(0, p).trim();
            String b = text.substring(p).trim();
            if (a.isEmpty() || b.isEmpty()) continue;
            float wa = m.width(a, size);
            float wb = m.width(b, size);
            if (wa > avail || wb > avail) continue;
            float mx = Math.max(wa, wb);
            float diff = Math.abs(wa - wb);
            if (mx < bestMax || (mx == bestMax && diff < bestDiff)) {
                bestMax = mx;
                bestDiff = diff;
                best1 = a;
                best2 = b;
            }
        }
        if (best1 == null) return null;
        List<String> lines = new ArrayList<>(2);
        lines.add(best1);
        lines.add(best2);
        return lines;
    }

    private static List<String> single(String text) {
        List<String> lines = new ArrayList<>(1);
        lines.add(text);
        return lines;
    }
}
