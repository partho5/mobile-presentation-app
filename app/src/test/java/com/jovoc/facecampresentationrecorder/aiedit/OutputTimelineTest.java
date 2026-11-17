package com.jovoc.facecampresentationrecorder.aiedit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.CropRect;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OutputTimelineTest {

    private static final List<Plan.Cut> NO_CUTS = Collections.emptyList();
    private static final List<Plan.SpeedSegment> NO_SPEED = Collections.emptyList();
    private static final List<Plan.FacecamKey> NO_FACE = Collections.emptyList();

    private static Plan.Cut cut(long s, long e) {
        return new Plan.Cut(s, e, "silence");
    }

    private static Plan.SpeedSegment speed(long s, long e, double rate) {
        return new Plan.SpeedSegment(s, e, rate);
    }

    @Test
    public void noCutsNoSpeedIsTheIdentity() {
        OutputTimeline t = new OutputTimeline(10000, NO_CUTS, NO_SPEED, NO_FACE);
        assertEquals(10000, t.outputDurationMs());
        assertEquals(0, t.sourceToOutput(0));
        assertEquals(4321, t.sourceToOutput(4321));
        assertEquals(4321, t.outputToSource(4321));
        assertEquals(1.0, t.rateAtOutput(5000), 0.0);
        assertTrue(t.isKept(0));
        assertTrue(t.isKept(9999));
    }

    @Test
    public void aCutShiftsEverythingAfterIt() {
        OutputTimeline t = new OutputTimeline(10000, Arrays.asList(cut(2000, 3000)), NO_SPEED, NO_FACE);
        assertEquals(9000, t.outputDurationMs());
        assertEquals(1999, t.sourceToOutput(1999));
        assertEquals(2000, t.sourceToOutput(3000));
        assertEquals(3000, t.sourceToOutput(4000));
        assertEquals(9000, t.sourceToOutput(10000));
        assertTrue(t.isKept(1999));
        assertFalse(t.isKept(2000));
        assertFalse(t.isKept(2999));
        assertTrue(t.isKept(3000));
        // A source time inside the cut lands on the cut point.
        assertEquals(2000, t.sourceToOutput(2500));
        // The other way: output 2000 is the first surviving millisecond after the cut.
        assertEquals(1999, t.outputToSource(1999));
        assertEquals(3000, t.outputToSource(2000));
        assertEquals(10000, t.outputToSource(9000));
    }

    @Test
    public void overlappingAndOutOfRangeCutsAreMerged() {
        OutputTimeline t = new OutputTimeline(10000,
                Arrays.asList(cut(1000, 2000), cut(1500, 3000), cut(9500, 12000)), NO_SPEED, NO_FACE);
        // 2000 ms and 500 ms removed in total.
        assertEquals(10000 - 2000 - 500, t.outputDurationMs());
        assertEquals(1000, t.sourceToOutput(3000));
        assertEquals(7500, t.sourceToOutput(10000));
    }

    @Test
    public void cutsAtTheEdgesWork() {
        OutputTimeline t = new OutputTimeline(10000, Arrays.asList(cut(0, 1000), cut(9000, 10000)),
                NO_SPEED, NO_FACE);
        assertEquals(8000, t.outputDurationMs());
        assertEquals(0, t.sourceToOutput(0));
        assertEquals(0, t.sourceToOutput(1000));
        assertEquals(1000, t.sourceToOutput(2000));
        assertEquals(8000, t.sourceToOutput(9500));
        assertEquals(1000, t.outputToSource(0));
        assertEquals(9000, t.outputToSource(8000));
        assertEquals(9000, t.outputToSource(50000));
        assertFalse(t.isKept(500));
    }

    @Test
    public void everythingCutLeavesAnEmptyOutput() {
        OutputTimeline t = new OutputTimeline(5000, Arrays.asList(cut(0, 5000)), NO_SPEED, NO_FACE);
        assertEquals(0, t.outputDurationMs());
        assertEquals(0, t.sourceToOutput(2500));
        assertEquals(0, t.outputToSource(100));
        assertFalse(t.isKept(0));
    }

    @Test
    public void aSpeedSegmentShortensItsStretch() {
        OutputTimeline t = new OutputTimeline(10000, NO_CUTS, Arrays.asList(speed(5000, 7000, 2.0)), NO_FACE);
        assertEquals(9000, t.outputDurationMs());
        assertEquals(5000, t.sourceToOutput(5000));
        assertEquals(5500, t.sourceToOutput(6000));
        assertEquals(6000, t.sourceToOutput(7000));
        assertEquals(9000, t.sourceToOutput(10000));
        assertEquals(6000, t.outputToSource(5500));
        assertEquals(7000, t.outputToSource(6000));
        assertEquals(2.0, t.rateAtOutput(5200), 0.0);
        assertEquals(1.0, t.rateAtOutput(4999), 0.0);
        assertEquals(1.0, t.rateAtOutput(6000), 0.0);
    }

    @Test
    public void aSlowSegmentLengthensItsStretch() {
        OutputTimeline t = new OutputTimeline(4000, NO_CUTS, Arrays.asList(speed(1000, 2000, 0.5)), NO_FACE);
        assertEquals(5000, t.outputDurationMs());
        assertEquals(3000, t.sourceToOutput(2000));
        assertEquals(1500, t.outputToSource(2000));
    }

    @Test
    public void cutsAndSpeedTogether() {
        // 0..2000 normal, 2000..3000 cut, 3000..5000 normal, 5000..7000 at 2x, 7000..10000 normal.
        OutputTimeline t = new OutputTimeline(10000, Arrays.asList(cut(2000, 3000)),
                Arrays.asList(speed(5000, 7000, 2.0)), NO_FACE);
        assertEquals(8000, t.outputDurationMs());
        assertEquals(2000, t.sourceToOutput(3000));
        assertEquals(4000, t.sourceToOutput(5000));
        assertEquals(4500, t.sourceToOutput(6000));
        assertEquals(5000, t.sourceToOutput(7000));
        assertEquals(8000, t.sourceToOutput(10000));
        assertEquals(3000, t.outputToSource(2000));
        assertEquals(6000, t.outputToSource(4500));
        assertEquals(7000, t.outputToSource(5000));
    }

    @Test
    public void aSpeedSegmentInsideACutIsIgnoredAndOneOverlappingItIsTrimmed() {
        OutputTimeline t = new OutputTimeline(10000, Arrays.asList(cut(4000, 6000)),
                Arrays.asList(speed(4500, 5500, 2.0), speed(5000, 8000, 2.0)), NO_FACE);
        // 4000..6000 removed; 6000..8000 at 2x takes 1000; the rest is normal: 4000 + 1000 + 2000.
        assertEquals(7000, t.outputDurationMs());
        assertEquals(4000, t.sourceToOutput(6000));
        assertEquals(5000, t.sourceToOutput(8000));
    }

    @Test
    public void roundTripThroughTheMapsIsStable() {
        OutputTimeline t = new OutputTimeline(20000, Arrays.asList(cut(1000, 1400), cut(8000, 9000)),
                Arrays.asList(speed(3000, 4000, 1.5), speed(12000, 14000, 2.0)), NO_FACE);
        long last = -1;
        for (long src = 0; src < 20000; src += 50) {
            if (!t.isKept(src)) continue;
            long out = t.sourceToOutput(src);
            assertTrue("output never goes backwards", out >= last);
            last = out;
            assertTrue("round trip stays within 2 ms", Math.abs(t.outputToSource(out) - src) <= 2);
        }
    }

    @Test
    public void facecamIsAStepFunctionOnTheOutputTimeline() {
        List<Plan.FacecamKey> track = Arrays.asList(
                new Plan.FacecamKey(0, 0.78, 0.80, 0.13),
                new Plan.FacecamKey(5000, 0.20, 0.30, 0.10));
        OutputTimeline t = new OutputTimeline(10000, NO_CUTS, NO_SPEED, track);
        assertEquals(0.78, t.facecamAt(0).cx, 0.0);
        assertEquals(0.78, t.facecamAt(4999).cx, 0.0);
        assertEquals(0.20, t.facecamAt(5000).cx, 0.0);
        assertEquals(0.30, t.facecamAt(99999).cy, 0.0);
        // Before the first key the first key applies.
        OutputTimeline late = new OutputTimeline(10000, NO_CUTS, NO_SPEED,
                Arrays.asList(new Plan.FacecamKey(2000, 0.5, 0.5, 0.1)));
        assertEquals(0.5, late.facecamAt(0).cx, 0.0);
        assertNull(new OutputTimeline(10000, NO_CUTS, NO_SPEED, NO_FACE).facecamAt(0));
    }

    @Test
    public void cropsOfA1080By2400Master() {
        assertEquals(new CropRect(0, 0, 1080, 1920), OutputTimeline.cropFor("9:16", 1080, 2400));
        assertEquals(new CropRect(0, 0, 1080, 1350), OutputTimeline.cropFor("4:5", 1080, 2400));
        assertEquals(new CropRect(0, 0, 1080, 1080), OutputTimeline.cropFor("1:1", 1080, 2400));
        CropRect nine = OutputTimeline.cropFor("9:16", 1080, 2400);
        assertEquals(0.8, nine.fractionH(2400), 1e-9);
        assertEquals(1.0, nine.fractionW(1080), 0.0);
        assertEquals(0.0, nine.fractionX(1080), 0.0);
        assertEquals(0.0, nine.fractionY(2400), 0.0);
    }

    @Test
    public void cropHeightsAreEvenAndNeverPastTheMaster() {
        // 1081 wide: 9:16 would be 1921.7 rows, rounded down to an even 1920.
        assertEquals(1920, OutputTimeline.cropFor("9:16", 1081, 2400).h);
        // A master shorter than the ratio needs gets the full height, centred, even sizes.
        CropRect wide = OutputTimeline.cropFor("9:16", 1920, 1080);
        assertEquals(new CropRect(656, 0, 606, 1080), wide);
        // A master exactly as tall as needed.
        assertEquals(new CropRect(0, 0, 1080, 1920), OutputTimeline.cropFor("9:16", 1080, 1920));
    }

    @Test
    public void badRatiosAreRefused() {
        for (String bad : new String[]{null, "", "916", "9:", ":16", "a:b", "0:5", "9:-16"}) {
            try {
                OutputTimeline.cropFor(bad, 1080, 2400);
                fail("must be refused: " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }
}
