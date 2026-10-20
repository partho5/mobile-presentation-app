package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;

/**
 * The upgrade sheet for a locked feature: its name and benefit, the tier it needs (badge and
 * monthly price), and what the user can do about it. With {@code CHECKOUT_LINK = "web"} that is
 * "Upgrade to <Tier>" (billing page in the browser) or, signed out, "Sign in to upgrade"; with
 * {@code "none"} the sheet only says which plan has the feature and offers sign-in / refresh. When
 * the user comes back and the feature is on, the sheet closes, shows "<Tier> unlocked" and runs
 * the action they tapped. Open it through {@link Gate#run} unless the caller has its own check.
 * Main thread only.
 */
public final class PaywallSheet {

    /** Where the user went and is expected back from. */
    private enum Await { NONE, SIGN_IN, BILLING }

    /** What the big button does right now. */
    private enum Primary { NONE, SIGN_IN, UPGRADE }

    private static final long CHECK_SIGN_IN_MS = 8_000L;
    private static final long CHECK_BILLING_MS = 14_000L;
    /** The prices are missing (just signed out): ask once, then look for them for this long. */
    private static final long PRICE_POLL_MS = 1_500L;
    private static final int PRICE_POLLS = 10;

    private final Activity host;
    private final String feature;
    private final Runnable onGranted;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Entitlements.Listener listener = this::onEntitlementsChanged;
    private final Runnable stopChecking = this::onCheckTimeout;

    private final BottomSheetDialog dialog;
    private final ReturnWatcher watcher;
    private final TextView tvFeature;
    private final TextView tvBadge;
    private final TextView tvBenefit;
    private final TextView tvPrice;
    private final TextView tvNote;
    private final ProgressBar progress;
    private final Button btnPrimary;
    private final Button btnRefresh;

    private Await awaiting = Await.NONE;
    private Primary primary = Primary.NONE;
    private boolean checking;
    private boolean linkPending;
    private boolean finished;
    private String notice;
    private int pricePolls;

    /**
     * Shows the sheet for {@code feature}. When the feature is already on, nothing is shown and
     * {@code onGranted} runs at once. {@code onGranted} (may be null) also runs after the user
     * upgrades and comes back, but not if they close the sheet first.
     */
    public static void show(Activity host, String feature, Runnable onGranted) {
        if (host == null || feature == null || host.isFinishing() || host.isDestroyed()) return;
        if (Entitlements.has(feature)) {
            if (onGranted != null) onGranted.run();
            return;
        }
        new PaywallSheet(host, feature, onGranted).open();
    }

    private PaywallSheet(Activity host, String feature, Runnable onGranted) {
        this.host = host;
        this.feature = feature;
        this.onGranted = onGranted;

        View root = LayoutInflater.from(host).inflate(R.layout.dialog_paywall, null);
        tvFeature = root.findViewById(R.id.tv_paywall_feature);
        tvBadge = root.findViewById(R.id.tv_paywall_badge);
        tvBenefit = root.findViewById(R.id.tv_paywall_benefit);
        tvPrice = root.findViewById(R.id.tv_paywall_price);
        tvNote = root.findViewById(R.id.tv_paywall_note);
        progress = root.findViewById(R.id.progress_paywall);
        btnPrimary = root.findViewById(R.id.btn_paywall_primary);
        btnRefresh = root.findViewById(R.id.btn_paywall_refresh);
        Button btnClose = root.findViewById(R.id.btn_paywall_close);

        dialog = new BottomSheetDialog(host);
        dialog.setContentView(root);
        // Cancel = back, a tap outside, or "Not now"; a dismiss we cause ourselves is not a close.
        dialog.setOnCancelListener(d -> ActivityLog.log("paywall_action", "action", "close", "feature", feature));
        dialog.setOnDismissListener(d -> release());
        dialog.setOnShowListener(d -> expand());

        btnPrimary.setOnClickListener(v -> onPrimary());
        btnRefresh.setOnClickListener(v -> onRefresh());
        btnClose.setOnClickListener(v -> dialog.cancel());

        watcher = new ReturnWatcher(host, this::onResumed, dialog::dismiss);
    }

    private void open() {
        ActivityLog.log("paywall_view", "feature", feature, "plan", Entitlements.plan());
        Entitlements.addListener(listener);
        render();
        dialog.show();
        watcher.attach();
        if (Entitlements.catalogue().plans().isEmpty()) {
            // No prices yet (first run, or just signed out): fetch them and fill them in when they land.
            EntitlementsRefresher.refreshNow();
            main.postDelayed(this::pollPrices, PRICE_POLL_MS);
        }
    }

    /** Full height on every orientation, with the rounded top of our own background showing. */
    private void expand() {
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        sheet.setBackgroundColor(Color.TRANSPARENT);
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    // ---- state -> views ----

    private void render() {
        if (finished) return;
        boolean signedIn = SessionManager.get(host).hasSession();
        boolean web = BillingLink.enabled();
        String need = requiredPlan();
        String tier = TierBadge.name(need);

        tvFeature.setText(FeatureLabels.name(host, feature));
        TierBadge.show(tvBadge, need);
        tvBenefit.setText(FeatureLabels.benefit(host, feature, tier));

        // A price next to a "no buy link" build could read as steering people off Play: web only.
        String price = web ? PlanPrice.monthly(host, Entitlements.catalogue().plan(need)) : null;
        tvPrice.setVisibility(price == null ? View.GONE : View.VISIBLE);
        if (price != null) tvPrice.setText(host.getString(R.string.paywall_tier_price, tier, price));

        String note;
        if (checking) note = host.getString(R.string.paywall_checking);
        else if (notice != null) note = notice;
        else note = web ? null : host.getString(R.string.paywall_available_on, tier);
        tvNote.setVisibility(note == null ? View.GONE : View.VISIBLE);
        if (note != null) tvNote.setText(note);
        progress.setVisibility(checking ? View.VISIBLE : View.GONE);

        if (checking) primary = Primary.NONE;
        else if (web) primary = signedIn ? Primary.UPGRADE : Primary.SIGN_IN;
        else primary = signedIn ? Primary.NONE : Primary.SIGN_IN;
        btnPrimary.setVisibility(primary == Primary.NONE ? View.GONE : View.VISIBLE);
        btnPrimary.setEnabled(!linkPending);
        if (primary == Primary.UPGRADE) {
            btnPrimary.setText(host.getString(R.string.paywall_upgrade, tier));
        } else if (primary == Primary.SIGN_IN) {
            btnPrimary.setText(web ? R.string.paywall_sign_in_upgrade : R.string.paywall_sign_in_subscribed);
        }
        btnRefresh.setVisibility(checking ? View.GONE : View.VISIBLE);
    }

    /** The plan to send people to: the lowest one with the feature (Starter if the phone cannot tell). */
    private String requiredPlan() {
        String lowest = Entitlements.lowestPlanFor(feature);
        return PremiumMark.isPaid(lowest) ? lowest : Feature.PLAN_STARTER;
    }

    // ---- taps ----

    private void onPrimary() {
        if (finished || checking || linkPending) return;
        if (primary == Primary.SIGN_IN) {
            ActivityLog.log("paywall_action", "action", "signin", "feature", feature);
            awaiting = Await.SIGN_IN;
            SessionManager.openSignIn(host, null);
        } else if (primary == Primary.UPGRADE) {
            ActivityLog.log("paywall_action", "action", "upgrade", "feature", feature);
            linkPending = true;
            notice = null;
            render();
            BillingLink.open(host, requiredPlan(), () -> {
                linkPending = false;
                awaiting = Await.BILLING;
                render(); // the button works again if no browser could open
            }, () -> {
                linkPending = false;
                render();
            });
        }
    }

    private void onRefresh() {
        if (finished || checking) return;
        ActivityLog.log("paywall_action", "action", "refresh", "feature", feature);
        // The user is looking at the sheet, so nobody is away in the browser (split-screen: no resume came).
        awaiting = Await.NONE;
        if (Entitlements.has(feature)) {
            granted();
            return;
        }
        EntitlementsRefresher.refreshBurst();
        startChecking(CHECK_BILLING_MS);
    }

    // ---- coming back ----

    /** The screen is in front again (after the browser or the sign-in screen), or the sheet was just attached. */
    private void onResumed() {
        if (finished) return;
        if (Entitlements.has(feature)) { // also catches a grant that arrived while the user was away
            granted();
            return;
        }
        Await was = awaiting;
        awaiting = Await.NONE;
        if (was == Await.BILLING) {
            BillingLink.refreshIfPending(host); // the app-level hook may have taken it already
            startChecking(CHECK_BILLING_MS);
        } else if (was == Await.SIGN_IN) {
            if (SessionManager.get(host).hasSession()) {
                EntitlementsRefresher.refreshNow();
                startChecking(CHECK_SIGN_IN_MS);
            } else {
                render();
            }
        }
    }

    private void onEntitlementsChanged() {
        if (finished) return;
        if (Entitlements.has(feature)) {
            // Away in the browser: wait for the user to come back before acting.
            if (awaiting == Await.NONE && watcher.isResumed()) granted();
            return;
        }
        // The plan moved but does not cover the feature yet (e.g. Starter, feature needs Pro).
        checking = false;
        main.removeCallbacks(stopChecking);
        render();
    }

    private void startChecking(long maxMs) {
        checking = true;
        notice = null;
        main.removeCallbacks(stopChecking);
        main.postDelayed(stopChecking, maxMs);
        render();
    }

    private void onCheckTimeout() {
        if (finished) return;
        checking = false;
        notice = host.getString(R.string.paywall_no_change);
        render();
    }

    private void pollPrices() {
        if (finished) return;
        if (!Entitlements.catalogue().plans().isEmpty()) {
            render();
            return;
        }
        if (++pricePolls < PRICE_POLLS) main.postDelayed(this::pollPrices, PRICE_POLL_MS);
    }

    /** The feature is on: close, say which plan, and run what the user tapped. */
    private void granted() {
        if (finished) return;
        finished = true; // the dismiss callback comes later; nothing may act on the sheet meanwhile
        dialog.dismiss();
        BillingLink.announceUnlocked(host); // once: the app-level watcher may have said it already
        if (onGranted != null) onGranted.run();
    }

    /** Runs on every dismiss (ours or the user's): let go of everything the sheet registered. */
    private void release() {
        finished = true;
        Entitlements.removeListener(listener);
        main.removeCallbacksAndMessages(null);
        watcher.detach();
    }
}
