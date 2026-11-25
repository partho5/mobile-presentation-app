package com.jovoc.facecampresentationrecorder.contentplan;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishException;
import com.jovoc.facecampresentationrecorder.ui.WebLinks;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opens the website's content plan page, already signed in; shared by the plan card and the Topic sets sheet. */
public final class ContentPlanWeb {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "content-plan-web");
        t.setDaemon(true);
        return t;
    });

    private ContentPlanWeb() {
    }

    /** Opens the plan page; the plain page when the server cannot give a link. */
    public static void open(MainActivity host) {
        final Context app = host.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        IO.execute(() -> {
            String url = null;
            PublishException error = null;
            try {
                AuthRepository repo = SessionManager.get(app).repository();
                url = new PublishApi(repo).webLink(ContentPlanCard.LINK_TARGET, null);
            } catch (PublishException e) {
                error = e;
            }
            final PublishException err = error;
            final String link = url != null ? url
                    : err != null && err.kind != PublishException.Kind.NETWORK
                            && err.kind != PublishException.Kind.SIGNED_OUT
                    ? BuildConfig.WEB_BASE_URL + "/plan" : null;
            main.post(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                if (link != null) {
                    WebLinks.open(host, link);
                } else if (err != null && err.kind == PublishException.Kind.NETWORK) {
                    Toast.makeText(host, R.string.err_offline, Toast.LENGTH_LONG).show();
                } else if (err != null && err.kind != PublishException.Kind.SIGNED_OUT) {
                    Toast.makeText(host, R.string.cplan_link_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
