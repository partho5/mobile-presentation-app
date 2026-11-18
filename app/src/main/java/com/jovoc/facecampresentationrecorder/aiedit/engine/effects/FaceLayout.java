package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * The shared part of the two layout effects: the frame shrinks to {@code frame_scale_to} (over a dimmed copy of
 * itself) while a round copy of the facecam, taken from the track, grows from its own size to
 * {@code face_scale_to} times it. Only made for an output whose crop holds the whole facecam circle.
 */
abstract class FaceLayout implements VideoEffect {

    final long startMs;
    final long endMs;
    final long inMs;
    final long outMs;
    final double faceScaleTo;
    final double frameScaleTo;
    final String ease;
    private final FacecamSource source;
    private final double[] circle = new double[4];

    FaceLayout(PlanItem item, RegistryEntry entry, FacecamSource source) {
        this.source = source;
        startMs = item.startMs;
        endMs = item.endMs;
        long span = endMs - startMs;
        inMs = Params.clamp(Math.round(Params.number(item, entry, "in_ms", 200)), 0, span);
        outMs = Params.clamp(Math.round(Params.number(item, entry, "out_ms", 150)), 0, span - inMs);
        faceScaleTo = Math.max(1, Params.number(item, entry, "face_scale_to", 1.5));
        frameScaleTo = Math.min(1, Params.number(item, entry, "frame_scale_to", 0.92));
        ease = Params.ease(item, entry, "out_cubic");
    }

    /** True when the whole facecam circle is inside the output for the whole item (start, middle and end). */
    static boolean fits(PlanItem item, FacecamSource source) {
        if (source == null) return false;
        long mid = item.startMs + item.durationMs() / 2;
        return source.visibleAt(item.startMs) && source.visibleAt(mid) && source.visibleAt(item.endMs - 1);
    }

    /** How far the layout is from the plain frame (0) to fully changed (1) at {@code outputMs}. */
    abstract double mixAt(long outputMs);

    /** The rise from the plain frame during {@code in_ms}. */
    double enter(long outputMs) {
        return Ease.byName(ease, Ease.progress(outputMs, startMs, inMs));
    }

    @Override
    public boolean apply(long outputMs, VideoTransform transform) {
        if (outputMs < startMs || outputMs >= endMs) return false;
        if (!source.circleAt(outputMs, circle)) return false;
        double mix = mixAt(outputMs);
        double frame = Ease.lerp(1, frameScaleTo, mix);
        double grow = Ease.lerp(1, faceScaleTo, mix);
        // Where the facecam sits once the frame is shrunk about the centre, and how big its copy is
        double x = 0.5 + (circle[0] - 0.5) * frame;
        double y = 0.5 + (circle[1] - 0.5) * frame;
        double rw = circle[2] * frame * grow;
        double rh = circle[3] * frame * grow;
        // The grown copy stays inside the output
        x = rw >= 0.5 ? 0.5 : Math.max(rw, Math.min(1 - rw, x));
        y = rh >= 0.5 ? 0.5 : Math.max(rh, Math.min(1 - rh, y));
        transform.faceLayout(frame, circle[0], circle[1], circle[2], x, y, rw, rh);
        return true;
    }
}
