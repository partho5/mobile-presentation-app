package com.jovoc.facecampresentationrecorder.aiedit.engine.plan;

/** A file the phone downloads once; a component's {@code asset_id} and a sound's {@code id} name an entry. */
public final class PlanAsset {
    public final String id;
    /** {@code lottie}, {@code icon} or {@code sound}. */
    public final String kind;
    public final String url;
    /** 0 when the server did not say. */
    public final int nativeDurationMs;
    public final int width;
    public final int height;

    public PlanAsset(String id, String kind, String url, int nativeDurationMs, int width, int height) {
        this.id = id;
        this.kind = kind;
        this.url = url;
        this.nativeDurationMs = nativeDurationMs;
        this.width = width;
        this.height = height;
    }
}
