package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code y_face_grow_slide_shrink}: the facecam grows and the slide area shrinks behind it within {@code in_ms},
 * holds, and goes back to the plain frame within {@code out_ms} at the end of the item.
 */
public final class FaceGrowSlideShrink extends FaceLayout {

    public static final String ID = "y_face_grow_slide_shrink";

    public FaceGrowSlideShrink(PlanItem item, RegistryEntry entry, FacecamSource source) {
        super(item, entry, source);
    }

    @Override
    double mixAt(long outputMs) {
        double back = 1 - Ease.outCubic(Ease.progress(outputMs, endMs - outMs, outMs));
        return Math.min(enter(outputMs), back);
    }
}
