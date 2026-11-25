package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class PlanSetIndexTest {

    private static PlanSetIndex.Entry entry(String id, String date, long created, String sig) {
        return new PlanSetIndex.Entry(id, "b1", "Title " + id, date, "PLANNED", 3, sig, created);
    }

    @Test
    public void putGetRemove() {
        PlanSetIndex idx = new PlanSetIndex(new ContentPlanStoreTest.MapStore());
        assertNull(idx.get("d1"));
        idx.put(entry("d1", "2026-11-25", 10, "abc"));
        PlanSetIndex.Entry e = idx.get("d1");
        assertEquals("d1", e.dayId);
        assertEquals("b1", e.brandId);
        assertEquals("Title d1", e.title);
        assertEquals("2026-11-25", e.date);
        assertEquals("PLANNED", e.kind);
        assertEquals(3, e.version);
        assertEquals("abc", e.signature);
        assertEquals(10, e.createdMs);
        idx.remove("d1");
        assertNull(idx.get("d1"));
        assertTrue(idx.all().isEmpty());
    }

    @Test
    public void nullSignatureAndBrandSurvive() {
        PlanSetIndex idx = new PlanSetIndex(new ContentPlanStoreTest.MapStore());
        idx.put(new PlanSetIndex.Entry("d1", null, "T", "2026-11-25", "PLANNED", 1, null, 5));
        PlanSetIndex.Entry e = idx.get("d1");
        assertNull(e.signature);
        assertNull(e.brandId);
    }

    @Test
    public void putReplaces() {
        PlanSetIndex idx = new PlanSetIndex(new ContentPlanStoreTest.MapStore());
        idx.put(entry("d1", "2026-11-25", 10, "a"));
        idx.put(entry("d1", "2026-11-25", 10, "b"));
        assertEquals(1, idx.all().size());
        assertEquals("b", idx.get("d1").signature);
    }

    @Test
    public void allIsOrderedByDateThenCreated() {
        PlanSetIndex idx = new PlanSetIndex(new ContentPlanStoreTest.MapStore());
        idx.put(entry("c", "2026-11-27", 1, null));
        idx.put(entry("b", "2026-11-26", 30, null));
        idx.put(entry("a", "2026-11-26", 20, null));
        List<PlanSetIndex.Entry> all = idx.all();
        assertEquals(3, all.size());
        assertEquals("a", all.get(0).dayId);
        assertEquals("b", all.get(1).dayId);
        assertEquals("c", all.get(2).dayId);
    }

    @Test
    public void malformedValueIsSkipped() {
        ContentPlanStoreTest.MapStore store = new ContentPlanStoreTest.MapStore();
        PlanSetIndex idx = new PlanSetIndex(store);
        store.put("set:bad", "not json {");
        store.put("other:key", "{}");
        idx.put(entry("ok", "2026-11-25", 1, "s"));
        assertNull(idx.get("bad"));
        List<PlanSetIndex.Entry> all = idx.all();
        assertEquals(1, all.size());
        assertEquals("ok", all.get(0).dayId);
    }
}
