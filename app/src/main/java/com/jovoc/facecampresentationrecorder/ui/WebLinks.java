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

    public static String channelsUrl() {
        return BuildConfig.WEB_BASE_URL + BuildConfig.CHANNELS_PATH;
    }

    public static String privacyUrl() {
        return BuildConfig.WEB_BASE_URL + BuildConfig.PRIVACY_PATH;
    }

    public static String termsUrl() {
        return BuildConfig.WEB_BASE_URL + BuildConfig.TERMS_PATH;
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
