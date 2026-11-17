package com.jovoc.facecampresentationrecorder.aiedit.engine.captions;

import android.graphics.Canvas;

/**
 * Draws the captions of one output frame. The engine owns the canvas (cleared to transparent, output size) and
 * blends it over the video; the painter only draws. Called on the render thread once per frame, so it must not
 * allocate per call.
 */
public interface CaptionPainter {

    /**
     * Draws the captions shown at {@code outputMs} (output timeline) into {@code canvas}.
     *
     * @return true when something was drawn; false lets the engine skip the upload of an empty layer
     */
    boolean draw(Canvas canvas, long outputMs, int outputW, int outputH);
}
