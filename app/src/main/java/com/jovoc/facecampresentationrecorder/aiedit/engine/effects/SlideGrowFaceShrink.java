package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code y_slide_grow_face_shrink}: the facecam leads for a moment (big facecam, shrunk slide area, reached within
 * {@code in_ms}), then the slide area grows back and the facecam shrinks to its own size across the rest of the
 * item, so the viewer's eye is handed from the speaker to the slide.
 */
public final class SlideGrowFaceShrink extends FaceLayout {

    public static final String ID = "y_slide_grow_face_shrink";

    public SlideGrowFaceShrink(PlanItem item, RegistryEntry entry, FacecamSource source) {
        super(item, entry, source);
    }

    @Override
    double mixAt(long outputMs) {
        long handOver = startMs + inMs;
        double settle = 1 - Ease.outCubic(Ease.progress(outputMs, handOver, endMs - handOver));
        return Math.min(enter(outputMs), settle);
    }
}
