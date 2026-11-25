package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PlanAutoTargetTest {

    private static JSONObject day(String id, String date, boolean beats) throws Exception {
        JSONObject d = new JSONObject();
        d.put("id", id);
        d.put("date", date);
        d.put("title", "T" + id);
        d.put("version", 1);
        JSONArray b = new JSONArray();
        if (beats) b.put(new JSONObject().put("hook", "h" + id).put("say", "s"));
        d.put("beats", b);
        return d;
    }

    private static PlanWeek week(String status, String weekStatus, JSONObject... days) throws Exception {
        JSONObject w = new JSONObject();
        w.put("id", "w1");
        w.put("status", weekStatus);
        w.put("days", new JSONArray(Arrays.asList(days)));
        JSONObject o = new JSONObject();
        o.put("status", status);
        o.put("today", "2026-11-25");
        o.put("week", w);
        return PlanWeek.parse(o.toString());
    }

    private static Set<String> set(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test
    public void todaysFirstUnrecorded() throws Exception {
        PlanWeek pw = week("ACTIVE", "READY", day("a", "2026-11-25", true), day("b", "2026-11-25", true),
                day("c", "2026-11-26", true));
        assertEquals("a", PlanAutoTarget.target(pw, "2026-11-25", set()::contains).id);
        assertEquals("b", PlanAutoTarget.target(pw, "2026-11-25", set("a")::contains).id);
    }

    @Test
    public void allTodayRecorded_nextFutureDay() throws Exception {
        PlanWeek pw = week("ACTIVE", "READY", day("a", "2026-11-25", true), day("c", "2026-11-27", true),
                day("b", "2026-11-26", true));
        assertEquals("b", PlanAutoTarget.target(pw, "2026-11-25", set("a")::contains).id);
    }

    @Test
    public void pastDaysIgnored() throws Exception {
        PlanWeek pw = week("ACTIVE", "READY", day("p", "2026-11-24", true), day("f", "2026-11-26", true));
        assertEquals("f", PlanAutoTarget.target(pw, "2026-11-25", set()::contains).id);
        assertNull(PlanAutoTarget.target(pw, "2026-11-27", set()::contains));
    }

    @Test
    public void dayWithoutBeatsSkipped() throws Exception {
        PlanWeek pw = week("ACTIVE", "READY", day("a", "2026-11-25", false), day("b", "2026-11-26", true));
        assertEquals("b", PlanAutoTarget.target(pw, "2026-11-25", set()::contains).id);
    }

    @Test
    public void allRecordedOrNone_null() throws Exception {
        PlanWeek pw = week("ACTIVE", "READY", day("a", "2026-11-25", true));
        assertNull(PlanAutoTarget.target(pw, "2026-11-25", set("a")::contains));
        assertNull(PlanAutoTarget.target(week("ACTIVE", "READY"), "2026-11-25", set()::contains));
    }

    @Test
    public void inactiveOrNotReady_null() throws Exception {
        assertNull(PlanAutoTarget.target(week("SETUP", "READY", day("a", "2026-11-25", true)), "2026-11-25",
                set()::contains));
        assertNull(PlanAutoTarget.target(week("ACTIVE", "WRITING", day("a", "2026-11-25", true)), "2026-11-25",
                set()::contains));
        assertNull(PlanAutoTarget.target(PlanWeek.parse("{\"status\":\"ACTIVE\"}"), "2026-11-25", set()::contains));
    }

    @Test
    public void switchDecisions() throws Exception {
        PlanWeek pw = week("ACTIVE", "READY", day("a", "2026-11-25", true));
        PlanWeek.Day t = pw.week.days.get(0);
        // no target
        assertEquals(PlanAutoTarget.Switch.NONE, PlanAutoTarget.decide(null, null, null, false, false, true));
        // recording
        assertEquals(PlanAutoTarget.Switch.NONE, PlanAutoTarget.decide(t, null, null, true, false, true));
        // manager open
        assertEquals(PlanAutoTarget.Switch.NONE, PlanAutoTarget.decide(t, null, null, false, true, true));
        // set not saved yet
        assertEquals(PlanAutoTarget.Switch.NONE, PlanAutoTarget.decide(t, null, null, false, false, false));
        // already handled
        assertEquals(PlanAutoTarget.Switch.NONE, PlanAutoTarget.decide(t, "a", null, false, false, true));
        // already on screen: remember only
        assertEquals(PlanAutoTarget.Switch.MARK_ONLY, PlanAutoTarget.decide(t, null, "plan:a", false, false, true));
        assertEquals(PlanAutoTarget.Switch.MARK_ONLY, PlanAutoTarget.decide(t, "z", "plan:a", false, false, true));
        // switch from own topics or another set
        assertEquals(PlanAutoTarget.Switch.SWITCH, PlanAutoTarget.decide(t, null, null, false, false, true));
        assertEquals(PlanAutoTarget.Switch.SWITCH, PlanAutoTarget.decide(t, "z", "plan:z", false, false, true));
    }
}
