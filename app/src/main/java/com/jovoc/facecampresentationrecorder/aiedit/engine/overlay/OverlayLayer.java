package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import android.graphics.Canvas;

/** A picture an overlay item draws on the Canvas layer: an icon image or one Lottie, both fitted into a box. */
interface OverlayLayer {

    /** Width over height of the picture. */
    float aspect();

    /**
     * Draws the picture centred on the canvas origin, fitted inside {@code boxW} × {@code boxH} pixels.
     *
     * @param elapsedMs time since the item started (a Lottie plays from it; an icon ignores it)
     * @param alpha     0..255
     */
    void draw(Canvas canvas, float boxW, float boxH, long elapsedMs, int alpha);

    /** Frees the picture; the layer is not used again. */
    void release();
}
