package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the user to the website's billing page for a plan, already signed in (build flag
 * {@code CHECKOUT_LINK = "web"}), and remembers that they went so the plan is refreshed when they
 * come back, even if Android closed the app meanwhile.
 */
public final class BillingLink {

    private static final String PREFS = "billing_prefs";
    private static final String K_OPENED_AT = "opened_at";
    /** A trip to the billing page older than this is forgotten. */
    private static final long PENDING_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "billing-link");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BillingLink() {
    }

    /** True when this build may send people to the billing page; otherwise the sheets only name the plan. */
    public static boolean enabled() {
        return "web".equals(BuildConfig.CHECKOUT_LINK);
    }

    /**
     * Asks the server for a one-time billing link for {@code planKey} and opens it in the browser.
     * When the server refuses the request (the billing target may not be deployed yet) the website's
     * plain {@code /billing?tier=KEY} page opens instead, so the user can still sign in there.
     * {@code beforeOpen} runs on the main thread right before the browser starts; {@code onFailed}
     * when no link could be had (a message has already been shown). Neither runs if the screen is gone.
     */
    static void open(Activity host, String planKey, Runnable beforeOpen, Runnable onFailed) {
        Context app = host.getApplicationContext();
        IO.execute(() -> {
            String url = null;
            PublishException error = null;
            try {
                url = new PublishApi(SessionManager.get(app).repository())
                        .webLink(PublishApi.LINK_BILLING, planKey);
            } catch (PublishException e) {
                error = e;
            }
            final String link = url != null ? url
                    : error != null && usesFallback(error) ? fallbackUrl(planKey) : null;
            final PublishException err = error;
            MAIN.post(() -> {
                if (host.isFinishing() || host.isDestroyed()) return;
                if (link != null) {
                    markPending(app);
                    beforeOpen.run();
                    WebLinks.open(host, link);
                    return;
                }
                // SIGNED_OUT: the session manager is already taking the user home.
                if (err == null || err.kind != PublishException.Kind.SIGNED_OUT) {
                    Toast.makeText(host, failureMessage(host, err), Toast.LENGTH_LONG).show();
                }
                onFailed.run();
            });
        });
    }

    /** The server answered but refused (not offline, not signed out): the plain billing page still works. */
    static boolean usesFallback(PublishException e) {
        return e.kind != PublishException.Kind.NETWORK && e.kind != PublishException.Kind.SIGNED_OUT;
    }

    static String fallbackUrl(String planKey) {
        return BuildConfig.WEB_BASE_URL + "/billing?tier=" + Uri.encode(planKey);
    }

    private static String failureMessage(Context c, PublishException e) {
        if (e != null && e.kind == PublishException.Kind.NETWORK) return c.getString(R.string.err_offline);
        return c.getString(R.string.paywall_link_failed);
    }

    private static void markPending(Context c) {
        prefs(c).edit().putLong(K_OPENED_AT, System.currentTimeMillis()).apply();
    }

    /**
     * Call when the app comes back to the front. If the user went to the billing page since the
     * last call, fetches the plan now, again after 3 s and after 10 s (the upgrade can reach the
     * server a moment after the browser closes). Once per trip: the first caller takes it.
     * @return true when a refresh was started
     */
    public static boolean refreshIfPending(Context context) {
        try {
            SharedPreferences sp = prefs(context);
            long openedAt = sp.getLong(K_OPENED_AT, 0L);
            if (openedAt == 0L) return false;
            sp.edit().remove(K_OPENED_AT).apply();
            long age = System.currentTimeMillis() - openedAt;
            if (age < 0 || age > PENDING_MAX_AGE_MS) return false;
            EntitlementsRefresher.refreshBurst();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
