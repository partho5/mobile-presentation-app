package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.jovoc.facecampresentationrecorder.aiedit.engine.Engine;
import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderJob;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.CropRect;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.OutputTimeline;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The renderer's pure maths: crop texture coordinates, proxy size, AAC rates, timeline pieces, ratio order. */
public class RenderMathTest {

    private static final float EPS = 1e-6f;

    @Test
    public void wholeFrameWhenNoCrop() {
        assertArrayEquals(new float[]{0, 0, 1, 0, 0, 1, 1, 1}, Compositor.cropTexCoords(null, 1080, 2400), EPS);
    }

    @Test
    public void nineSixteenCropKeepsTheTopRows() {
        CropRect crop = OutputTimeline.cropFor("9:16", 1080, 2400);
        float[] t = Compositor.cropTexCoords(crop, 1080, 2400);
        // Full width; the top edge stays at v = 1, the bottom moves up to 1 - 1920/2400 = 0.2
        assertArrayEquals(new float[]{0, 0.2f, 1, 0.2f, 0, 1, 1, 1}, t, EPS);
    }

    @Test
    public void squareCropOfALandscapeIsCentred() {
        CropRect crop = OutputTimeline.cropFor("1:1", 1920, 1080);
        float[] t = Compositor.cropTexCoords(crop, 1920, 1080);
        assertEquals(420f / 1920f, t[0], EPS);
        assertEquals(1500f / 1920f, t[2], EPS);
        assertEquals(0f, t[1], EPS);
        assertEquals(1f, t[5], EPS);
    }

    @Test
    public void proxyShortSideIs360() {
        assertArrayEquals(new int[]{360, 800}, Engine.proxySize(1080, 2400));
        assertArrayEquals(new int[]{640, 360}, Engine.proxySize(1920, 1080));
        // Never upscaled
        assertArrayEquals(new int[]{320, 240}, Engine.proxySize(320, 240));
    }

    @Test
    public void aacRatesAreStandard() {
        assertEquals(48_000, AudioPath.aacRate(48_000));
        assertEquals(16_000, AudioPath.aacRate(16_000));
        assertEquals(48_000, AudioPath.aacRate(96_000));
        assertEquals(44_100, AudioPath.aacRate(37_800));
    }

    @Test
    public void piecesFollowCutsAndSpeed() {
        List<Plan.Cut> cuts = Collections.singletonList(new Plan.Cut(2000, 3000, "silence"));
        List<Plan.SpeedSegment> speed = Collections.singletonList(new Plan.SpeedSegment(5000, 7000, 2.0));
        OutputTimeline t = new OutputTimeline(10000, cuts, speed, Collections.<Plan.FacecamKey>emptyList());
        assertEquals(4, t.pieceCount());
        assertEquals(0, t.pieceSourceStartMs(0));
        assertEquals(2000, t.pieceSourceEndMs(0));
        assertEquals(3000, t.pieceSourceStartMs(1));
        assertEquals(2000.0, t.pieceOutputStartMs(1), 0.0);
        assertEquals(2.0, t.pieceRate(2), 0.0);
        assertEquals(4000.0, t.pieceOutputStartMs(2), 0.0);
        assertEquals(5000.0, t.pieceOutputStartMs(3), 0.0);
        assertEquals(8000.0, t.exactOutputDurationMs(), 0.0);
    }

    @Test
    public void nineSixteenRendersFirst() {
        Plan plan = new Plan(1, 1, "s", "basic", 1000, new ArrayList<Plan.Cut>(),
                new ArrayList<Plan.SpeedSegment>(), new ArrayList<Plan.FacecamKey>(), null,
                new ArrayList<>(), null, new ArrayList<String>(), new ArrayList<>());
        File src = new File("src.mp4");
        List<RenderJob> jobs = Arrays.asList(
                new RenderJob(src, plan, "1:1", new File("a.mp4"), null, null, null),
                new RenderJob(src, plan, "4:5", new File("b.mp4"), null, null, null),
                new RenderJob(src, plan, "9:16", new File("c.mp4"), null, null, null));
        List<RenderJob> ordered = Engine.inRenderOrder(jobs);
        assertEquals("9:16", ordered.get(0).ratio);
        assertEquals("4:5", ordered.get(1).ratio);
        assertEquals("1:1", ordered.get(2).ratio);
    }
}
