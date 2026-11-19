package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One keyed mp4 clip (a folder of transparent PNG frames at {@link AssetFiles#FRAME_FPS}) drawn into the overlay
 * layer. The clip is never played: for each output frame the frame is picked from the item's time, {@code time x fit
 * factor} (see {@link ClipFit}), so the render is exact and the same on every run. Longer than the slot it is sped
 * up, shorter it holds the last frame. Only a few decoded frames are kept. A folder with no readable frame gives no
 * layer, so the item is left out.
 */
final class FrameSequenceLayer implements OverlayLayer {

    /** How many decoded frames stay in memory; the clip moves forward, so a few are enough. */
    private static final int KEPT_FRAMES = 4;

    private final File[] frames;
    private final double factor;
    private final float aspect;
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<Integer, Bitmap> kept = new LinkedHashMap<Integer, Bitmap>(KEPT_FRAMES + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<Integer, Bitmap> eldest) {
            if (size() <= KEPT_FRAMES) return false;
            eldest.getValue().recycle();
            return true;
        }
    };

    private FrameSequenceLayer(File[] frames, float aspect, double factor) {
        this.frames = frames;
        this.aspect = aspect;
        this.factor = factor;
    }

    /**
     * @param factor the speed-up from {@link ClipFit#factor}
     * @return the layer, or null when the folder has no frame that decodes
     */
    static FrameSequenceLayer load(File framesDir, double factor) {
        File[] files = framesDir != null ? framesDir.listFiles((d, n) -> n.endsWith(".png")) : null;
        if (files == null || files.length == 0) return null;
        Arrays.sort(files);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(files[0].getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            return new FrameSequenceLayer(files, (float) bounds.outWidth / bounds.outHeight, factor);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public float aspect() {
        return aspect;
    }

    @Override
    public void draw(Canvas canvas, float boxW, float boxH, long elapsedMs, int alpha) {
        Bitmap bmp = frame(ClipFit.frameIndex(elapsedMs, frames.length, AssetFiles.FRAME_FPS, factor));
        if (bmp == null) return;
        float fit = Math.min(boxW / bmp.getWidth(), boxH / bmp.getHeight());
        float w = bmp.getWidth() * fit;
        float h = bmp.getHeight() * fit;
        src.set(0, 0, bmp.getWidth(), bmp.getHeight());
        dst.set(-w / 2, -h / 2, w / 2, h / 2);
        paint.setAlpha(alpha);
        canvas.drawBitmap(bmp, src, dst, paint);
    }

    /** The decoded frame at {@code index}, from the kept ones or read now; null when it cannot be read. */
    private Bitmap frame(int index) {
        Bitmap bmp = kept.get(index);
        if (bmp != null) return bmp;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = BitmapFactory.decodeFile(frames[index].getAbsolutePath(), opts);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
        if (bmp != null) kept.put(index, bmp);
        return bmp;
    }

    @Override
    public void release() {
        for (Bitmap bmp : kept.values()) bmp.recycle();
        kept.clear();
    }
}
