package com.jovoc.facecampresentationrecorder.util;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;

/**
 * Shared rename flow for saved recordings.
 *
 * Extracted so the saved-recordings overflow menu and the video player popup
 * offer the same prompt, validation and MediaStore bookkeeping rather than
 * each carrying their own copy.
 */
public final class VideoRenameHelper {

    private static final String TAG = "VideoRenameHelper";

    public abstract static class RenameCallback {
        /**
         * Runs immediately before the file is renamed on disk, so a caller that
         * holds the file open (the player, for instance) can release its handle.
         */
        public void onBeforeRename() {}

        public abstract void onRenamed(File oldFile, File newFile);
    }

    private VideoRenameHelper() {}

    /** Prompts for a new filename and renames the file if the input is valid. */
    public static void promptRename(Context context, File currentFile, RenameCallback callback) {
        if (context == null || currentFile == null || !currentFile.exists()) {
            if (context != null) {
                Toast.makeText(context, "File no longer exists", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Rename Video");

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        String name = currentFile.getName();
        if (name.endsWith(".mp4")) {
            name = name.substring(0, name.length() - 4);
        }
        input.setText(name);
        input.setSelection(name.length());

        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        builder.setView(input);
        input.setPadding(padding, padding, padding, padding);

        builder.setPositiveButton("Rename", (d, which) -> {
            String newName = input.getText().toString().trim();
            if (TextUtils.isEmpty(newName)) {
                Toast.makeText(context, "Filename cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newName.endsWith(".mp4")) {
                newName += ".mp4";
            }

            if (currentFile.getName().equals(newName)) {
                return;
            }

            File newFile = new File(currentFile.getParentFile(), newName);
            if (newFile.exists()) {
                Toast.makeText(context, "A file with this name already exists", Toast.LENGTH_SHORT).show();
                return;
            }

            if (callback != null) {
                callback.onBeforeRename();
            }

            if (!currentFile.renameTo(newFile)) {
                Toast.makeText(context, "Failed to rename file", Toast.LENGTH_SHORT).show();
                return;
            }

            updateMediaStore(context, currentFile, newFile);

            if (callback != null) {
                callback.onRenamed(currentFile, newFile);
            }
            Toast.makeText(context, "Renamed successfully", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (d, which) -> d.cancel());
        builder.show();
    }

    /** Points the existing MediaStore row at the new path, then rescans both. */
    private static void updateMediaStore(Context context, File oldFile, File newFile) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.TITLE, newFile.getName());
            values.put(MediaStore.Video.Media.DISPLAY_NAME, newFile.getName());
            values.put(MediaStore.Video.Media.DATA, newFile.getAbsolutePath());
            resolver.update(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values,
                    MediaStore.Video.Media.DATA + "=?",
                    new String[]{oldFile.getAbsolutePath()});
        } catch (Exception e) {
            Log.w(TAG, "ContentResolver rename update fallback", e);
        }

        MediaScannerConnection.scanFile(context,
                new String[]{oldFile.getAbsolutePath(), newFile.getAbsolutePath()},
                null, null);
    }
}
