package com.jovoc.facecampresentationrecorder.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.R;

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
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(context instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.web_no_browser, Toast.LENGTH_LONG).show();
        }
    }
}
