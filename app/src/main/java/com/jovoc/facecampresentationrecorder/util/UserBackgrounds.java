package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Backgrounds the user added from their own photos. Each one is copied (downscaled) into app-private
 * storage as a JPEG, so no storage permission is needed and the original can be moved or deleted.
 * The directory itself is the persisted list; a selected user background is saved in the
 * background preference as the file's absolute path (built-in ones are "bg_samples/..." asset paths).
 */
public final class UserBackgrounds {

    private UserBackgrounds() {}

    /** At most this many user uploads; built-in backgrounds don't count. */
    public static final int MAX_UPLOADS = 5;

    /** Longest edge kept on import. The background only fills the face-cam circle, so this is plenty. */
    public static final int MAX_DIMENSION = 1920;

    private static final String DIR_NAME = "user_backgrounds";
    private static final String PREFIX = "bg_";
    private static final String EXTENSION = ".jpg";
    private static final int JPEG_QUALITY = 90;

    /** The user's backgrounds, oldest first. */
    public static List<File> list(Context context) {
        File[] files = dir(context).listFiles((d, name) ->
                name.startsWith(PREFIX) && name.endsWith(EXTENSION));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        List<File> result = new ArrayList<>(files.length);
        Collections.addAll(result, files);
        return result;
    }

    public static boolean canAddMore(int currentCount) {
        return currentCount < MAX_UPLOADS;
    }

    /** True for a saved preference value that points at an uploaded file rather than a bundled asset. */
    public static boolean isUserPath(String savedPath) {
        return savedPath != null && savedPath.startsWith("/");
    }

    /**
     * The saved background to actually use. Uploaded photos are kept on disk when the bg_custom
     * feature is off but are not selectable: a saved one then gives way to {@code fallback}.
     * The saved preference itself is not changed, so it applies again when the plan does.
     */
    public static String effectivePath(String savedPath, boolean customAllowed, String fallback) {
        if (savedPath == null) return fallback;
        return isUserPath(savedPath) && !customAllowed ? fallback : savedPath;
    }

    public static boolean delete(File file) {
        return file.delete();
    }

    /**
     * Power-of-two {@code inSampleSize} that decodes the image at no less than {@code maxDimension}
     * on its longest edge, so very large photos never get fully loaded into memory.
     */
    public static int sampleSizeFor(int width, int height, int maxDimension) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= maxDimension) sample *= 2;
        return sample;
    }

    /**
     * Decodes {@code source}, applies its EXIF rotation, downscales to {@link #MAX_DIMENSION} and
     * stores it as a new background. Blocking; call off the main thread.
     *
     * @throws IOException if the file can't be read or isn't a decodable image
     */
    public static File importImage(Context context, Uri source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("Cannot open " + source);
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Not an image: " + source);
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION);
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("Cannot open " + source);
            decoded = BitmapFactory.decodeStream(in, null, opts);
        } catch (OutOfMemoryError e) {
            throw new IOException("Image too large", e);
        }
        if (decoded == null) throw new IOException("Cannot decode " + source);

        Bitmap oriented = applyExifRotation(context, source, decoded);
        Bitmap result = scaleToFit(oriented, MAX_DIMENSION);
        try {
            File dir = dir(context);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File target = new File(dir, PREFIX + System.currentTimeMillis() + EXTENSION);
            File temp = new File(dir, target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw new IOException("Cannot encode image");
                }
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                throw new IOException("Cannot save " + target);
            }
            return target;
        } finally {
            if (result != oriented) result.recycle();
            if (oriented != decoded) oriented.recycle();
            decoded.recycle();
        }
    }

    private static Bitmap applyExifRotation(Context context, Uri source, Bitmap bitmap) {
        int degrees = 0;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in != null) {
                int orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            }
        } catch (IOException ignored) {
            // No EXIF (PNG, WebP, stripped file): keep as decoded.
        }
        if (degrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private static Bitmap scaleToFit(Bitmap bitmap, int maxDimension) {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest <= maxDimension) return bitmap;
        float scale = maxDimension / (float) longest;
        return Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
    }

    private static File dir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }
}
