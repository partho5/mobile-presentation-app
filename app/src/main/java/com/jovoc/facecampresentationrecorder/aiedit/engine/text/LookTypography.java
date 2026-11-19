package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The one owner of the three type decisions of a look: which system font family, which letters (case), and how far
 * apart (letter spacing). Pure (no Paint, Typeface or android.icu), so every rule is tested on the JVM; the Canvas
 * side only applies the answers. No per-language rule: only Unicode data ({@code toUpperCase(Locale)} and the
 * script table of {@link TextMotion}).
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
     * The text as the look draws it: capitals for {@code case: upper} in the plan language, else as written. A text
     * without cased letters (Bengali, Chinese, Thai, Arabic...) is returned unchanged.
     */
    public static String shownText(String text, boolean upper, String language) {
        if (!upper || !hasCase(text)) return text;
        return text.toUpperCase(localeOf(language));
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
}
