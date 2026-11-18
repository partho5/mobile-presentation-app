package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

/** A plan item that moves the video picture (camera movement). Pure maths; the compositor applies the result. */
public interface VideoEffect {

    /**
     * Adds this effect's part of the frame at {@code outputMs} to {@code transform}.
     *
     * @return true when the effect is active at that time
     */
    boolean apply(long outputMs, VideoTransform transform);
}
