package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class ContentPlanStoreTest {

    /** In-memory store. */
    static final class MapStore implements ContentPlanStore.Store {
        final Map<String, String> data = new LinkedHashMap<>();

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public void put(String key, String value) {
            data.put(key, value);
        }

        @Override
        public void remove(String key) {
            data.remove(key);
        }

        @Override
        public Collection<String> keys() {
            return new ArrayList<>(data.keySet());
        }
    }

    @Test
    public void weekIsKeptPerBrand() {
        ContentPlanStore s = new ContentPlanStore(new MapStore());
        s.saveWeek("b1", "{\"a\":1}", 100);
        s.saveWeek("b2", "{\"b\":2}", 200);
        assertEquals("{\"a\":1}", s.weekJson("b1"));
        assertEquals("{\"b\":2}", s.weekJson("b2"));
        assertEquals(100, s.fetchedAt("b1"));
        assertEquals(200, s.fetchedAt("b2"));
        assertNull(s.weekJson("b3"));
        assertEquals(0, s.fetchedAt("b3"));
        s.saveWeek("b1", "{\"a\":3}", 300);
        assertEquals("{\"a\":3}", s.weekJson("b1"));
        assertEquals("{\"b\":2}", s.weekJson("b2"));
    }

    @Test
    public void pendingAddAndRemove() {
        ContentPlanStore s = new ContentPlanStore(new MapStore());
        s.savePendingRecorded("d2", "b1", 200);
        s.savePendingRecorded("d1", "b1", 100);
        assertEquals(2, s.pendingRecorded().size());
        assertEquals("d1", s.pendingRecorded().get(0).dayId);
        assertEquals("b1", s.pendingRecorded().get(0).brandId);
        s.savePendingRecorded("d1", "b1", 150); // a repeat replaces
        assertEquals(2, s.pendingRecorded().size());
        s.removePending("d1");
        assertEquals(1, s.pendingRecorded().size());
        assertEquals("d2", s.pendingRecorded().get(0).dayId);
        s.removePending("d2");
        assertTrue(s.pendingRecorded().isEmpty());
    }

    @Test
    public void localRecorded_isPrunedAfter30Days() {
        ContentPlanStore s = new ContentPlanStore(new MapStore());
        long day = 24L * 60 * 60 * 1000;
        s.markRecordedLocally("old", 0);
        assertTrue(s.recordedLocally("old"));
        s.markRecordedLocally("recent", 20 * day);
        assertTrue(s.recordedLocally("old")); // 20 days: still kept
        s.markRecordedLocally("new", 31 * day);
        assertFalse(s.recordedLocally("old"));
        assertTrue(s.recordedLocally("recent"));
        assertTrue(s.recordedLocally("new"));
        assertFalse(s.recordedLocally("never"));
    }
}
