package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** The first-wave video effects besides the punch-in: camera moves, looks, layouts and cut transitions. */
public class VideoEffectsTest {

    private EngineContract contract;

    /** A facecam well inside the output. */
    private final FacecamSource inside = (ms, out) -> {
        out[0] = 0.8;
        out[1] = 0.3;
        out[2] = 0.1;
        out[3] = 0.056;
        return true;
    };

    /** A facecam the crop cut off at the bottom edge. */
    private final FacecamSource cutOff = (ms, out) -> {
        out[0] = 0.8;
        out[1] = 0.99;
        out[2] = 0.1;
        out[3] = 0.056;
        return true;
    };

    @Before
    public void setUp() throws Exception {
        contract = TestContract.load();
    }

    private PlanItem item(String effect, long start, long end) {
        return item(effect, start, end, new HashMap<String, Object>());
    }

    private PlanItem item(String effect, long start, long end, Map<String, Object> params) {
        return new PlanItem("e1", effect, "video", "full_frame", start, end, -1, 0, null, null, params, null, 0, null);
    }

    @Test
    public void pushInRisesThenEasesBackToTheFullFrame() {
        PushIn p = new PushIn(item("v_push_in", 1000, 1600), contract.effect("v_push_in"));
        assertEquals(1.0, p.scaleAt(999), 1e-9);
        assertEquals(p.scaleTo, p.scaleAt(1000 + Math.round(600 * PushIn.IN_SHARE)), 1e-3);
        assertTrue(p.scaleAt(1590) < p.scaleTo);
        assertEquals(1.0, p.scaleAt(1600), 1e-9);
        assertTrue(p.scaleTo >= 1.06 && p.scaleTo <= 1.10);
    }

    @Test
    public void altCropHoldsTheCloseCropBetweenTheSwitches() {
        AltCrop a = new AltCrop(item("v_alt_crop", 0, 2000), contract.effect("v_alt_crop"));
        assertEquals(1.0, a.scaleAt(0), 1e-9);
        assertEquals(a.scaleTo, a.scaleAt(1000), 1e-9);
        assertEquals(1.0, a.scaleAt(2000), 1e-9);
        VideoTransform t = new VideoTransform();
        assertTrue(a.apply(1000, t));
        assertEquals(0.4, t.centerY, 1e-9);
    }

    @Test
    public void flashPeaksEarlyAndFadesInsideTheItem() {
        Flash f = new Flash(item("l_flash", 500, 620), contract.effect("l_flash"), contract.getSpeed());
        assertEquals(0.0, f.alphaAt(499), 1e-9);
        assertEquals(f.peakAlpha, f.alphaAt(500 + Math.round(f.durationMs * Flash.ATTACK_SHARE)), 1e-2);
        assertTrue(f.alphaAt(600) < f.peakAlpha);
        assertEquals(0.0, f.alphaAt(500 + f.durationMs), 1e-9);
        VideoTransform t = new VideoTransform();
        assertTrue(f.apply(520, t));
        assertTrue(t.flash > 0);
        assertFalse(f.apply(2000, t));
    }

    @Test
    public void glitchSplitFlipsDirectionAndStaysInsideTheItem() {
        RgbGlitch g = new RgbGlitch(item("l_rgb_glitch", 0, 200), contract.effect("l_rgb_glitch"));
        assertTrue(g.offsetAt(10) > 0);
        assertTrue(g.offsetAt(10 + RgbGlitch.FLIP_MS) < 0);
        assertEquals(0.0, g.offsetAt(200), 1e-9);
        assertTrue(Math.abs(g.offsetAt(10)) <= 0.02);
    }

    @Test
    public void flashCutPeaksOnTheCutPoint() {
        FlashCut f = new FlashCut(item("tr_flash_cut", 1000, 1200), contract.effect("tr_flash_cut"),
                contract.getSpeed());
        assertEquals(0.0, f.alphaAt(1000), 1e-9);
        assertEquals(f.peakAlpha, f.alphaAt(1000 + f.durationMs / 2), 1e-6);
        assertEquals(0.0, f.alphaAt(1000 + f.durationMs), 1e-9);
    }

    @Test
    public void whipSlidesOutBeforeTheCutAndInAfterIt() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("direction", "left");
        Whip w = new Whip(item("tr_whip", 1000, 1200, params), contract.effect("tr_whip"), contract.getSpeed());
        assertTrue(w.shiftAt(1090) < 0);
        assertTrue(w.shiftAt(1110) > 0);
        assertEquals(0.0, w.shiftAt(1000), 1e-9);
        assertEquals(1.0, w.strengthAt(1100), 1e-6);
        VideoTransform t = new VideoTransform();
        assertTrue(w.apply(1100, t));
        assertTrue(t.scale > 1);
        assertTrue(t.blur > 0);

        params.put("direction", "right");
        Whip r = new Whip(item("tr_whip", 1000, 1200, params), contract.effect("tr_whip"), contract.getSpeed());
        assertTrue(r.shiftAt(1090) > 0);
    }

    @Test
    public void layoutEffectsShrinkTheFrameAndGrowTheFacecamCopy() {
        PlanItem item = item("y_face_grow_slide_shrink", 1000, 2000);
        VideoEffect e = EffectFactory.create(item, contract, inside);
        assertTrue(e instanceof FaceGrowSlideShrink);
        VideoTransform t = new VideoTransform();
        assertTrue(e.apply(1500, t));
        assertTrue(t.faceCopy);
        assertEquals(0.92, t.frameScale, 1e-6);
        assertEquals(0.1 * 0.92 * 1.5, t.faceRW, 1e-6);
        assertEquals(0.8, t.faceSrcX, 1e-9);
        // Back to the plain frame at the very start
        VideoTransform start = new VideoTransform();
        assertTrue(e.apply(1000, start));
        assertEquals(1.0, start.frameScale, 1e-9);
    }

    @Test
    public void slideGrowHandsTheEyeBackToTheSlide() {
        VideoEffect e = EffectFactory.create(item("y_slide_grow_face_shrink", 0, 1000), contract, inside);
        assertTrue(e instanceof SlideGrowFaceShrink);
        VideoTransform early = new VideoTransform();
        VideoTransform late = new VideoTransform();
        e.apply(250, early);
        e.apply(990, late);
        assertTrue(early.frameScale < late.frameScale);
        assertTrue(early.faceRW > late.faceRW);
    }

    @Test
    public void facecamCopyStaysInsideTheOutput() {
        FacecamSource corner = (ms, out) -> {
            out[0] = 0.95;
            out[1] = 0.05;
            out[2] = 0.04;
            out[3] = 0.04;
            return true;
        };
        VideoEffect e = EffectFactory.create(item("y_face_grow_slide_shrink", 0, 1000), contract, corner);
        VideoTransform t = new VideoTransform();
        assertTrue(e.apply(500, t));
        assertTrue(t.faceX + t.faceRW <= 1.0 + 1e-9);
        assertTrue(t.faceY - t.faceRH >= -1e-9);
    }

    @Test
    public void layoutEffectsAreSkippedWhenTheCropCutsTheFacecamOff() {
        assertNull(EffectFactory.create(item("y_face_grow_slide_shrink", 0, 1000), contract, cutOff));
        assertNull(EffectFactory.create(item("y_slide_grow_face_shrink", 0, 1000), contract, cutOff));
        assertNull(EffectFactory.create(item("y_face_grow_slide_shrink", 0, 1000), contract, null));
        assertNull(EffectFactory.create(item("y_face_grow_slide_shrink", 0, 1000), contract));
    }

    @Test
    public void factoryMakesEveryOtherFirstWaveVideoEffectAndSkipsTheRest() {
        for (String id : new String[]{"v_punch_in", "v_push_in", "v_alt_crop", "l_flash", "l_rgb_glitch",
                "tr_flash_cut", "tr_whip"}) {
            assertNotNull(id, EffectFactory.create(item(id, 0, 400), contract, inside));
        }
        assertNull(EffectFactory.create(item("v_shake", 0, 400), contract, inside));
        assertNull(EffectFactory.create(item("c_active_pop", 0, 400), contract, inside));
    }

    @Test
    public void transformResetClearsEveryEffect() {
        VideoTransform t = new VideoTransform();
        t.zoom(1.2, 0.3, 0.3);
        t.flash(0.5);
        t.glitch = 0.01;
        t.shiftX = 0.05;
        t.blur = 0.02;
        t.faceLayout(0.92, 0.8, 0.3, 0.1, 0.8, 0.3, 0.1, 0.05);
        assertFalse(t.isIdentity());
        t.reset();
        assertTrue(t.isIdentity());
    }
}
