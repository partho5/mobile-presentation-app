package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

/**
 * How the video picture is moved in one output frame: a zoom of {@code scale} (1 or more, so the frame stays full)
 * about a centre given as fractions of the output (x across, y down from the top). Reused every frame.
 */
public final class VideoTransform {

    public double scale = 1;
    public double centerX = 0.5;
    public double centerY = 0.5;

    /** Back to the plain picture. */
    public void reset() {
        scale = 1;
        centerX = 0.5;
        centerY = 0.5;
    }

    /** Adds a zoom on top of what is already set; the latest centre wins. Never below 1. */
    public void zoom(double factor, double cx, double cy) {
        scale = Math.max(1, scale * factor);
        centerX = Ease.clamp01(cx);
        centerY = Ease.clamp01(cy);
    }

    public boolean isIdentity() {
        return scale == 1;
    }
}
