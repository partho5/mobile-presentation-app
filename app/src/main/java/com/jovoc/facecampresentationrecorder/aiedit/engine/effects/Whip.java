package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.RegistryEntry;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

/**
 * {@code tr_whip}: a whip pan over the cut point (the middle of the item). The picture swings out sideways in the
 * direction of {@code direction} ({@code left} or {@code right}) with a horizontal motion blur, and swings in from
 * the other side after the cut. A small zoom keeps the slid picture covering the frame.
 */
public final class Whip implements VideoEffect {

    public static final String ID = "tr_whip";

    /** The picture slide at the cut, as a fraction of the width, and the zoom added at the cut. */
    static final double MAX_SHIFT = 0.07;
    static final double MAX_EXTRA_ZOOM = 0.18;

    final long startMs;
    final long durationMs;
    final double blur;
    final double sign;
    final String ease;

    public Whip(PlanItem item, RegistryEntry entry, EngineContract.SpeedRules speed) {
        startMs = item.startMs;
        durationMs = Params.clamp(item.durationMs(), speed.transitionMs[0], speed.transitionMs[1]);
        blur = Params.number(item, entry, "blur", 0.02);
        sign = "right".equals(item.paramString("direction", "left")) ? -1 : 1;
        ease = Params.ease(item, entry, "out_cubic");
    }

    /** 0 at the ends of the transition, 1 on the cut. */
    double strengthAt(long outputMs) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return 0;
        return Math.sin(Math.PI * Ease.progress(outputMs, startMs, durationMs));
    }

    /** The signed slide at {@code outputMs}: away from rest before the cut, in from the far side after it. */
    double shiftAt(long outputMs) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return 0;
        double p = Ease.progress(outputMs, startMs, durationMs);
        if (p < 0.5) return -sign * MAX_SHIFT * Ease.byName(ease, p * 2);
        return sign * MAX_SHIFT * (1 - Ease.byName(ease, (p - 0.5) * 2));
    }

    @Override
    public boolean apply(long outputMs, VideoTransform transform) {
        if (outputMs < startMs || outputMs >= startMs + durationMs) return false;
        double strength = strengthAt(outputMs);
        transform.zoom(1 + MAX_EXTRA_ZOOM * strength, 0.5, 0.5);
        transform.shiftX += shiftAt(outputMs);
        transform.blur = Math.max(transform.blur, blur * strength);
        return true;
    }
}
