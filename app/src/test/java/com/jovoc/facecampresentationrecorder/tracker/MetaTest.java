package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class MetaTest {

    @Test
    public void ofKeepsOnlyCompletePairs() {
        Meta m = Meta.of("a", 1, "b");
        assertEquals(1, m.values.size());
        assertEquals(1, m.values.get("a"));
        assertFalse(m.values.containsKey("b"));
    }

    @Test
    public void nullValuesAndNullKeysAreSkipped() {
        Meta m = Meta.of("a", null, null, 2, "c", 3);
        assertEquals(1, m.values.size());
        assertEquals(3, m.values.get("c"));
    }

    @Test
    public void longStringIsCutTo200() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('x');
        Meta m = Meta.of("text", sb.toString());
        assertEquals(200, ((String) m.values.get("text")).length());
    }

    @Test
    public void onlyTwentyKeysAreKept() {
        Meta m = new Meta();
        for (int i = 0; i < 21; i++) m.put("k" + i, i);
        assertEquals(20, m.values.size());
        assertFalse(m.values.containsKey("k20"));
        assertTrue(m.values.containsKey("k19"));
    }

    @Test
    public void keysAreCleaned() {
        Meta m = Meta.of("Topic Type", "video");
        assertEquals("video", m.values.get("topic_type"));
    }

    @Test
    public void nestedObjectIsKeptAsItsStringForm() {
        java.util.List<Integer> list = Arrays.asList(1, 2);
        Meta m = Meta.of("list", list);
        assertEquals(String.valueOf(list), m.values.get("list"));
    }

    @Test
    public void nanIsSkipped() {
        Meta m = Meta.of("x", Double.NaN, "y", Double.POSITIVE_INFINITY, "z", 1.5);
        assertFalse(m.values.containsKey("x"));
        assertFalse(m.values.containsKey("y"));
        assertEquals(1.5, m.values.get("z"));
    }

    @Test
    public void mergeCopiesEntriesAndIgnoresNull() {
        Meta m = Meta.of("a", 1).merge(Meta.of("b", true)).merge(null);
        assertEquals(2, m.values.size());
        assertEquals(true, m.values.get("b"));
    }

    @Test
    public void emptyMetaHasNoJson() throws Exception {
        assertNull(new Meta().toJson());
        assertEquals(2, Meta.of("n", 2).toJson().getInt("n"));
    }
}
