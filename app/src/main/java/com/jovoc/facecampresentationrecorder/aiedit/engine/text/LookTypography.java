package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import java.text.BreakIterator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The one owner of the three type decisions of a look: which system font family, which letters (case), and how far
 * apart (letter spacing). Pure (no Paint, Typeface or android.icu), so every rule is tested on the JVM; the Canvas
 * side only applies the answers. No per-language rule: only Unicode data ({@code toUpperCase(Locale)},
 * {@link BreakIterator} words, {@link Character.UnicodeScript}) and the script table of {@link TextMotion}.
 *
 * <p>Two further decisions live here: the Title Case that {@code case: upper} now draws ({@link #titleCase}) and the
 * optical size of a script relative to Latin ({@link #scriptSizeFactor}).
 */
public final class LookTypography {

    /** The family used when a look names one the phone does not know. */
    public static final String FALLBACK_FAMILY = "sans";

    /** The weight the shipped looks always drew (bold). */
    public static final int SHIPPED_WEIGHT = 700;

    private static final Map<String, String> SYSTEM_FAMILIES = new HashMap<>();

    static {
        SYSTEM_FAMILIES.put("sans", "sans-serif");
        SYSTEM_FAMILIES.put("sans_condensed", "sans-serif-condensed");
        SYSTEM_FAMILIES.put("serif", "serif");
        SYSTEM_FAMILIES.put("mono", "monospace");
    }

    /** ISO 639-2/T (three letter) code to the two letter code that {@code String.toUpperCase(Locale)} acts on. */
    private static final Map<String, String> TWO_LETTER = new HashMap<>();

    static {
        for (String two : Locale.getISOLanguages()) {
            try {
                TWO_LETTER.put(new Locale(two).getISO3Language(), two);
            } catch (java.util.MissingResourceException ignored) {
                // a code the runtime has no three letter form for stays unmapped
            }
        }
    }

    private LookTypography() {}

    /** True when {@code family} is one of the contract's four families. */
    public static boolean isKnownFamily(String family) {
        return family != null && SYSTEM_FAMILIES.containsKey(family);
    }

    /** The contract family that is actually drawn: {@code family} when known, else {@link #FALLBACK_FAMILY}. */
    public static String effectiveFamily(String family) {
        return isKnownFamily(family) ? family : FALLBACK_FAMILY;
    }

    /** The Android system family name for a contract family; an unknown one gives the {@code sans} name. */
    public static String systemFamily(String family) {
        return SYSTEM_FAMILIES.get(effectiveFamily(family));
    }

    /** The weight to draw, kept inside the 100..900 the contract allows. */
    public static int weight(int weight) {
        return Math.max(100, Math.min(900, weight));
    }

    /**
     * The locale of a plan language: a two letter code, a three letter (ISO 639) code such as the one the phone sends
     * the server, or a language tag. Null, empty or unknown gives {@link Locale#ROOT} (the language-neutral rules).
     */
    public static Locale localeOf(String language) {
        if (language == null) return Locale.ROOT;
        String tag = language.trim().replace('_', '-');
        if (tag.isEmpty() || "und".equalsIgnoreCase(tag)) return Locale.ROOT;
        Locale parsed = Locale.forLanguageTag(tag);
        String lang = parsed.getLanguage();
        if (lang.isEmpty()) return Locale.ROOT;
        String two = TWO_LETTER.get(lang);
        return two != null ? new Locale(two, parsed.getCountry()) : parsed;
    }

    /**
     * The text as the look draws it: for {@code case: upper} the text in Title Case in the plan language
     * ({@link #titleCase}; the owner chose this over forced capitals), else as written. A text without cased letters
     * (Bengali, Chinese, Thai, Arabic...) is returned unchanged. The flag keeps the contract's name {@code upper}.
     */
    public static String shownText(String text, boolean upper, String language) {
        if (!upper || !hasCase(text)) return text;
        return titleCase(text, localeOf(language));
    }

    /**
     * Title Case: the first letter of every word capitalised, the rest of the word lower case, all in {@code locale}
     * (so Turkish {@code i} gives {@code İ} and {@code I} gives {@code ı}). Words come from the word
     * {@link BreakIterator}; a segment is a word when it starts with a letter, and only a word with cased letters is
     * touched, so digits, punctuation, emoji and uncased scripts pass through. A letter that follows an apostrophe
     * inside a word stays lower case (<i>don't</i> gives <i>Don't</i>), a hyphen starts a new word (<i>well-known</i>
     * gives <i>Well-Known</i>). Deterministic and simple on purpose: a word the writer typed in all capitals is
     * lowered too (<i>NASA</i> gives <i>Nasa</i>), and mid-word capitals go (<i>iPhone</i> gives <i>Iphone</i>).
     */
    public static String titleCase(String text, Locale locale) {
        if (text == null || text.isEmpty()) return text;
        BreakIterator words = BreakIterator.getWordInstance(locale);
        words.setText(text);
        StringBuilder out = new StringBuilder(text.length());
        int start = words.first();
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            String seg = text.substring(start, end);
            int first = seg.codePointAt(0);
            if (!Character.isLetter(first) || !hasCase(seg)) {
                out.append(seg);
            } else if (followsApostropheInWord(text, start)) {
                out.append(seg.toLowerCase(locale));
            } else {
                // A hyphen starts a new word whether or not the platform's word rules break there (the JVM and ICU differ)
                int from = 0;
                for (int i = 0; i <= seg.length(); i++) {
                    if (i == seg.length() || isHyphen(seg.charAt(i))) {
                        out.append(capitalise(seg.substring(from, i), locale));
                        if (i < seg.length()) out.append(seg.charAt(i));
                        from = i + 1;
                    }
                }
            }
        }
        return out.toString();
    }

    /** One word: the first letter in title form, the rest lower case; a piece that does not start with a letter is kept. */
    private static String capitalise(String word, Locale locale) {
        if (word.isEmpty() || !Character.isLetter(word.codePointAt(0))) return word;
        int first = word.codePointAt(0);
        int n = Character.charCount(first);
        String up = word.substring(0, n).toUpperCase(locale);
        // A capital that needs two letters (the German sharp s gives SS) is not a title letter: use the title form
        if (up.codePointCount(0, up.length()) > 1) up = new String(Character.toChars(Character.toTitleCase(first)));
        return up + word.substring(n).toLowerCase(locale);
    }

    private static boolean isHyphen(char c) {
        return c == '-' || c == '\u2010' || c == '\u2011';
    }

    /** True when the letter at {@code at} directly follows an apostrophe that directly follows a letter (a contraction). */
    private static boolean followsApostropheInWord(String text, int at) {
        if (at < 2) return false;
        char mark = text.charAt(at - 1);
        return (mark == '\'' || mark == '\u2019') && Character.isLetter(text.codePointBefore(at - 1));
    }

    /** Whether the text has letters with case (Latin, Cyrillic, Greek...), so all caps is meaningful. */
    public static boolean hasCase(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.toUpperCase(cp) != cp || Character.toLowerCase(cp) != cp) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * The letter spacing to draw, a fraction of the text size: 0 when any letter is in a script whose letters join or
     * reorder ({@link TextMotion#JOINING_SCRIPTS}, the same table as the typewriter step and the server's
     * {@code NO_SPACING_SCRIPTS}), so a join is never pulled apart; the look's value otherwise.
     */
    public static double letterSpacing(String drawnText, double lookSpacing) {
        if (Double.isNaN(lookSpacing) || Double.isInfinite(lookSpacing)) return 0;
        return TextMotion.hasJoiningScript(drawnText) ? 0 : lookSpacing;
    }

    // ---- optical size of a script ----

    /** The probe size for {@link #scriptSizeFactor} (large, so rounding to whole pixels does not matter). */
    static final float PROBE_SIZE = 100f;
    /** A script is never drawn smaller than this share of the asked size by {@link #scriptSizeFactor}. */
    public static final float MIN_SCRIPT_FACTOR = 0.6f;
    /** The Latin run every script is compared with: cap height down to a descender, the full ink extent of a line. */
    static final String LATIN_REFERENCE = "Hg";

    /**
     * The one table of scripts that are drawn optically larger than Latin at the same size, each with a short run that
     * spans its usual ink extent (consonant, virama, consonant: the stacked conjunct form, tall and with a foot).
     * The Indic scripts have tall glyphs and line metrics; the factor is measured, never typed in, so it follows the
     * real system font. A script not listed is not touched (1). Same Unicode script names as {@link TextMotion}.
     */
    private static final Map<Character.UnicodeScript, String> TALL_SCRIPT_SAMPLE =
            new EnumMap<>(Character.UnicodeScript.class);

    static {
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.DEVANAGARI, "\u0915\u094D\u0937");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.BENGALI, "\u0995\u09CD\u09B7");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.GURMUKHI, "\u0A15\u0A4D\u0A38");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.GUJARATI, "\u0A95\u0ACD\u0AB7");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.ORIYA, "\u0B15\u0B4D\u0B37");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.TAMIL, "\u0B95\u0BCD\u0BB7");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.TELUGU, "\u0C15\u0C4D\u0C37");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.KANNADA, "\u0C95\u0CCD\u0CB7");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.MALAYALAM, "\u0D15\u0D4D\u0D37");
        TALL_SCRIPT_SAMPLE.put(Character.UnicodeScript.SINHALA, "\u0D9A\u0DCA\u0DC2");
    }

    /**
     * The share of the asked text size to draw {@code text} at so that its script looks as big as Latin: 1 for Latin and
     * every script not in the table (so the shipped looks draw Latin unchanged), else the measured Latin ink height
     * divided by the script's, never above 1 and never below {@link #MIN_SCRIPT_FACTOR}. A mixed text gets the average
     * over its letters, so a Latin word with one Bengali word shrinks a little, not by the full factor. A measurer that
     * cannot measure ({@link TextLayoutPolicy.TextMeasurer#inkHeight} of 0 or less) gives 1. The one owner of this
     * decision; {@link TextLayoutPolicy#fit} applies it, so layout, paint plan and renderer share the one drawn size.
     */
    public static float scriptSizeFactor(String text, TextLayoutPolicy.TextMeasurer measurer) {
        if (text == null || text.isEmpty() || measurer == null) return 1f;
        Map<Character.UnicodeScript, Integer> counts = new EnumMap<>(Character.UnicodeScript.class);
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (TALL_SCRIPT_SAMPLE.containsKey(script)) {
                Integer n = counts.get(script);
                counts.put(script, n == null ? 1 : n + 1);
            }
        }
        if (counts.isEmpty() || letters == 0) return 1f;
        float latin = measurer.inkHeight(LATIN_REFERENCE, PROBE_SIZE);
        if (!(latin > 0)) return 1f;
        double sum = letters - total(counts);
        for (Map.Entry<Character.UnicodeScript, Integer> e : counts.entrySet()) {
            float ink = measurer.inkHeight(TALL_SCRIPT_SAMPLE.get(e.getKey()), PROBE_SIZE);
            float f = ink > 0 ? Math.max(MIN_SCRIPT_FACTOR, Math.min(1f, latin / ink)) : 1f;
            sum += f * e.getValue();
        }
        return (float) (sum / letters);
    }

    private static int total(Map<Character.UnicodeScript, Integer> counts) {
        int t = 0;
        for (int n : counts.values()) t += n;
        return t;
    }

    /** Whether {@code script} has a measured size factor (for tests and the table's documentation). */
    static boolean isTallScript(Character.UnicodeScript script) {
        return TALL_SCRIPT_SAMPLE.containsKey(script);
    }
}
