package com.jovoc.facecampresentationrecorder.contentplan;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlanSetPlannerTest {

    private static PlanSetIndex.Entry entry(int version, String sig) {
        return new PlanSetIndex.Entry("d1", "b1", "T", "2026-11-25", "PLANNED", version, sig, 1);
    }

    @Test
    public void rule1_noEntryNoSlides_create() {
        assertEquals(PlanSetPlanner.Action.CREATE, PlanSetPlanner.decide(null, false, "x", 1));
    }

    @Test
    public void rule2_noEntryWithSlides_adopt() {
        assertEquals(PlanSetPlanner.Action.ADOPT, PlanSetPlanner.decide(null, true, "x", 1));
    }

    @Test
    public void rule3_entryNoSlides_keep() {
        assertEquals(PlanSetPlanner.Action.KEEP, PlanSetPlanner.decide(entry(1, "x"), false, "y", 5));
    }

    @Test
    public void rule4_newerVersionUnedited_replace() {
        assertEquals(PlanSetPlanner.Action.REPLACE, PlanSetPlanner.decide(entry(1, "x"), true, "x", 2));
    }

    @Test
    public void newerVersionButEdited_keep() {
        assertEquals(PlanSetPlanner.Action.KEEP, PlanSetPlanner.decide(entry(1, "x"), true, "edited", 2));
    }

    @Test
    public void newerVersionNullSignature_keep() {
        assertEquals(PlanSetPlanner.Action.KEEP, PlanSetPlanner.decide(entry(1, null), true, null, 2));
        assertEquals(PlanSetPlanner.Action.KEEP, PlanSetPlanner.decide(entry(1, null), true, "x", 2));
    }

    @Test
    public void sameVersion_keep() {
        assertEquals(PlanSetPlanner.Action.KEEP, PlanSetPlanner.decide(entry(2, "x"), true, "x", 2));
    }

    @Test
    public void olderServerVersion_keep() {
        assertEquals(PlanSetPlanner.Action.KEEP, PlanSetPlanner.decide(entry(3, "x"), true, "x", 2));
    }
}
