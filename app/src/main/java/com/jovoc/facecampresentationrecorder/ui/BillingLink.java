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
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.publish.PublishApi;
import com.jovoc.facecampresentationrecorder.publish.PublishException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the user to the website's billing page for a plan, already signed in (build flag
 * {@code CHECKOUT_LINK = "web"}), and remembers that they went, even if Android closes the app
 * meanwhile. The trip stays pending until the plan really goes up (then "<Plan> unlocked" is shown
 * once, wherever the user is) or 24 hours pass; each time the app comes to the front meanwhile the
 * plan is fetched again (see {@link BillingTrip}).
 */
public final class BillingLink {

    private static final String PREFS = "billing_prefs";
    private static final String K_OPENED_AT = "opened_at";
    private static final String K_RANK = "rank_at_open";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "billing-link");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static BillingTrip trip;
    private static boolean listening;

    private BillingLink() {
    }

    /**
     * Call once at startup, after {@code Entitlements.init}: watches the plan for the whole life of
     * the app so a pending trip ends with the unlock toast even when no sheet is open any more.
     */
    public static synchronized void init(Context context) {
        if (listening) return;
        listening = true;
        Context app = context.getApplicationContext();
        Entitlements.addListener(() -> {
            try {
                if (trip(app).onPlan(Feature.rank(Entitlements.plan())) == BillingTrip.Step.RISEN) {
                    announceUnlocked(app);
                }
            } catch (RuntimeException e) {
                // Plans must never break the screen on top.
            }
        });
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
        trip(c).begin(Feature.rank(Entitlements.plan()));
    }

    /**
     * Call when the app comes back to the front (and when a sheet's screen resumes). While a trip
     * to the billing page is pending it fetches the plan now, again after 3 s and after 10 s (the
     * upgrade can reach the server a moment after the browser closes), at most once per 20 s so the
     * several callers of one return share a burst. A return does not use the trip up: it ends when
     * the plan has risen (the unlock toast shows) or after 24 hours.
     * @return true while a trip is still pending after this call
     */
    public static boolean refreshIfPending(Context context) {
        try {
            switch (trip(context).onForeground(Feature.rank(Entitlements.plan()))) {
                case REFRESH:
                    EntitlementsRefresher.refreshBurst();
                    return true;
                case WAIT:
                    return true;
                case RISEN:
                    announceUnlocked(context);
                    return false;
                default:
                    return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Toast "<Plan> unlocked" for the plan in force, once: the app-level watcher and an open sheet
     * both call this for the same rise and only the first one shows it. Main thread.
     */
    static void announceUnlocked(Context context) {
        Context app = context.getApplicationContext();
        if (!trip(app).shouldAnnounce(Entitlements.plan())) return;
        Toast.makeText(app, app.getString(R.string.paywall_unlocked, Entitlements.planName()),
                Toast.LENGTH_SHORT).show();
    }

    private static synchronized BillingTrip trip(Context c) {
        if (trip == null) {
            SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            trip = new BillingTrip(new PrefsStore(sp), System::currentTimeMillis);
        }
        return trip;
    }

    private static final class PrefsStore implements BillingTrip.Store {
        private final SharedPreferences sp;

        PrefsStore(SharedPreferences sp) {
            this.sp = sp;
        }

        @Override
        public long openedAtMs() {
            return sp.getLong(K_OPENED_AT, 0L);
        }

        @Override
        public int rankAtOpen() {
            return sp.getInt(K_RANK, BillingTrip.RANK_UNKNOWN);
        }

        @Override
        public void save(long openedAtMs, int rank) {
            sp.edit().putLong(K_OPENED_AT, openedAtMs).putInt(K_RANK, rank).apply();
        }

        @Override
        public void clear() {
            sp.edit().remove(K_OPENED_AT).remove(K_RANK).apply();
        }
    }
}
