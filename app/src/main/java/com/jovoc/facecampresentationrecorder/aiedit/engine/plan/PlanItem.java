package com.jovoc.facecampresentationrecorder.aiedit.engine.plan;

import java.util.Map;

/** One effect on the OUTPUT timeline, in integer milliseconds. {@code params} keep the registry's key names. */
public final class PlanItem {
    public final String id;
    public final String effect;
    public final String layer;
    public final String target;
    public final long startMs;
    public final long endMs;
    /** The transcript word the item hangs on, or -1 when it has no anchor. */
    public final int anchorWordIndex;
    public final long anchorOffsetMs;
    /** One of the contract's zones, or null for effects without a position. */
    public final String zone;
    /** Null for effects without a component (camera, time, look, transition). */
    public final PlanComponent component;
    /** Numbers (Long or Double), booleans and strings. */
    public final Map<String, Object> params;
    /** Id of a sound (an entry of the plan's assets), or null for no sound. */
    public final String soundId;
    public final long soundOffsetMs;
    /** The planner's short reason; for debugging only, never logged. */
    public final String reason;

    public PlanItem(String id, String effect, String layer, String target, long startMs, long endMs,
                    int anchorWordIndex, long anchorOffsetMs, String zone, PlanComponent component,
                    Map<String, Object> params, String soundId, long soundOffsetMs, String reason) {
        this.id = id;
        this.effect = effect;
        this.layer = layer;
        this.target = target;
        this.startMs = startMs;
        this.endMs = endMs;
        this.anchorWordIndex = anchorWordIndex;
        this.anchorOffsetMs = anchorOffsetMs;
        this.zone = zone;
        this.component = component;
        this.params = params;
        this.soundId = soundId;
        this.soundOffsetMs = soundOffsetMs;
        this.reason = reason;
    }

    public long durationMs() {
        return endMs - startMs;
    }

    /** Whether the item is on screen at {@code outputMs} ({@code startMs <= t < endMs}). */
    public boolean activeAt(long outputMs) {
        return outputMs >= startMs && outputMs < endMs;
    }

    /** A numeric param, or {@code fallback} when it is missing or not a number. */
    public double paramNumber(String key, double fallback) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    /** A string param, or {@code fallback}. */
    public String paramString(String key, String fallback) {
        Object v = params.get(key);
        return v instanceof String ? (String) v : fallback;
    }
}
