package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void splitNeedsRoomOnBothSides() {
        assertTrue(EditTimelineMath.canSplit(TWO, 2000));
        assertFalse(EditTimelineMath.canSplit(TWO, 1100));
        assertFalse(EditTimelineMath.canSplit(TWO, 2900));
        assertFalse(EditTimelineMath.canSplit(TWO, 4000));
    }

    @Test
    public void deleteIsOffWhenItWouldRemoveEverything() {
        assertFalse(EditTimelineMath.canDelete(Collections.singletonList(r(0, DUR)), 0));
        assertTrue(EditTimelineMath.canDelete(TWO, 1));
        assertFalse(EditTimelineMath.canDelete(TWO, -1));
    }

    @Test
    public void resizeStartCannotPassPreviousSegment() {
        List<Range> out = EditTimelineMath.resize(TWO, 1, 2000, 9000, DUR);
        assertEquals(r(3000, 9000), out.get(1));
        assertEquals(r(1000, 3000), out.get(0));
    }

    @Test
    public void resizeEndCannotPassNextSegmentOrFile() {
        List<Range> out = EditTimelineMath.resize(TWO, 0, 1000, 8000, DUR);
        assertEquals(r(1000, 5000), out.get(0));
        List<Range> last = EditTimelineMath.resize(TWO, 1, 5000, 99_000, DUR);
        assertEquals(r(5000, DUR), last.get(1));
    }

    @Test
    public void resizeKeepsMinimumLength() {
        List<Range> startDrag = EditTimelineMath.resize(TWO, 1, 8950, 9000, DUR);
        assertEquals(r(9000 - EditTimelineMath.MIN_SEGMENT_MS, 9000), startDrag.get(1));
        List<Range> endDrag = EditTimelineMath.resize(TWO, 1, 5000, 5050, DUR);
        assertEquals(r(5000, 5000 + EditTimelineMath.MIN_SEGMENT_MS), endDrag.get(1));
    }

    @Test
    public void resizeAllowsGrowingIntoDeletedStretch() {
        List<Range> out = EditTimelineMath.resize(TWO, 0, 0, 3000, DUR);
        assertEquals(r(0, 3000), out.get(0));
    }

    @Test
    public void withRangesKeepsAudioAndMaster() {
        EditProject p = EditProject.wholeFile("/m.mp4")
                .withOriginal(new EditProject.AudioSettings(0.5f, false, false));
        EditProject q = EditTimelineMath.withRanges(p, TWO, DUR);
        assertEquals("/m.mp4", q.getMasterPath());
        assertEquals(0.5f, q.getOriginal().volume, 0f);
        assertEquals(TWO, q.getKeep());
    }

    @Test
    public void withRangesCoveringTheWholeFileMeansNoCuts() {
        EditProject p = EditProject.wholeFile("/m.mp4");
        EditProject q = EditTimelineMath.withRanges(p, Collections.singletonList(r(0, DUR)), DUR);
        assertEquals(p, q);
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
    public void formatTime() {
        assertEquals("0:00", EditTimelineMath.formatTime(-5));
        assertEquals("1:05", EditTimelineMath.formatTime(65_900));
        assertEquals("1:01:01", EditTimelineMath.formatTime(3_661_000));
    }
}
