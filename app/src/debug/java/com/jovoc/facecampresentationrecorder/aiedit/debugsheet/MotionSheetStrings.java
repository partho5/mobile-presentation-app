package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The string matrix (gate 6) for the debug motion sheet. A debug-only copy of the test fixture
 * {@code aiedit/TestStrings} (that one lives in the test source set, which the app cannot use); a JVM test keeps the two
 * identical, so a change in one without the other fails.
 */
public final class MotionSheetStrings {

    /** One string: an id, its text, whether it reads right to left, whether it is over the caps (expected to skip). */
    public static final class Entry {
        public final String id;
        public final String text;
        public final boolean rtl;
        public final boolean overCap;

        Entry(String id, String text, boolean rtl, boolean overCap) {
            this.id = id;
            this.text = text;
            this.rtl = rtl;
            this.overCap = overCap;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** Every string, in the server's order (the over-cap ones last). */
    public static final List<Entry> ALL;

    static {
        List<Entry> l = new ArrayList<>();
        l.add(new Entry("one_grapheme", "A", false, false));
        l.add(new Entry("one_word", "Amazing", false, false));
        l.add(new Entry("longest_allowed", "WWWWWWW WWWWWWW WWWWWWW WWWW", false, false));
        l.add(new Entry("all_caps_latin", "SAVE TIME NOW", false, false));
        l.add(new Entry("bengali_conjunct", "শ্রেষ্ঠ", false, false));
        l.add(new Entry("devanagari", "नमस्ते दुनिया", false, false));
        l.add(new Entry("arabic", "مرحبا بالعالم", true, false));
        l.add(new Entry("hebrew", "שלום עולם", true, false));
        l.add(new Entry("chinese", "节省时间", false, false));
        l.add(new Entry("japanese", "ありがとう", false, false));
        l.add(new Entry("thai", "สวัสดีครับ", false, false));
        l.add(new Entry("cyrillic", "Привет мир", false, false));
        l.add(new Entry("turkish_i", "iğdır ışık", false, false));
        l.add(new Entry("mixed_ltr_rtl", "Save שלום 50% مرحبا", false, false));
        l.add(new Entry("descenders", "gypsy jumping quickly", false, false));
        l.add(new Entry("emoji_inside", "Great 🔥 job", false, false));
        l.add(new Entry("over_cap_latin", "Extraordinarily unbelievable organizational transformation", false, true));
        l.add(new Entry("over_cap_chinese",
                "今天我们一起来学习如何在每天的生活中节省更多的时间并且提高工作效率让生活变得更加美好", false, true));
        l.add(new Entry("over_cap_thai",
                "ฉันรักประเทศไทยมากที่สุดในโลกนี้และจะอยู่ที่นี่ตลอดไปไม่ว่าจะเกิดอะไรขึ้นก็ตาม", false, true));
        ALL = Collections.unmodifiableList(l);
    }

    private MotionSheetStrings() {}
}
