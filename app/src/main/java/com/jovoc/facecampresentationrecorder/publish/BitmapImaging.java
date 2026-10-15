package com.jovoc.facecampresentationrecorder.publish;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** {@link PosterMaker.Imaging} on the framework: MediaMetadataRetriever, Bitmap, jpeg compress. */
public final class BitmapImaging implements PosterMaker.Imaging<Bitmap> {

    private BitmapImaging() {
    }

    /** The poster source the app uses: a frame of the uploaded file as base64, null on any failure. */
    public static PublishPipeline.PosterSource source() {
        return new PosterMaker<>(new BitmapImaging())::base64For;
    }

    @Override
    public long durationMs(File video) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(video.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (RuntimeException e) {
            return 0;
        } finally {
            close(r);
        }
    }

    @Override
    public Bitmap grab(File video, long timeUs) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(video.getAbsolutePath());
            // Applies the video's rotation, so a portrait crop stays portrait.
            return r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } finally {
            close(r);
        }
    }

    private static void close(MediaMetadataRetriever r) {
        try {
            r.release();
        } catch (IOException ignored) {
            // Nothing to do: the poster does not depend on it.
        }
    }

    @Override
    public int width(Bitmap image) {
        return image.getWidth();
    }

    @Override
    public int height(Bitmap image) {
        return image.getHeight();
    }

    @Override
    public Bitmap scale(Bitmap image, int width, int height) {
        return Bitmap.createScaledBitmap(image, width, height, true);
    }

    @Override
    public byte[] jpeg(Bitmap image, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        return image.compress(Bitmap.CompressFormat.JPEG, quality, out) ? out.toByteArray() : null;
    }

    @Override
    public void release(Bitmap image) {
        image.recycle();
    }
}
