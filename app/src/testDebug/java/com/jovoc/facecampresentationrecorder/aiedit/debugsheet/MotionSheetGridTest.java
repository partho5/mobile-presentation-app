package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.TestStrings;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The debug motion sheet's grid: which motion, string and ratio sit where, and that it is pure and deterministic. */
public class MotionSheetGridTest {

    @Test
    public void theTickedMotionsOfTheBuildAreEveryNonLegacyMotionOfTheSheetInGridOrder() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (MotionSheetGrid.Motion m : MotionSheetGrid.MOTIONS) if (!m.legacy) ids.add(m.serverId());
        assertEquals(ids, com.jovoc.facecampresentationrecorder.aiedit.engine.contract.Capabilities.DEVICE_PASSED_TEXT_MOTIONS);
    }

    @Test
    public void theSheetStringsAreTheTestMatrixExactly() {
        assertEquals(TestStrings.ALL.size(), MotionSheetStrings.ALL.size());
        for (int i = 0; i < TestStrings.ALL.size(); i++) {
            TestStrings.Entry t = TestStrings.ALL.get(i);
            MotionSheetStrings.Entry s = MotionSheetStrings.ALL.get(i);
            assertEquals(t.id, s.id);
            assertEquals(t.id, t.text, s.text);
            assertEquals(t.id, t.rtl, s.rtl);
            assertEquals(t.id, t.overCap, s.overCap);
        }
    }

    @Test
    public void everyCellIsMotionMajorThenStringThenRatioNineSixteenFirst() {
        assertEquals("9:16", MotionSheetGrid.RATIOS.get(0));
        assertEquals(3, MotionSheetGrid.ratioCount());
        assertEquals(MotionSheetGrid.motionCount() * MotionSheetGrid.stringCount() * 3, MotionSheetGrid.size());
        int n = 0;
        for (int m = 0; m < MotionSheetGrid.motionCount(); m++) {
            for (int s = 0; s < MotionSheetGrid.stringCount(); s++) {
                for (int r = 0; r < 3; r++) {
                    MotionSheetGrid.Cell c = MotionSheetGrid.cell(m, s, r);
                    assertEquals(n, c.index);
                    assertEquals(n, MotionSheetGrid.index(m, s, r));
                    assertEquals(MotionSheetGrid.MOTIONS.get(m), c.motion);
                    assertEquals(MotionSheetStrings.ALL.get(s), c.string);
                    assertEquals(MotionSheetGrid.RATIOS.get(r), c.ratio);
                    MotionSheetGrid.Cell back = MotionSheetGrid.cell(n);
                    assertEquals(c.motion, back.motion);
                    assertEquals(c.string, back.string);
                    assertEquals(c.ratio, back.ratio);
                    n++;
                }
            }
        }
        assertEquals(MotionSheetGrid.size(), n);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void aCellPastTheEndIsRefused() {
        MotionSheetGrid.cell(MotionSheetGrid.size());
    }

    @Test
    public void everyMotionOfTheTableIsOnTheSheetOnceWithItsServerName() {
        Set<String> ids = new HashSet<>();
        for (MotionSheetGrid.Motion m : MotionSheetGrid.MOTIONS) assertTrue(m.serverId(), ids.add(m.serverId()));
        for (String e : new String[]{"fade", "pop", "slide_left", "slide_right", "slide_top", "slide_bottom", "typewriter",
                "word_by_word", "wipe", "drop_bounce", "blur_in", "flip", "stamp", "glitch"}) {
            assertTrue(e, ids.contains(e));
        }
        for (String h : new String[]{"float", "pulse", "shake", "shimmer", "neon_flicker"}) assertTrue(h, ids.contains("hold_" + h));
        for (String x : new String[]{"fade", "slide_out", "shrink", "pop_out", "typewriter_delete", "wipe_out"}) {
            assertTrue(x, ids.contains("exit_" + x));
        }
        assertEquals(14 + 5 + 6, ids.size());
    }

    @Test
    public void aCellsPlanCarriesOnlyItsMotionKeyAndPlaysOneItem() throws Exception {
        for (MotionSheetGrid.Motion m : MotionSheetGrid.MOTIONS) {
            Plan plan = MotionSheetGrid.plan(MotionSheetGrid.cell(MotionSheetGrid.MOTIONS.indexOf(m), 2, 0));
            assertEquals(1, plan.items.size());
            PlanItem item = plan.items.get(0);
            assertEquals(0, item.startMs);
            assertEquals(MotionSheetGrid.ITEM_MS, item.endMs);
            assertEquals(TestStrings.get("longest_allowed").text, item.component.text);
            Map<String, Object> p = item.params;
            assertEquals(m.kind == MotionSheetGrid.ENTRY ? m.id : null, p.get("entry"));
            assertEquals(m.kind == MotionSheetGrid.HOLD ? m.id : null, p.get("hold"));
            assertEquals(m.kind == MotionSheetGrid.EXIT ? m.id : null, p.get("exit"));
        }
        EngineContract contract = TestContract.load();
        assertNotNull(contract.effect(MotionSheetGrid.EFFECT));
        assertNotNull(contract.textLook(MotionSheetGrid.LOOK));
        assertNotNull(contract.zone(MotionSheetGrid.ZONE));
        for (String r : MotionSheetGrid.RATIOS) assertNotNull(r, contract.output(r));
    }

    @Test
    public void theSameCellAlwaysGivesTheSamePlan() {
        for (int i = 0; i < MotionSheetGrid.size(); i += 7) {
            PlanItem a = MotionSheetGrid.plan(MotionSheetGrid.cell(i)).items.get(0);
            PlanItem b = MotionSheetGrid.plan(MotionSheetGrid.cell(i)).items.get(0);
            assertEquals(a.params, b.params);
            assertEquals(a.component.text, b.component.text);
            assertEquals(a.endMs, b.endMs);
        }
    }

    @Test
    public void theReportPrintsPassedIdsInServerNamingWithoutLegacyOnes() {
        Map<String, MotionSheetGrid.Verdict> v = MotionSheetGrid.emptyVerdicts();
        v.put("fade", MotionSheetGrid.Verdict.PASS);
        v.put("pop", MotionSheetGrid.Verdict.PASS);             // already shipped: not printed
        v.put("slide_left", MotionSheetGrid.Verdict.PASS);
        v.put("hold_float", MotionSheetGrid.Verdict.PASS);
        v.put("exit_wipe_out", MotionSheetGrid.Verdict.PASS);
        v.put("glitch", MotionSheetGrid.Verdict.FAIL);
        String report = MotionSheetGrid.report(v);
        String first = report.split("\n")[0];
        assertEquals("Passed on a device, motions: fade, slide_left, hold_float, exit_wipe_out", first);
        assertTrue(report.contains("Failed: glitch"));
        assertFalse(first.contains("pop"));
        assertEquals(report, MotionSheetGrid.report(v));
        assertEquals("Passed on a device, motions: none", MotionSheetGrid.report(MotionSheetGrid.emptyVerdicts()).split("\n")[0]);
        assertNull(MotionSheetGrid.emptyVerdicts().get("not_a_motion"));
    }
}
