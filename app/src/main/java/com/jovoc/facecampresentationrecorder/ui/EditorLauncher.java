package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.snackbar.Snackbar;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.edit.EditGroupMath;
import com.jovoc.facecampresentationrecorder.util.RecordingDeleter;
import com.jovoc.facecampresentationrecorder.util.VideoGroupStore;

import java.io.File;
import java.util.List;

/**
 * Opens the video editor from any screen and, after a successful Save, offers a one-tap "Delete
 * original?" (default keep, never automatic). Screens that can host the editor implement
 * {@link Host} and register a launcher with {@link #register}; any other caller falls back to a
 * plain start with no offer.
 */
public final class EditorLauncher {

    /** A screen that launches the editor for a result so it can show the delete offer. */
    public interface Host {
        void launchEditor(String masterPath);
    }

    private static final int OFFER_MS = 10_000;
    private static final String OFFER_PREFS = "editor_pending_offer";
    private static final String KEY_ORIGINAL = "original";
    private static final String KEY_OUTPUT = "output";

    private EditorLauncher() {}

    /** Opens the editor on {@code masterPath} through the hosting screen, if there is one. */
    public static void launch(Context context, String masterPath) {
        Activity activity = unwrap(context);
        if (activity instanceof Host) {
            ((Host) activity).launchEditor(masterPath);
        } else {
            context.startActivity(VideoEditorActivity.newIntent(context, masterPath));
        }
    }

    /**
     * Call before the host is STARTED (a field initialiser or onCreate). {@code onOriginalDeleted}
     * runs after the offer was taken, so the screen can refresh its list.
     */
    public static ActivityResultLauncher<Intent> register(ComponentActivity host,
                                                          Runnable onOriginalDeleted) {
        return host.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> onResult(host, result, onOriginalDeleted));
    }

    private static void onResult(Activity host, ActivityResult result, Runnable onOriginalDeleted) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
        String output = result.getData().getStringExtra(VideoEditorActivity.EXTRA_OUTPUT_PATH);
        String original = result.getData().getStringExtra(VideoEditorActivity.EXTRA_MASTER_PATH);
        // This screen shows it now, so it must not be shown again from the stored copy
        forgetOffer(host);
        showOffer(host, original, output, onOriginalDeleted);
    }

    /** Remembers a finished Save so a screen shows the offer even if the editor was left early. */
    static void rememberOffer(Context context, String original, String output) {
        prefs(context).edit().putString(KEY_ORIGINAL, original).putString(KEY_OUTPUT, output).apply();
    }

    private static void forgetOffer(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(OFFER_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Call from a host's onResume: shows the offer of a Save that finished without the editor
     * handing its result back (it was left, backgrounded or killed first). Shown once.
     */
    public static void showPendingOffer(Activity host, Runnable onOriginalDeleted) {
        SharedPreferences p = prefs(host);
        String original = p.getString(KEY_ORIGINAL, null);
        String output = p.getString(KEY_OUTPUT, null);
        if (original == null || output == null) return;
        forgetOffer(host);
        showOffer(host, original, output, onOriginalDeleted);
    }

    private static void showOffer(Activity host, String original, String output,
                                  Runnable onOriginalDeleted) {
        if (output == null || original == null || output.equals(original)) return;
        if (!new File(output).isFile() || !new File(original).exists()) return;
        if (host.isFinishing() || host.isDestroyed()) return;
        View root = host.findViewById(android.R.id.content);
        if (root == null) return;

        Snackbar.make(root, host.getString(R.string.editor_saved_offer, new File(output).getName()),
                        OFFER_MS)
                .setAction(R.string.editor_delete_original, v ->
                        deleteOriginal(host, original, output, onOriginalDeleted))
                .show();
    }

    /** Deletes the original and its own crops (not the edits) through the app's normal delete path. */
    private static void deleteOriginal(Context context, String original, String output,
                                       Runnable onDone) {
        Context app = context.getApplicationContext();
        List<String> doomed = EditGroupMath.deletablePaths(
                VideoGroupStore.getGroupPaths(app, original), original, output);
        int failed = 0;
        for (String path : doomed) {
            if (!RecordingDeleter.deleteFile(app, new File(path))) failed++;
        }
        ActivityLog.log("video_delete", "count", doomed.size() - failed);
        Toast.makeText(context, failed == 0 ? R.string.editor_original_deleted
                : R.string.editor_original_delete_failed, Toast.LENGTH_SHORT).show();
        if (onDone != null) onDone.run();
    }

    private static Activity unwrap(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
