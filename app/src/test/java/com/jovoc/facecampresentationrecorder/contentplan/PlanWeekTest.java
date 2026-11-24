package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PlanWeekTest {

    private static final String SAMPLE_DAY = "{ \"id\": \"d7f1\", \"kind\": \"PLANNED\", \"date\": \"2026-11-23\", "
            + "\"dayIndex\": 0, \"title\": \"One Idea Beats Ten Tips\", \"angle\": \"mistake\","
            + "\"why\": \"Your viewers leave when a video tries to say everything.\","
            + "\"beats\": [ { \"hook\": \"Pick one takeaway\", \"say\": \"Stop trying to say everything today.\" },"
            + " { \"hook\": \"Talk to one person\", \"say\": \"Explain it to a client.\" },"
            + " { \"hook\": \"Stop at one idea\", \"say\": \"One idea keeps it short.\" } ],"
            + "\"closingLine\": \"Follow for a new tip tomorrow.\", \"status\": \"PLANNED\", \"version\": 1 }";

    private static String week(String weekStatus, String days) {
        return "{\"status\":\"ACTIVE\",\"today\":\"2026-11-23\",\"timezone\":\"Asia/Dhaka\","
                + "\"week\":{\"id\":\"w1\",\"weekStart\":\"2026-11-23\",\"weekEnd\":\"2026-11-29\","
                + "\"idea\":\"focus\",\"status\":\"" + weekStatus + "\",\"days\":[" + days + "]}}";
    }

    @Test
    public void contractSample_parses() throws Exception {
        PlanWeek pw = PlanWeek.parse(week("READY", SAMPLE_DAY));
        assertEquals("ACTIVE", pw.status);
        assertEquals("2026-11-23", pw.today);
        assertEquals("Asia/Dhaka", pw.timezone);
        assertTrue(pw.isActive());
        assertTrue(pw.isReady());
        assertEquals("w1", pw.week.id);
        assertEquals("2026-11-29", pw.week.weekEnd);
        PlanWeek.Day d = pw.week.days.get(0);
        assertEquals("d7f1", d.id);
        assertEquals("PLANNED", d.kind);
        assertFalse(d.isInstantIdea());
        assertEquals(0, d.dayIndex);
        assertEquals("mistake", d.angle);
        assertEquals(3, d.beats.size());
        assertEquals("Pick one takeaway", d.beats.get(0).hook);
        assertEquals("Explain it to a client.", d.beats.get(1).say);
        assertEquals("Follow for a new tip tomorrow.", d.closingLine);
        assertEquals(1, d.version);
    }

    @Test
    public void daysEmptyWhenNotReady() throws Exception {
        PlanWeek pw = PlanWeek.parse(week("GENERATING", ""));
        assertFalse(pw.isReady());
        assertTrue(pw.week.days.isEmpty());
        assertTrue(pw.daysFor("2026-11-23").isEmpty());
    }

    @Test
    public void nullWeek() throws Exception {
        PlanWeek pw = PlanWeek.parse("{\"status\":\"NONE\",\"today\":\"2026-11-23\",\"timezone\":\"UTC\",\"week\":null}");
        assertNull(pw.week);
        assertFalse(pw.isReady());
        assertFalse(pw.isActive());
        assertTrue(pw.daysFor("2026-11-23").isEmpty());
    }

    @Test
    public void dayWithoutId_isSkipped() throws Exception {
        String noId = "{\"date\":\"2026-11-24\",\"title\":\"x\",\"beats\":[{\"hook\":\"h\",\"say\":\"s\"}]}";
        PlanWeek pw = PlanWeek.parse(week("READY", noId + "," + SAMPLE_DAY));
        assertEquals(1, pw.week.days.size());
        assertEquals("d7f1", pw.week.days.get(0).id);
    }

    @Test
    public void beats_emptyHookDropped_missingSayIsEmpty() throws Exception {
        String day = "{\"id\":\"a\",\"date\":\"2026-11-23\",\"beats\":[{\"hook\":\"\",\"say\":\"s\"},"
                + "{\"hook\":\"keep\"},{\"say\":\"no hook\"}]}";
        PlanWeek.Day d = PlanWeek.parse(week("READY", day)).week.days.get(0);
        assertEquals(1, d.beats.size());
        assertEquals("keep", d.beats.get(0).hook);
        assertEquals("", d.beats.get(0).say);
    }

    @Test
    public void daysFor_filtersByDate() throws Exception {
        String other = SAMPLE_DAY.replace("d7f1", "d2").replace("2026-11-23", "2026-11-25");
        PlanWeek pw = PlanWeek.parse(week("READY", SAMPLE_DAY + "," + other));
        assertEquals(1, pw.daysFor("2026-11-25").size());
        assertEquals("d2", pw.daysFor("2026-11-25").get(0).id);
        assertTrue(pw.daysFor("2026-11-30").isEmpty());
    }

    @Test
    public void instantIdea_withNullDayIndexAndAngle() throws Exception {
        String idea = "{\"id\":\"i1\",\"kind\":\"INSTANT_IDEA\",\"date\":\"2026-11-23\",\"dayIndex\":null,"
                + "\"title\":\"Mine\",\"angle\":null,\"why\":\"w\",\"beats\":[{\"hook\":\"h\",\"say\":\"s\"}],"
                + "\"closingLine\":\"c\",\"status\":\"PLANNED\",\"version\":2}";
        PlanWeek.Day d = PlanWeek.parse(week("READY", idea)).week.days.get(0);
        assertTrue(d.isInstantIdea());
        assertEquals(-1, d.dayIndex);
        assertEquals("", d.angle);
        assertEquals(2, d.version);
    }

    @Test
    public void missingKind_isPlanned() throws Exception {
        String day = "{\"id\":\"a\",\"date\":\"2026-11-23\",\"beats\":[]}";
        PlanWeek.Day d = PlanWeek.parse(week("READY", day)).week.days.get(0);
        assertEquals("PLANNED", d.kind);
        assertEquals(-1, d.dayIndex);
        assertEquals("", d.angle);
    }

    @Test
    public void twoDaysOnOneDate_keepServerOrder() throws Exception {
        String idea = "{\"id\":\"i1\",\"kind\":\"INSTANT_IDEA\",\"date\":\"2026-11-23\",\"dayIndex\":null,"
                + "\"angle\":null,\"beats\":[]}";
        List<PlanWeek.Day> list = PlanWeek.parse(week("READY", SAMPLE_DAY + "," + idea)).daysFor("2026-11-23");
        assertEquals(2, list.size());
        assertEquals("d7f1", list.get(0).id);
        assertEquals("i1", list.get(1).id);
    }
}
