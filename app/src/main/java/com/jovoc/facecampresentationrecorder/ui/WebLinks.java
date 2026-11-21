package com.jovoc.facecampresentationrecorder.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.tracker.Meta;

/** Pages of the website the app sends the user to (they open in the browser, not in the app). */
public final class WebLinks {

    private WebLinks() {}

    /** Empty until a privacy page exists (see build.gradle). */
    public static String privacyUrl() {
        return BuildConfig.PRIVACY_URL;
    }

    /** Empty until a terms page exists (see build.gradle). */
    public static String termsUrl() {
        return BuildConfig.TERMS_URL;
    }

    public static void open(Context context, String url) {
        // Only the site, never the path or query (a link can carry a one-time code).
        ActivityTracker.log("click", "web link", Meta.of("host", Uri.parse(url).getHost()));
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(context instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (ActivityNotFoundException e) {
            ActivityTracker.error("web_link", "no browser", e);
            Toast.makeText(context, R.string.web_no_browser, Toast.LENGTH_LONG).show();
        }
    }
}
