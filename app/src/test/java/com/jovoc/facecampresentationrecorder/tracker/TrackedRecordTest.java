package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class TrackedRecordTest {

    private static TrackedRecord record(String name, String kind) {
        TrackedRecord r = new TrackedRecord();
        r.id = "00000000-0000-4000-8000-000000000001";
        r.seq = 7;
        r.session = "a1b2c3d4";
        r.kind = kind;
        r.name = name;
        r.startedAt = 1763711990000L;
        r.tz = 360;
        r.appBuild = 4;
        return r;
    }

    @Test
    public void cleanNameTurnsSpacesAndDashesIntoUnderscores() {
        assertEquals("double_click", TrackedRecord.cleanName("double click"));
        assertEquals("app_open", TrackedRecord.cleanName("  App-Open "));
    }

    @Test
    public void cleanNameDropsEmptyAndCutsTo40() {
        assertNull(TrackedRecord.cleanName("!!!"));
        assertNull(TrackedRecord.cleanName(null));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('a');
        assertEquals(40, TrackedRecord.cleanName(sb.toString()).length());
    }

    @Test
    public void instantRecordHasNoEndedAt() throws Exception {
        JSONObject o = new JSONObject(record("click", "activity").toJson());
        assertFalse(o.has("endedAt"));
        assertEquals("click", o.getString("name"));
        assertEquals("activity", o.getString("kind"));
        assertEquals(7, o.getLong("seq"));
        assertEquals("a1b2c3d4", o.getString("session"));
    }

    @Test
    public void spanHasBothTimes() throws Exception {
        TrackedRecord r = record("record", "activity");
        r.endedAt = 1763711992500L;
        JSONObject o = new JSONObject(r.toJson());
        assertEquals(1763711990000L, o.getLong("startedAt"));
        assertEquals(1763711992500L, o.getLong("endedAt"));
    }

    @Test
    public void errorRecordCarriesAnErrorObject() throws Exception {
        TrackedRecord r = record("record", "error");
        r.error = ErrorInfo.from(new IllegalStateException("boom"), true, "main", "click");
        JSONObject o = new JSONObject(r.toJson());
        assertEquals("error", o.getString("kind"));
        assertTrue(o.has("error"));
        assertEquals("java.lang.IllegalStateException", o.getJSONObject("error").getString("type"));
    }

    @Test
    public void nullNameMeansNoJson() {
        assertNull(record(null, "activity").toJson());
    }

    @Test
    public void metaAndOptionalFieldsAppearOnlyWhenSet() throws Exception {
        TrackedRecord r = record("toggle", "activity");
        r.description = "menu bar";
        r.meta = Meta.of("on", true);
        JSONObject o = new JSONObject(r.toJson());
        assertEquals("menu bar", o.getString("description"));
        assertEquals(true, o.getJSONObject("meta").getBoolean("on"));
        assertFalse(o.has("screen"));
        assertFalse(o.has("source"));
    }
}
