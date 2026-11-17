package com.jovoc.facecampresentationrecorder.aiedit.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.edit.EditProject;
import com.jovoc.facecampresentationrecorder.edit.EditProject.Range;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class EventLogRemapTest {

    private static EventLogData.Activity brush(long s, long e) {
        return new EventLogData.Activity("x", "brush", s, e, 0.1, 0.2, 0.3, 0.4, 1.0);
    }

    private static EventLogData.Activity marker(String type, long t) {
        return new EventLogData.Activity("x", type, t, t, 0, 0, 1, 1, 1.0);
    }

    private static EventLogData.FacecamKey key(long t, double cx) {
        return new EventLogData.FacecamKey(t, cx, 0.5, 0.1);
    }

    private static EventLogData log(long duration, List<EventLogData.Activity> a, List<EventLogData.FacecamKey> f) {
        return new EventLogData("sid", 1080, 2400, duration, a, f);
    }

    @Test
    public void keepingEverythingReturnsTheSameLog() {
        EventLogData d = log(10000, Arrays.asList(brush(1000, 2000)), Arrays.asList(key(0, 0.5)));
        assertSame(d, EventLogRemap.apply(d, Collections.singletonList(new Range(0, 10000))));
        assertSame(d, EventLogRemap.apply(d, EditProject.wholeFile("/m.mp4")));
    }

    @Test
    public void timesShiftAndDurationIsWhatIsKept() {
        EventLogData d = log(10000,
                Arrays.asList(brush(6000, 7000), marker("slide_change", 8000)),
                Arrays.asList(key(0, 0.5)));
        EventLogData out = EventLogRemap.apply(d, Arrays.asList(new Range(0, 2000), new Range(5000, 10000)));
        assertEquals(7000, out.durationMs);
        assertEquals("sid", out.sessionId);
        assertEquals(1080, out.width);
        assertEquals(2400, out.height);
        assertEquals(2, out.activity.size());
        assertEquals("brush", out.activity.get(0).type);
        assertEquals(3000, out.activity.get(0).startMs);
        assertEquals(4000, out.activity.get(0).endMs);
        assertEquals(0.3, out.activity.get(0).w, 1e-9);   // the region does not change
        assertEquals("slide_change", out.activity.get(1).type);
        assertEquals(5000, out.activity.get(1).startMs);
        assertEquals(5000, out.activity.get(1).endMs);
        assertEquals("a1", out.activity.get(0).id);
        assertEquals("a2", out.activity.get(1).id);
    }

    @Test
    public void strokeAcrossACutIsClippedAndJoined() {
        EventLogData d = log(10000, Arrays.asList(brush(1500, 5500)), Collections.<EventLogData.FacecamKey>emptyList());
        EventLogData out = EventLogRemap.apply(d, Arrays.asList(new Range(0, 2000), new Range(5000, 10000)));
        assertEquals(1, out.activity.size());
        assertEquals(1500, out.activity.get(0).startMs);
        assertEquals(2500, out.activity.get(0).endMs);
    }

    @Test
    public void whatLiesInACutVanishesAndBoundaryTouchesDoNotCount() {
        List<EventLogData.Activity> in = new ArrayList<>();
        in.add(brush(2500, 4000));                  // inside the cut
        in.add(marker("board_show", 3000));         // inside the cut
        in.add(brush(4000, 5000));                  // ends where the kept part starts
        in.add(brush(2000, 3000));                  // starts where the kept part ends
        in.add(marker("board_hide", 5000));         // exactly at the start of the kept part: kept
        EventLogData out = EventLogRemap.apply(log(10000, in, Collections.<EventLogData.FacecamKey>emptyList()),
                Arrays.asList(new Range(0, 2000), new Range(5000, 10000)));
        assertEquals(1, out.activity.size());
        assertEquals("board_hide", out.activity.get(0).type);
        assertEquals(2000, out.activity.get(0).startMs);
    }

    @Test
    public void strokesBroughtCloserByACutAreMergedAgain() {
        EventLogData d = log(10000, Arrays.asList(brush(1800, 2000), brush(5100, 5300)),
                Collections.<EventLogData.FacecamKey>emptyList());
        EventLogData out = EventLogRemap.apply(d, Arrays.asList(new Range(0, 2000), new Range(5000, 10000)));
        assertEquals(1, out.activity.size());
        assertEquals(1800, out.activity.get(0).startMs);
        assertEquals(2300, out.activity.get(0).endMs);
    }

    @Test
    public void facecamHoldsItsPlaceAtTheStartOfEachKeptStretch() {
        EventLogData d = log(10000, Collections.<EventLogData.Activity>emptyList(),
                Arrays.asList(key(0, 0.2), key(3000, 0.5), key(7000, 0.8)));
        EventLogData out = EventLogRemap.apply(d, Arrays.asList(new Range(0, 2000), new Range(5000, 10000)));
        assertEquals(3, out.facecam.size());
        assertEquals(0, out.facecam.get(0).tMs);
        assertEquals(0.2, out.facecam.get(0).cx, 1e-9);
        assertEquals(2000, out.facecam.get(1).tMs);   // moved inside the cut: the place it ended in
        assertEquals(0.5, out.facecam.get(1).cx, 1e-9);
        assertEquals(4000, out.facecam.get(2).tMs);
        assertEquals(0.8, out.facecam.get(2).cx, 1e-9);
    }

    @Test
    public void facecamThatNeverMovedStaysOneKey() {
        EventLogData d = log(10000, Collections.<EventLogData.Activity>emptyList(), Arrays.asList(key(0, 0.5)));
        EventLogData out = EventLogRemap.apply(d, Arrays.asList(new Range(0, 2000), new Range(5000, 10000)));
        assertEquals(1, out.facecam.size());
    }

    @Test
    public void aTrimmedStartMakesTheFirstKeyZero() {
        EventLogData d = log(10000, Arrays.asList(brush(3500, 4000)), Arrays.asList(key(0, 0.4), key(1000, 0.6)));
        EventLogData out = EventLogRemap.apply(d, Collections.singletonList(new Range(3000, 5000)));
        assertEquals(2000, out.durationMs);
        assertEquals(1, out.facecam.size());
        assertEquals(0, out.facecam.get(0).tMs);
        assertEquals(0.6, out.facecam.get(0).cx, 1e-9);
        assertEquals(500, out.activity.get(0).startMs);
        assertEquals(1000, out.activity.get(0).endMs);
    }

    @Test
    public void projectCutsAreUsedThroughRenderRanges() {
        EventLogData d = log(10000, Arrays.asList(brush(6000, 7000)), Arrays.asList(key(0, 0.5)));
        EditProject p = new EditProject("/m.mp4").withDeleted(2000, 5000, 10000);
        EventLogData out = EventLogRemap.apply(d, p);
        assertEquals(7000, out.durationMs);
        assertEquals(3000, out.activity.get(0).startMs);
    }

    @Test
    public void aSplitThatCutsNothingChangesNothing() {
        EventLogData d = log(10000, Arrays.asList(brush(4900, 5100)), Collections.<EventLogData.FacecamKey>emptyList());
        EditProject p = new EditProject("/m.mp4").withSplit(5000, 10000);
        EventLogData out = EventLogRemap.apply(d, p);
        assertEquals(10000, out.durationMs);
        assertEquals(1, out.activity.size());
        assertEquals(4900, out.activity.get(0).startMs);
        assertEquals(5100, out.activity.get(0).endMs);
        assertTrue(out.facecam.isEmpty());
    }
}
