package com.jovoc.facecampresentationrecorder.aiedit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The string matrix every text test runs (gate 6). Same list as the server's S1 fixture
 * ({@code contract/edit.strings.ts}): a single grapheme, the longest allowed text, left-to-right and right-to-left
 * scripts, scripts without spaces, joined and combined letters, emoji. Keep the two lists identical.
 */
public final class TestStrings {

    /** One string: an id, the text, its language tag, whether it reads right to left, whether it is over the caps. */
    public static final class Entry {
        public final String id;
        public final String text;
        public final String language;
        public final boolean rtl;
        public final boolean overCap;

        Entry(String id, String text, String language, boolean rtl, boolean overCap) {
            this.id = id;
            this.text = text;
            this.language = language;
            this.rtl = rtl;
            this.overCap = overCap;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** Every string of the matrix, in the server's order (the over-cap ones last). */
    public static final List<Entry> ALL;

    static {
        List<Entry> l = new ArrayList<>();
        l.add(new Entry("one_grapheme", "A", "en", false, false));
        l.add(new Entry("one_word", "Amazing", "en", false, false));
        // 4 words and 28 graphemes of the widest Latin capital: the longest text of the normal width class
        l.add(new Entry("longest_allowed", "WWWWWWW WWWWWWW WWWWWWW WWWW", "en", false, false));
        l.add(new Entry("all_caps_latin", "SAVE TIME NOW", "en", false, false));
        l.add(new Entry("bengali_conjunct", "শ্রেষ্ঠ", "bn", false, false));
        l.add(new Entry("devanagari", "नमस्ते दुनिया", "hi", false, false));
        l.add(new Entry("arabic", "مرحبا بالعالم", "ar", true, false));
        l.add(new Entry("hebrew", "שלום עולם", "he", true, false));
        l.add(new Entry("chinese", "节省时间", "zh", false, false));
        l.add(new Entry("japanese", "ありがとう", "ja", false, false));
        l.add(new Entry("thai", "สวัสดีครับ", "th", false, false));
        l.add(new Entry("cyrillic", "Привет мир", "ru", false, false));
        l.add(new Entry("turkish_i", "iğdır ışık", "tr", false, false));
        l.add(new Entry("mixed_ltr_rtl", "Save שלום 50% مرحبا", "en", false, false));
        l.add(new Entry("descenders", "gypsy jumping quickly", "en", false, false));
        l.add(new Entry("emoji_inside", "Great 🔥 job", "en", false, false));
        l.add(new Entry("over_cap_latin", "Extraordinarily unbelievable organizational transformation", "en", false, true));
        l.add(new Entry("over_cap_chinese",
                "今天我们一起来学习如何在每天的生活中节省更多的时间并且提高工作效率让生活变得更加美好", "zh", false, true));
        l.add(new Entry("over_cap_thai",
                "ฉันรักประเทศไทยมากที่สุดในโลกนี้และจะอยู่ที่นี่ตลอดไปไม่ว่าจะเกิดอะไรขึ้นก็ตาม", "th", false, true));
        ALL = Collections.unmodifiableList(l);
    }

    private TestStrings() {}

    /** The entry with {@code id}. */
    public static Entry get(String id) {
        for (Entry e : ALL) if (e.id.equals(id)) return e;
        throw new IllegalArgumentException(id);
    }
}
