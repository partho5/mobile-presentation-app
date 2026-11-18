package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import java.util.ArrayList;
import java.util.List;

/** Turns the camera-movement items of a plan into {@link VideoEffect}s; ids this build does not draw are skipped. */
public final class EffectFactory {

    private EffectFactory() {}

    /** The video effects of {@code plan}, in start order (the plan's own order). */
    public static List<VideoEffect> videoEffects(Plan plan, EngineContract contract) {
        List<VideoEffect> out = new ArrayList<>();
        for (PlanItem item : plan.items) {
            VideoEffect effect = create(item, contract);
            if (effect != null) out.add(effect);
        }
        return out;
    }

    /** The effect of one item, or null when the item is not a video effect this build renders. */
    public static VideoEffect create(PlanItem item, EngineContract contract) {
        if (PunchIn.ID.equals(item.effect)) {
            return new PunchIn(item, contract.effect(item.effect), contract.getSpeed());
        }
        return null;
    }
}
