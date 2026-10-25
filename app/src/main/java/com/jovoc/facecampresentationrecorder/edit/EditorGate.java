package com.jovoc.facecampresentationrecorder.edit;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;

/**
 * The one place that decides whether the editor opens by itself after a recording, and whether
 * the preview's Publish button is shown. Nothing else reads these two switches.
 */
public final class EditorGate {

    private EditorGate() {}

    /** Auto-publish always wins: it already queues the 9:16, so the editor never auto-opens. */
    static boolean shouldShow(boolean autoPublishOn, boolean settingOn) {
        return !autoPublishOn && settingOn;
    }

    /** Auto-publish on and covered by the plan (the same test the Settings row uses). */
    public static boolean autoPublishOn(Context context) {
        return context.getSharedPreferences(RecordSettings.PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(RecordSettings.KEY_AUTO_PUBLISH, false)
                && Entitlements.has(Feature.AUTO_PUBLISH);
    }

    /** "Show video editor after recording": auto-publish off AND the setting on (default off). */
    public static boolean shouldShowAfterRecording(Context context) {
        return shouldShow(autoPublishOn(context), RecordSettings.showEditorAfterRecording(context));
    }

    /** The preview's Publish button is hidden while auto-publish is on (the 9:16 is already queued). */
    public static boolean showPublishButton(Context context) {
        return !autoPublishOn(context);
    }
}
