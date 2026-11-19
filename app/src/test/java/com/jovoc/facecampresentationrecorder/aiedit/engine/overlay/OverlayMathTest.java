package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanComponent;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Asset file names, the clip fit, and the icon scale pop: pure maths, no Android classes. */
public class OverlayMathTest {

    private EngineContract contract;

    @Before
    public void setUp() throws Exception {
        contract = TestContract.load();
    }

    private IconAnimator animator(long start, long end, Map<String, Object> params) {
        PlanItem item = new PlanItem("i1", "i_scale_pop", "icon", "overlay", start, end, -1, 0, "upper_right",
                PlanComponent.asset(PlanComponent.ICON_IMAGE, "a1", 0, null, 1.0), params, null, 0, null);
        return new IconAnimator(item, contract.effect("i_scale_pop"), contract.getSpeed());
    }

    @Test
    public void assetNamesAreOneSafeFileName() {
        assertEquals("abc-123_x.json", AssetFiles.safeName("abc-123_x.json"));
        String evil = AssetFiles.safeName("../../etc/passwd");
        assertFalse(evil.contains("/"));
        assertFalse(evil.contains("\\"));
        assertNotEquals("..", AssetFiles.safeName(".."));
        assertNotEquals(AssetFiles.safeName("a/b"), AssetFiles.safeName("a_b"));
        assertFalse(AssetFiles.isFramesName(AssetFiles.safeName("x.frames")));
        assertFalse(AssetFiles.safeName("x.frames.part").endsWith(".part"));
        File dir = new File("assets");
        assertEquals(dir, AssetFiles.fileFor(dir, "x/y").getParentFile());
        assertNull(AssetFiles.existing(dir, "no-such-asset"));
        assertNull(AssetFiles.existing(null, "x"));
    }

    @Test
    public void keyedFramesLiveInOneFolderNextToTheClip() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("assets").toFile();
        File frames = AssetFiles.framesDir(dir, "x/y");
        assertEquals(dir, frames.getParentFile());
        assertEquals(AssetFiles.safeName("x/y") + ".frames", frames.getName());
        assertTrue(AssetFiles.isFramesName(frames.getName()));
        assertNull(AssetFiles.existingFrames(dir, "x/y"));
        assertNull(AssetFiles.existingFrames(null, "x/y"));
        assertTrue(frames.mkdirs());
        assertNull(AssetFiles.existingFrames(dir, "x/y"));
        assertTrue(new File(frames, "0001.png").createNewFile());
        assertEquals(frames, AssetFiles.existingFrames(dir, "x/y"));
        assertTrue(AssetFiles.deleteTree(dir));
        assertFalse(dir.exists());
    }

    @Test
    public void clipFactorIsOneUnlessSpedUpAndCapped() {
        assertEquals(1.0, ClipFit.factor("native", 2.0, 3.0), 1e-9);
        assertEquals(1.0, ClipFit.factor(null, 2.0, 3.0), 1e-9);
        assertEquals(2.0, ClipFit.factor("speed_up", 2.0, 3.0), 1e-9);
        assertEquals(3.0, ClipFit.factor("speed_up", 5.0, 3.0), 1e-9);
        assertEquals(1.0, ClipFit.factor("speed_up", 0.5, 3.0), 1e-9);
    }

    @Test
    public void clipProgressFollowsTimeTimesFactorAndHoldsTheLastFrame() {
        assertEquals(0.0, ClipFit.progress(0, 1000, 1), 1e-9);
        assertEquals(0.5, ClipFit.progress(500, 1000, 1), 1e-9);
        // Sped up 2x: half the time reaches the end
        assertEquals(1.0, ClipFit.progress(500, 1000, 2), 1e-9);
        // A longer slot holds the last frame, never slows down
        assertEquals(1.0, ClipFit.progress(5000, 1000, 1), 1e-9);
        // Never slower than native even for a factor below 1
        assertEquals(0.5, ClipFit.progress(500, 1000, 0.25), 1e-9);
        assertEquals(0.0, ClipFit.progress(500, 0, 1), 1e-9);
    }

    @Test
    public void clipFrameFollowsProgressAndHoldsTheLastFrame() {
        // 24 frames at 24 fps = a 1 s clip
        assertEquals(0, ClipFit.frameIndex(0, 24, 24, 1));
        assertEquals(12, ClipFit.frameIndex(500, 24, 24, 1));
        assertEquals(23, ClipFit.frameIndex(5000, 24, 24, 1));
        // Sped up 2x: half the time reaches the last frame
        assertEquals(23, ClipFit.frameIndex(500, 24, 24, 2));
        assertEquals(0, ClipFit.frameIndex(500, 0, 24, 1));
    }

    @Test
    public void scalePopOvershootsSettlesAndShrinksOut() {
        IconAnimator a = animator(1000, 2500, new HashMap<String, Object>());
        IconAnimator.State s = new IconAnimator.State();
        assertTrue(a.at(1000, s));
        assertEquals(0f, s.scale, 0.01f);
        assertEquals(0f, s.alpha, 0.01f);
        assertTrue(a.at(1000 + a.entryMs / 2, s));
        assertTrue(s.scale > 0.5f);
        assertTrue(a.at(1000 + a.entryMs + 50, s));
        assertEquals(1f, s.scale, 1e-6f);
        assertEquals(1f, s.alpha, 1e-6f);
        assertTrue(a.at(2499, s));
        assertTrue(s.scale < 1f && s.scale >= IconAnimator.EXIT_SCALE - 0.01f);
        assertTrue(s.alpha < 0.2f);
    }

    @Test
    public void notShownOutsideTheItem() {
        IconAnimator a = animator(1000, 2000, new HashMap<String, Object>());
        IconAnimator.State s = new IconAnimator.State();
        assertFalse(a.at(999, s));
        assertFalse(a.at(2000, s));
        assertTrue(a.at(1999, s));
    }

    @Test
    public void entryAndExitStayInsideTheSpeedRulesAndTheItem() {
        Map<String, Object> slow = new HashMap<>();
        slow.put("entry_ms", 5000);
        slow.put("exit_ms", 5000);
        IconAnimator a = animator(0, 600, slow);
        assertTrue(a.entryMs <= contract.getSpeed().entryMs[1]);
        assertTrue(a.exitMs <= contract.getSpeed().exitMs[1]);
        assertTrue(a.entryMs + a.exitMs <= 600);
    }
}
