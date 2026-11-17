package com.jovoc.facecampresentationrecorder.aiedit.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class EventLogBuilderTest {

    private static final long T0 = 100_000;

    private static EventLogBuilder builder() {
        return new EventLogBuilder(T0, 1000, 2000);
    }

    /** A stroke from (x0,y0) to (x1,y1) between two moments after the recording start. */
    private static void stroke(EventLogBuilder b, long fromMs, long toMs, float x0, float y0, float x1, float y1) {
        b.strokeStart(T0 + fromMs, x0, y0);
        b.strokeMove(x1, y1);
        b.strokeEnd(T0 + toMs);
    }

    @Test
    public void strokeBecomesOneBrushEntryWithBoxAndMargin() {
        EventLogBuilder b = builder();
        stroke(b, 1000, 1500, 100, 200, 300, 400);
        EventLogData d = b.finish(T0 + 5000);
        assertEquals(1, d.activity.size());
        EventLogData.Activity a = d.activity.get(0);
        assertEquals("a1", a.id);
        assertEquals("brush", a.type);
        assertEquals(1000, a.startMs);
        assertEquals(1500, a.endMs);
        assertEquals(0.076, a.x, 1e-9);
        assertEquals(0.088, a.y, 1e-9);
        assertEquals(0.248, a.w, 1e-9);
        assertEquals(0.124, a.h, 1e-9);
        assertEquals(5000, d.durationMs);
    }

    @Test
    public void strokesCloserThan400msAreOneSpanAndFartherAreNot() {
        EventLogBuilder b = builder();
        stroke(b, 1000, 1500, 100, 200, 300, 400);
        stroke(b, 1800, 2000, 500, 600, 700, 800);   // gap 300: merged
        stroke(b, 2400, 2600, 10, 10, 20, 20);       // gap 400 exactly: not merged
        EventLogData d = b.finish(T0 + 5000);
        assertEquals(2, d.activity.size());
        EventLogData.Activity first = d.activity.get(0);
        assertEquals(1000, first.startMs);
        assertEquals(2000, first.endMs);
        assertEquals(0.076, first.x, 1e-9);                    // union of both boxes
        assertEquals((724 - 76) / 1000.0, first.w, 1e-9);
        assertEquals("a2", d.activity.get(1).id);
        assertEquals(2400, d.activity.get(1).startMs);
    }

    @Test
    public void strokeNeverEndedIsClosedByFinishOrByTheNextStart() {
        EventLogBuilder b = builder();
        b.strokeStart(T0 + 1000, 10, 10);
        EventLogData d = b.finish(T0 + 4000);
        assertEquals(1, d.activity.size());
        assertEquals(1000, d.activity.get(0).startMs);
        assertEquals(4000, d.activity.get(0).endMs);

        EventLogBuilder c = builder();
        c.strokeStart(T0 + 1000, 10, 10);
        c.strokeStart(T0 + 3000, 50, 50);   // closes the first at 3000, then is closed by finish
        EventLogData e = c.finish(T0 + 3200);
        assertEquals(1, e.activity.size());   // touching, so merged
        assertEquals(1000, e.activity.get(0).startMs);
        assertEquals(3200, e.activity.get(0).endMs);
    }

    @Test
    public void moveAndEndWithoutAStartAreIgnored() {
        EventLogBuilder b = builder();
        b.strokeMove(5, 5);
        b.strokeEnd(T0 + 10);
        assertTrue(b.finish(T0 + 100).activity.isEmpty());
    }

    @Test
    public void pulseLastsThreeHundredMsAndIsClampedToTheDuration() {
        EventLogBuilder b = builder();
        b.pulse(T0 + 1000, 0, 0, 1000, 500);
        b.pulse(T0 + 4900, 0, 0, 1000, 500);
        EventLogData d = b.finish(T0 + 5000);
        assertEquals(2, d.activity.size());
        assertEquals(1000, d.activity.get(0).startMs);
        assertEquals(1300, d.activity.get(0).endMs);
        assertEquals(0.25, d.activity.get(0).h, 1e-9);
        assertEquals(5000, d.activity.get(1).endMs);
    }

    @Test
    public void markersAreKeptInOrderAndBoardStateRepeatsAreIgnored() {
        EventLogBuilder b = builder();
        b.slideChanged(T0 + 500);
        b.boardShown(T0 + 1000, false);   // already hidden: nothing
        b.boardShown(T0 + 1500, true);
        b.boardShown(T0 + 1600, true);    // repeat: nothing
        b.boardShown(T0 + 2500, false);
        EventLogData d = b.finish(T0 + 3000);
        List<EventLogData.Activity> a = d.activity;
        assertEquals(3, a.size());
        assertEquals("slide_change", a.get(0).type);
        assertEquals("board_show", a.get(1).type);
        assertEquals("board_hide", a.get(2).type);
        assertEquals(1500, a.get(1).startMs);
        assertEquals(a.get(1).startMs, a.get(1).endMs);
        assertEquals("a3", a.get(2).id);
    }

    @Test
    public void markerBetweenStrokesDoesNotStopTheirMerge() {
        EventLogBuilder b = builder();
        stroke(b, 1000, 1500, 100, 100, 200, 200);
        b.slideChanged(T0 + 1600);
        stroke(b, 1700, 1900, 100, 100, 200, 200);
        EventLogData d = b.finish(T0 + 3000);
        assertEquals(2, d.activity.size());
        assertEquals("brush", d.activity.get(0).type);
        assertEquals(900, d.activity.get(0).endMs - d.activity.get(0).startMs);
        assertEquals("slide_change", d.activity.get(1).type);
    }

    @Test
    public void firstFacecamKeyIsAtZeroAndPlaceIsInFractions() {
        EventLogBuilder b = builder();
        b.facecamBox(T0 + 33, true, 700, 1200, 200);   // centre (800,1300), radius 100
        EventLogData d = b.finish(T0 + 5000);
        assertEquals(1, d.facecam.size());
        EventLogData.FacecamKey k = d.facecam.get(0);
        assertEquals(0, k.tMs);
        assertEquals(0.8, k.cx, 1e-9);
        assertEquals(0.65, k.cy, 1e-9);
        assertEquals(0.1, k.r, 1e-9);
    }

    @Test
    public void facecamHiddenAndUnmovedAddNothingAndADragKeepsOneKeyPer100ms() {
        EventLogBuilder b = builder();
        b.facecamBox(T0 + 10, true, 100, 100, 200);
        b.facecamBox(T0 + 43, false, 0, 0, 0);          // hidden
        b.facecamBox(T0 + 50, true, 100, 100, 200);     // not moved
        b.facecamBox(T0 + 1000, true, 110, 100, 200);   // drag starts
        b.facecamBox(T0 + 1033, true, 140, 100, 200);
        b.facecamBox(T0 + 1066, true, 180, 100, 200);
        b.facecamBox(T0 + 1099, true, 220, 100, 200);
        b.facecamBox(T0 + 1300, true, 300, 100, 200);   // rested
        EventLogData d = b.finish(T0 + 5000);
        assertEquals(3, d.facecam.size());
        assertEquals(0, d.facecam.get(0).tMs);
        assertEquals(1000, d.facecam.get(1).tMs);
        assertEquals((220 + 100) / 1000.0, d.facecam.get(1).cx, 1e-9);   // the newest place won
        assertEquals(1300, d.facecam.get(2).tMs);
        assertEquals(0.4, d.facecam.get(2).cx, 1e-9);
    }

    @Test
    public void eventsBeforeStartCountAsZeroAndKeysAfterTheEndAreDropped() {
        EventLogBuilder b = builder();
        b.slideChanged(T0 - 50);
        b.facecam(T0 + 9000, 0.5, 0.5, 0.1);
        EventLogData d = b.finish(T0 + 2000);
        assertEquals(0, d.activity.get(0).startMs);
        assertTrue(d.facecam.isEmpty());
    }

    @Test
    public void everyRecordingGetsItsOwnSessionId() {
        assertTrue(!builder().sessionId().equals(builder().sessionId()));
        assertEquals(36, builder().sessionId().length());
    }
}
