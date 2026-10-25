package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.edit.EditProject.Range;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EditTimelineMathTest {

    private static final long DUR = 10_000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Range r(long s, long e) {
        return new Range(s, e);
    }

    private static final List<Range> TWO = Arrays.asList(r(1000, 3000), r(5000, 9000));

    @Test
    public void indexAtFindsSegmentsAndGaps() {
        assertEquals(0, EditTimelineMath.indexAt(TWO, 1000));
        assertEquals(-1, EditTimelineMath.indexAt(TWO, 3000));
        assertEquals(-1, EditTimelineMath.indexAt(TWO, 4000));
        assertEquals(1, EditTimelineMath.indexAt(TWO, 5000));
        assertEquals(1, EditTimelineMath.indexAt(TWO, 9000));
        assertEquals(-1, EditTimelineMath.indexAt(TWO, 9500));
        assertEquals(-1, EditTimelineMath.indexAt(Collections.<Range>emptyList(), 0));
    }

    @Test
    public void snapToKeptJumpsForwardOverDeletedParts() {
        assertEquals(2000, EditTimelineMath.snapToKept(TWO, 2000));
        assertEquals(5000, EditTimelineMath.snapToKept(TWO, 4000));
        assertEquals(1000, EditTimelineMath.snapToKept(TWO, 0));
        assertEquals(9000, EditTimelineMath.snapToKept(TWO, 9800));
    }

    @Test
    public void nextStartIsStrictlyAfter() {
        assertEquals(5000, EditTimelineMath.nextStart(TWO, 1000));
        assertEquals(1000, EditTimelineMath.nextStart(TWO, 0));
        assertEquals(-1, EditTimelineMath.nextStart(TWO, 5000));
    }

    private static final EditProject WHOLE = EditProject.wholeFile("/m.mp4");

    private static SectionSelection sel(long start, long end, long dur) {
        return SectionSelection.EMPTY.tap(start, dur).tap(end, dur);
    }

    @Test
    public void removeCutsTheMiddleAndJoinsTheRest() {
        // 10 s video, remove 3 s..5 s: 8 s left, joined at 3 s
        EditProject q = EditTimelineMath.removeSection(WHOLE, sel(3000, 5000, DUR), DUR);
        assertEquals(Arrays.asList(r(0, 3000), r(5000, DUR)), q.getKeep());
        assertEquals(8000, q.outputDurationMs(DUR));
    }

    @Test
    public void removeWorksAtTheVeryStartAndEnd() {
        EditProject head = EditTimelineMath.removeSection(WHOLE, sel(0, 2000, DUR), DUR);
        assertEquals(Collections.singletonList(r(2000, DUR)), head.getKeep());
        EditProject tail = EditTimelineMath.removeSection(WHOLE, sel(8000, DUR, DUR), DUR);
        assertEquals(Collections.singletonList(r(0, 8000)), tail.getKeep());
    }

    @Test
    public void removingEverythingIsRefused() {
        SectionSelection all = sel(0, DUR, DUR);
        assertTrue(EditTimelineMath.coversAll(WHOLE, all, DUR));
        assertFalse(EditTimelineMath.canRemove(WHOLE, all, DUR));
        assertNull(EditTimelineMath.removeSection(WHOLE, all, DUR));
        assertFalse(EditTimelineMath.canRemove(WHOLE, SectionSelection.EMPTY.tap(1000, DUR), DUR));
        assertFalse(EditTimelineMath.canRemove(WHOLE, SectionSelection.EMPTY, DUR));
    }

    @Test
    public void secondRemovalWorksOnTheEditedTimeline() {
        // After removing 3..5 s the timeline is 8 s: 0..3 s then source 5..10 s.
        EditProject once = EditTimelineMath.removeSection(WHOLE, sel(3000, 5000, DUR), DUR);
        // Edited 2 s..4 s is source 2..3 s plus 5..6 s
        EditProject twice = EditTimelineMath.removeSection(once, sel(2000, 4000, DUR), DUR);
        assertEquals(Arrays.asList(r(0, 2000), r(6000, DUR)), twice.getKeep());
        assertEquals(6000, twice.outputDurationMs(DUR));
        // Edited 4 s..6 s lies wholly after the join: source 6..8 s
        EditProject after = EditTimelineMath.removeSection(once, sel(4000, 6000, DUR), DUR);
        assertEquals(Arrays.asList(r(0, 3000), r(5000, 6000), r(8000, DUR)), after.getKeep());
    }

    @Test
    public void seamsAreOnTheEditedTimeline() {
        assertEquals(0, EditTimelineMath.seams(WHOLE, DUR).length);
        EditProject once = EditTimelineMath.removeSection(WHOLE, sel(3000, 5000, DUR), DUR);
        assertArrayEquals(new long[]{3000}, EditTimelineMath.seams(once, DUR));
        EditProject twice = EditTimelineMath.removeSection(once, sel(4000, 6000, DUR), DUR);
        assertArrayEquals(new long[]{3000, 4000}, EditTimelineMath.seams(twice, DUR));
        // A leftover split (touching ranges) is not a join
        assertEquals(0, EditTimelineMath.seams(WHOLE.withSplit(4000, DUR), DUR).length);
        // Cutting the tail leaves no join
        EditProject tail = EditTimelineMath.removeSection(WHOLE, sel(8000, DUR, DUR), DUR);
        assertEquals(0, EditTimelineMath.seams(tail, DUR).length);
    }

    @Test
    public void formatPreciseShowsTenths() {
        assertEquals("0:00.0", EditTimelineMath.formatPrecise(-5));
        assertEquals("0:03.2", EditTimelineMath.formatPrecise(3_240));
        assertEquals("1:05.0", EditTimelineMath.formatPrecise(64_960));
        assertEquals("1:01:01.5", EditTimelineMath.formatPrecise(3_661_500));
        assertEquals("2.0 s", EditTimelineMath.formatSeconds(2_000));
        assertEquals("0.1 s", EditTimelineMath.formatSeconds(100));
    }

    @Test
    public void outputNameAvoidsCollisions() throws Exception {
        File master = tmp.newFile("talk.mp4");
        File first = EditTimelineMath.nextOutputFile(master);
        assertEquals("talk_edit.mp4", first.getName());
        assertEquals(master.getParentFile(), first.getParentFile());
        assertTrue(first.createNewFile());
        assertEquals("talk_edit_2.mp4", EditTimelineMath.nextOutputFile(master).getName());
        assertTrue(new File(master.getParentFile(), "talk_edit_2.mp4").createNewFile());
        assertEquals("talk_edit_3.mp4", EditTimelineMath.nextOutputFile(master).getName());
    }

    @Test
    public void leftoverCropOfAnEditNameCountsAsTaken() throws Exception {
        File master = tmp.newFile("talk.mp4");
        assertTrue(new File(master.getParentFile(), "talk_edit_9x16.mp4").createNewFile());
        assertEquals("talk_edit_2.mp4", EditTimelineMath.nextOutputFile(master).getName());
    }

    @Test
    public void editingAnEditedFileKeepsCountingFromItsOwnName() throws Exception {
        File edited = tmp.newFile("talk_edit.mp4");
        assertEquals("talk_edit_edit.mp4", EditTimelineMath.nextOutputFile(edited).getName());
    }

    @Test
    public void sameFileSeesThroughRelativeSegments() throws Exception {
        File master = tmp.newFile("talk.mp4");
        File viaDots = new File(master.getParentFile(), "x/../talk.mp4");
        assertTrue(EditTimelineMath.isSameFile(master, viaDots));
        assertFalse(EditTimelineMath.isSameFile(master, EditTimelineMath.nextOutputFile(master)));
    }

    @Test
    public void formatTime() {
        assertEquals("0:00", EditTimelineMath.formatTime(-5));
        assertEquals("1:05", EditTimelineMath.formatTime(65_900));
        assertEquals("1:01:01", EditTimelineMath.formatTime(3_661_000));
    }
}
