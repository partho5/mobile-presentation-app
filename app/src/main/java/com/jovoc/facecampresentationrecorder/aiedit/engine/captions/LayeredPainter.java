package com.jovoc.facecampresentationrecorder.aiedit.engine.captions;

import android.graphics.Canvas;

/** Draws several painters on one canvas, first to last (so the last one is on top). Null entries are skipped. */
public final class LayeredPainter implements CaptionPainter {

    private final CaptionPainter[] painters;

    public LayeredPainter(CaptionPainter... painters) {
        this.painters = painters;
    }

    @Override
    public boolean draw(Canvas canvas, long outputMs, int outputW, int outputH) {
        boolean drew = false;
        for (CaptionPainter p : painters) {
            if (p != null && p.draw(canvas, outputMs, outputW, outputH)) drew = true;
        }
        return drew;
    }
}
