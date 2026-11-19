package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The debug look sheet's grid: which look, string and ratio sit where, the plan each cell draws, and the owner's report. */
public class LookSheetGridTest {

    private EngineContract contract;
    private List<TextLook> looks;
    private List<String> ids;

    @Before
    public void load() throws Exception {
        contract = TestContract.load();
        looks = contract.getTextLooks();
        ids = new ArrayList<>();
        for (TextLook l : looks) ids.add(l.id);
    }

    @Test
    public void theSheetCoversEveryLookOfTheBundledContract() {
        assertEquals(16, ids.size());
        for (String id : new String[]{"sticker", "label_pill", "3d", "glow", "gradient", "highlighter", "neon_box", "pill_ring",
                "gradient_block", "tilt_sticker", "solid_round", "pastel_pill", "flat_rect", "yellow_bold", "dark_accent",
                "coral_pill"}) {
            assertTrue(id, ids.contains(id));
        }
    }

    @Test
    public void everyCellIsLookMajorThenStringThenRatioNineSixteenFirst() {
        assertEquals("9:16", LookSheetGrid.RATIOS.get(0));
        assertEquals(3, LookSheetGrid.ratioCount());
        assertEquals(ids.size() * LookSheetGrid.stringCount() * 3, LookSheetGrid.size(ids.size()));
        int n = 0;
        for (int l = 0; l < ids.size(); l++) {
            for (int s = 0; s < LookSheetGrid.stringCount(); s++) {
                for (int r = 0; r < 3; r++) {
                    LookSheetGrid.Cell c = LookSheetGrid.cell(ids, l, s, r);
                    assertEquals(n, c.index);
                    assertEquals(n, LookSheetGrid.index(l, s, r));
                    assertEquals(ids.get(l), c.lookId);
                    assertEquals(l, c.lookIdx);
                    assertEquals(MotionSheetStrings.ALL.get(s), c.string);
                    assertEquals(LookSheetGrid.RATIOS.get(r), c.ratio);
                    LookSheetGrid.Cell back = LookSheetGrid.cell(ids, n);
                    assertEquals(c.lookId, back.lookId);
                    assertEquals(c.string, back.string);
                    assertEquals(c.ratio, back.ratio);
                    n++;
                }
            }
        }
        assertEquals(LookSheetGrid.size(ids.size()), n);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void aCellPastTheEndIsRefused() {
        LookSheetGrid.cell(ids, LookSheetGrid.size(ids.size()));
    }

    @Test
    public void aPageHoldsEveryLookInContractOrderForOneStringAndRatio() {
        List<LookSheetGrid.Cell> page = LookSheetGrid.page(ids, 4, 1);
        assertEquals(ids.size(), page.size());
        for (int i = 0; i < page.size(); i++) {
            assertEquals(ids.get(i), page.get(i).lookId);
            assertEquals(MotionSheetStrings.ALL.get(4), page.get(i).string);
            assertEquals("4:5", page.get(i).ratio);
        }
    }

    @Test
    public void tilesSitInTwoColumnsLikeTheReferenceSheets() {
        assertEquals(2, LookSheetGrid.COLUMNS);
        assertEquals(0, LookSheetGrid.row(0));
        assertEquals(0, LookSheetGrid.row(1));
        assertEquals(1, LookSheetGrid.row(2));
        assertEquals(1, LookSheetGrid.column(3));
        assertEquals(8, LookSheetGrid.rows(16));
        assertEquals(8, LookSheetGrid.rows(15));
    }

    @Test
    public void aCellsPlanDrawsItsLookWithItsSlotAndTiltAndOnlyThat() {
        Set<Integer> slots = new HashSet<>();
        for (int l = 0; l < looks.size(); l++) {
            LookSheetGrid.Cell cell = LookSheetGrid.cell(ids, l, 2, 0);
            Plan plan = LookSheetGrid.plan(cell, looks.get(l));
            assertEquals(1, plan.items.size());
            PlanItem item = plan.items.get(0);
            assertEquals(ids.get(l), item.component.stylePreset);
            assertEquals(LookSheetGrid.colorSlot(l), item.component.colorSlot);
            assertEquals(TestStringsText.longest(), item.component.text);
            assertEquals(0, item.startMs);
            assertEquals(MotionSheetGrid.ITEM_MS, item.endMs);
            assertEquals(LookSheetGrid.tilt(looks.get(l)), ((Number) item.params.get("tilt_deg")).doubleValue(), 0);
            assertFalse(item.params.containsKey("entry"));
            slots.add(item.component.colorSlot);
        }
        assertEquals(3, slots.size());
        assertNotNull(contract.effect(LookSheetGrid.EFFECT));
        assertNotNull(contract.zone(LookSheetGrid.ZONE));
        for (String r : LookSheetGrid.RATIOS) assertNotNull(r, contract.output(r));
    }

    @Test
    public void tiltIsTheFurthestEndOfTheRangeAndZeroWithoutOne() {
        assertEquals(6.0, LookSheetGrid.tilt(contract.textLook("sticker")), 0);
        assertEquals(-7.0, LookSheetGrid.tilt(contract.textLook("tilt_sticker")), 0);
        assertEquals(0.0, LookSheetGrid.tilt(contract.textLook("label_pill")), 0);
    }

    @Test
    public void onlyTheTurkishStringNamesALanguage() {
        for (MotionSheetStrings.Entry e : MotionSheetStrings.ALL) {
            assertEquals(e.id, "turkish_i".equals(e.id) ? "tr" : null, LookSheetGrid.language(e));
        }
    }

    @Test
    public void theSameCellAlwaysGivesTheSamePlan() {
        for (int i = 0; i < LookSheetGrid.size(ids.size()); i += 11) {
            LookSheetGrid.Cell c = LookSheetGrid.cell(ids, i);
            PlanItem a = LookSheetGrid.plan(c, looks.get(c.lookIdx)).items.get(0);
            PlanItem b = LookSheetGrid.plan(c, looks.get(c.lookIdx)).items.get(0);
            assertEquals(a.params, b.params);
            assertEquals(a.component.text, b.component.text);
            assertEquals(a.component.stylePreset, b.component.stylePreset);
        }
    }

    @Test
    public void theReportListsPassedNewLooksWithTheirFeaturesAndLeavesShippedOnesOut() {
        Map<String, MotionSheetGrid.Verdict> v = LookSheetGrid.emptyVerdicts(ids);
        v.put("neon_box", MotionSheetGrid.Verdict.PASS);
        v.put("pill_ring", MotionSheetGrid.Verdict.PASS);
        v.put("gradient_block", MotionSheetGrid.Verdict.FAIL);
        v.put("sticker", MotionSheetGrid.Verdict.PASS);       // already shipped: not in the pasted list
        v.put("glow", MotionSheetGrid.Verdict.FAIL);
        String report = LookSheetGrid.report(looks, v);
        String[] lines = report.split("\n");
        assertEquals("Passed on a device, looks: neon_box, pill_ring", lines[0]);
        // the server's order of feature ids, once each
        assertEquals("Features those looks need: box_border, box_glow, font_weight, case_as_written", lines[1]);
        assertTrue(report, report.contains("  neon_box needs box_border, box_glow, font_weight, case_as_written"));
        assertTrue(report, report.contains("  pill_ring needs box_border, case_as_written"));
        assertTrue(report, report.contains("Failed: gradient_block"));
        assertFalse(lines[0].contains("sticker"));
        assertTrue(report, report.contains("Shipped looks (not listed above): passed sticker; failed glow"));
        assertTrue(report, report.contains("Not ticked: tilt_sticker, solid_round"));
        assertEquals(report, LookSheetGrid.report(looks, v));
    }

    @Test
    public void anEmptySheetPrintsNoneAndEveryNewLookAsNotTicked() {
        String report = LookSheetGrid.report(looks, LookSheetGrid.emptyVerdicts(ids));
        String[] lines = report.split("\n");
        assertEquals("Passed on a device, looks: none", lines[0]);
        assertEquals("Features those looks need: none", lines[1]);
        assertTrue(report, report.contains("Not ticked: neon_box, pill_ring, gradient_block, tilt_sticker, solid_round, "
                + "pastel_pill, flat_rect, yellow_bold, dark_accent, coral_pill"));
    }

    @Test
    public void everyListedLookCarriesTheFeaturesThatCapabilitiesWouldAsk() {
        // dark_accent and coral_pill name the condensed family, so they need font_family as well
        Map<String, MotionSheetGrid.Verdict> v = LookSheetGrid.emptyVerdicts(ids);
        v.put("dark_accent", MotionSheetGrid.Verdict.PASS);
        String report = LookSheetGrid.report(looks, v);
        assertTrue(report, report.contains("  dark_accent needs box_border, font_family, case_as_written"));
    }

    /** The longest-allowed string of the matrix, by id. */
    private static final class TestStringsText {
        static String longest() {
            for (MotionSheetStrings.Entry e : MotionSheetStrings.ALL) if ("longest_allowed".equals(e.id)) return e.text;
            throw new AssertionError("no longest_allowed");
        }
    }
}
