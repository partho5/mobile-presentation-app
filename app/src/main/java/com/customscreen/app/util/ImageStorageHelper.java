package com.customscreen.app.util;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class ImageStorageHelper {

    public static String saveImageToInternalStorage(Context context, Uri uri) {
        try {
            File imagesDir = new File(context.getFilesDir(), "slides_images");
            if (!imagesDir.exists()) {
                imagesDir.mkdirs();
            }

            String fileName = "slide_img_" + System.currentTimeMillis() + ".jpg";
            File destFile = new File(imagesDir, fileName);

            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            return destFile.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
