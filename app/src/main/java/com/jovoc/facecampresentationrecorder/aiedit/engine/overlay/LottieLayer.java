package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import android.graphics.Canvas;
import android.graphics.Rect;

import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieCompositionFactory;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * One Lottie (a JSON animation file) drawn into the overlay layer. The animation is never played: for each output
 * frame its progress is set from the item's time, {@code time x fit factor} (see {@link LottieFit}), so the render
 * is exact and the same on every run. Longer than the slot it is sped up, shorter it holds the last frame. A file
 * that cannot be read as a Lottie gives no layer, so the item is left out.
 */
final class LottieLayer implements OverlayLayer {

    private final LottieDrawable drawable = new LottieDrawable();
    private final Rect bounds = new Rect();
    private final double nativeMs;
    private final double factor;
    private final float aspect;
    private int boundsW = -1;
    private int boundsH = -1;

    private LottieLayer(LottieComposition composition, double factor) {
        drawable.setComposition(composition);
        this.nativeMs = composition.getDuration();
        this.factor = factor;
        Rect b = composition.getBounds();
        this.aspect = b.height() > 0 ? (float) b.width() / b.height() : 1f;
    }

    /**
     * @param factor the speed-up from {@link LottieFit#factor}
     * @return the layer, or null when the file is not a readable Lottie JSON or has no length
     */
    static LottieLayer load(File file, double factor) {
        try (InputStream in = new FileInputStream(file)) {
            // The same stream is read by the parser and closed by the try block; no shared cache key
            LottieResult<LottieComposition> result = LottieCompositionFactory.fromJsonInputStreamSync(in, null);
            LottieComposition composition = result.getValue();
            if (composition == null || composition.getDuration() <= 0 || composition.getBounds().isEmpty()) return null;
            return new LottieLayer(composition, factor);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public float aspect() {
        return aspect;
    }

    @Override
    public void draw(Canvas canvas, float boxW, float boxH, long elapsedMs, int alpha) {
        // The picture's size inside the box, aspect kept; the drawable scales itself to its bounds
        boolean widthLimits = aspect * boxH > boxW;
        int iw = Math.max(1, Math.round(widthLimits ? boxW : aspect * boxH));
        int ih = Math.max(1, Math.round(widthLimits ? boxW / aspect : boxH));
        if (iw != boundsW || ih != boundsH) {
            boundsW = iw;
            boundsH = ih;
            bounds.set(-iw / 2, -ih / 2, iw - iw / 2, ih - ih / 2);
            drawable.setBounds(bounds);
        }
        drawable.setProgress((float) LottieFit.progress(elapsedMs, nativeMs, factor));
        drawable.setAlpha(alpha);
        drawable.draw(canvas);
    }

    @Override
    public void release() {
        drawable.clearComposition();
    }
}
