package com.jovoc.facecampresentationrecorder.aiedit.engine.overlay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;

/**
 * A still icon (PNG, WebP or JPEG file) drawn into the overlay layer. Decoded once, no larger than the box it is
 * drawn in, and kept until the item ends. A file that does not decode gives no layer, so the item is left out.
 */
final class IconLayer implements OverlayLayer {

    private final Bitmap bitmap;
    private final Rect src;
    private final RectF dst = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private IconLayer(Bitmap bitmap) {
        this.bitmap = bitmap;
        this.src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
    }

    /** The icon in {@code file}, decoded to about {@code maxSidePx} on its longer side; null when it cannot be read. */
    static IconLayer load(File file, int maxSidePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            // Halve while the picture stays at least as large as the box
            while (longest / (sample * 2) >= Math.max(1, maxSidePx)) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            return bmp != null ? new IconLayer(bmp) : null;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    @Override
    public float aspect() {
        return (float) bitmap.getWidth() / bitmap.getHeight();
    }

    @Override
    public void draw(Canvas canvas, float boxW, float boxH, long elapsedMs, int alpha) {
        float fit = Math.min(boxW / bitmap.getWidth(), boxH / bitmap.getHeight());
        float w = bitmap.getWidth() * fit;
        float h = bitmap.getHeight() * fit;
        dst.set(-w / 2, -h / 2, w / 2, h / 2);
        paint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, src, dst, paint);
    }

    @Override
    public void release() {
        bitmap.recycle();
    }
}
