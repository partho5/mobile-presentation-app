package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SectionSelectionTest {

    private static final long DUR = 10_000;

    @Test
    public void firstTapSetsTheStartOnly() {
        SectionSelection s = SectionSelection.EMPTY.tap(3000, DUR);
        assertTrue(s.hasStart());
        assertFalse(s.hasRange());
        assertEquals(3000, s.getStartMs());
        assertEquals(-1, s.getEndMs());
        assertEquals(0, s.lengthMs());
    }

    @Test
    public void secondTapSetsTheEnd() {
        SectionSelection s = SectionSelection.EMPTY.tap(3000, DUR).tap(5000, DUR);
        assertTrue(s.hasRange());
        assertEquals(3000, s.getStartMs());
        assertEquals(5000, s.getEndMs());
        assertEquals(2000, s.lengthMs());
    }

    @Test
    public void anEndBeforeTheStartSwaps() {
        SectionSelection s = SectionSelection.EMPTY.tap(5000, DUR).tap(3000, DUR);
        assertEquals(3000, s.getStartMs());
        assertEquals(5000, s.getEndMs());
    }

    @Test
    public void laterTapsMoveTheNearerMarker() {
        SectionSelection s = SectionSelection.EMPTY.tap(3000, DUR).tap(7000, DUR);
        SectionSelection nearStart = s.tap(2000, DUR);
        assertEquals(2000, nearStart.getStartMs());
        assertEquals(7000, nearStart.getEndMs());
        SectionSelection nearEnd = s.tap(8000, DUR);
        assertEquals(3000, nearEnd.getStartMs());
        assertEquals(8000, nearEnd.getEndMs());
        // Between the markers, closer to the end
        SectionSelection inside = s.tap(6000, DUR);
        assertEquals(3000, inside.getStartMs());
        assertEquals(6000, inside.getEndMs());
    }

    @Test
    public void movingAMarkerPastTheOtherKeepsThemInOrder() {
        SectionSelection s = SectionSelection.EMPTY.tap(3000, DUR).tap(4000, DUR);
        // Far past the end: the end marker is the nearer one and moves
        SectionSelection moved = s.tap(9000, DUR);
        assertEquals(3000, moved.getStartMs());
        assertEquals(9000, moved.getEndMs());
    }

    @Test
    public void tooShortSelectionIsStretchedToTheMinimum() {
        SectionSelection fwd = SectionSelection.EMPTY.tap(3000, DUR).tap(3010, DUR);
        assertEquals(3000, fwd.getStartMs());
        assertEquals(3000 + SectionSelection.MIN_LENGTH_MS, fwd.getEndMs());
        SectionSelection back = SectionSelection.EMPTY.tap(3000, DUR).tap(2990, DUR);
        assertEquals(3000 - SectionSelection.MIN_LENGTH_MS, back.getStartMs());
        assertEquals(3000, back.getEndMs());
    }

    @Test
    public void minimumLengthIsKeptAtTheEdges() {
        SectionSelection end = SectionSelection.EMPTY.tap(DUR, DUR).tap(DUR, DUR);
        assertEquals(DUR - SectionSelection.MIN_LENGTH_MS, end.getStartMs());
        assertEquals(DUR, end.getEndMs());
        SectionSelection start = SectionSelection.EMPTY.tap(0, DUR).tap(-5, DUR);
        assertEquals(0, start.getStartMs());
        assertEquals(SectionSelection.MIN_LENGTH_MS, start.getEndMs());
    }

    @Test
    public void tapsAreClampedToTheTimeline() {
        SectionSelection s = SectionSelection.EMPTY.tap(-500, DUR).tap(99_000, DUR);
        assertEquals(0, s.getStartMs());
        assertEquals(DUR, s.getEndMs());
        assertTrue(s.fitsIn(DUR));
        assertFalse(s.fitsIn(DUR - 1));
    }

    @Test
    public void emptyTimelineIgnoresTaps() {
        assertTrue(SectionSelection.EMPTY.tap(100, 0).isEmpty());
    }

    @Test
    public void draggingAMarkerMovesOnlyThatMarker() {
        SectionSelection s = SectionSelection.EMPTY.tap(3000, DUR).tap(6000, DUR);
        SectionSelection a = s.moveMarker(true, 1000, DUR);
        assertEquals(1000, a.getStartMs());
        assertEquals(6000, a.getEndMs());
        SectionSelection b = s.moveMarker(false, 9000, DUR);
        assertEquals(3000, b.getStartMs());
        assertEquals(9000, b.getEndMs());
    }

    @Test
    public void aDraggedMarkerStopsAtTheOtherOneAndNeverSwaps() {
        SectionSelection s = SectionSelection.EMPTY.tap(3000, DUR).tap(6000, DUR);
        SectionSelection a = s.moveMarker(true, 8000, DUR);
        assertEquals(6000 - SectionSelection.MIN_LENGTH_MS, a.getStartMs());
        assertEquals(6000, a.getEndMs());
        SectionSelection b = s.moveMarker(false, 0, DUR);
        assertEquals(3000, b.getStartMs());
        assertEquals(3000 + SectionSelection.MIN_LENGTH_MS, b.getEndMs());
    }

    @Test
    public void aDraggedMarkerStaysInsideTheTimeline() {
        SectionSelection s = SectionSelection.EMPTY.tap(3000, DUR).tap(6000, DUR);
        assertEquals(0, s.moveMarker(true, -500, DUR).getStartMs());
        assertEquals(DUR, s.moveMarker(false, DUR + 500, DUR).getEndMs());
    }

    @Test
    public void aLoneStartCanBeDraggedAnywhereButThereIsNoEndToDrag() {
        SectionSelection s = SectionSelection.EMPTY.tap(3000, DUR);
        assertEquals(9000, s.moveMarker(true, 9000, DUR).getStartMs());
        assertEquals(s, s.moveMarker(false, 9000, DUR));
        assertEquals(SectionSelection.EMPTY, SectionSelection.EMPTY.moveMarker(true, 1000, DUR));
    }

    @Test
    public void setStartThenSetEndMakesARange() {
        SectionSelection s = SectionSelection.EMPTY.setStart(3000, DUR).setEnd(5000, DUR);
        assertEquals(3000, s.getStartMs());
        assertEquals(5000, s.getEndMs());
    }

    @Test
    public void setEndWithoutStartChangesNothing() {
        assertEquals(SectionSelection.EMPTY, SectionSelection.EMPTY.setEnd(5000, DUR));
    }

    @Test
    public void setEndAtOrBeforeStartChangesNothing() {
        SectionSelection s = SectionSelection.EMPTY.setStart(5000, DUR);
        assertEquals(s, s.setEnd(5000, DUR));
        assertEquals(s, s.setEnd(2000, DUR));
        assertEquals(s, s.setEnd(5000 + SectionSelection.MIN_LENGTH_MS - 1, DUR));
        assertEquals(5000 + SectionSelection.MIN_LENGTH_MS,
                s.setEnd(5000 + SectionSelection.MIN_LENGTH_MS, DUR).getEndMs());
    }

    @Test
    public void setStartBeforeTheEndOnlyMovesTheStart() {
        SectionSelection s = SectionSelection.EMPTY.setStart(3000, DUR).setEnd(6000, DUR).setStart(4000, DUR);
        assertEquals(4000, s.getStartMs());
        assertEquals(6000, s.getEndMs());
    }

    @Test
    public void setStartAtOrPastTheEndStartsOver() {
        SectionSelection s = SectionSelection.EMPTY.setStart(3000, DUR).setEnd(6000, DUR).setStart(8000, DUR);
        assertEquals(8000, s.getStartMs());
        assertFalse(s.hasRange());
        SectionSelection near = SectionSelection.EMPTY.setStart(3000, DUR).setEnd(6000, DUR)
                .setStart(6000 - SectionSelection.MIN_LENGTH_MS + 1, DUR);
        assertFalse(near.hasRange());
    }

    @Test
    public void setStartAndEndStayInsideTheTimeline() {
        SectionSelection s = SectionSelection.EMPTY.setStart(-50, DUR).setEnd(DUR + 500, DUR);
        assertEquals(0, s.getStartMs());
        assertEquals(DUR, s.getEndMs());
        assertEquals(SectionSelection.EMPTY, SectionSelection.EMPTY.setStart(5, 0));
    }
}
