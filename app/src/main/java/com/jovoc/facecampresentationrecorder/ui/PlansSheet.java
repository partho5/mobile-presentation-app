package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.tracker.Meta;
import com.jovoc.facecampresentationrecorder.entitlements.Catalogue;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every plan with the features it adds, the user's own plan marked. With {@code CHECKOUT_LINK =
 * "web"} each higher plan has an "Upgrade to <Tier>" button (billing page in the browser) and shows
 * its price; with {@code "none"} the sheet only lists what each plan includes. Opened from the Plan
 * row of the Account screen. Main thread only.
 */
public final class PlansSheet {

    private static final int CHECK_ICON_DP = 16;
    private static final int CHECK_GAP_DP = 8;
    private static final int CHECK_GREEN = 0xFF4ADE80;
    private static final long CHECK_MS = 12_000L;

    private final Activity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Entitlements.Listener listener = this::onEntitlementsChanged;
    private final Runnable stopChecking = this::onCheckTimeout;

    private final BottomSheetDialog dialog;
    private final ReturnWatcher watcher;
    private final TextView tvSubtitle;
    private final LinearLayout container;
    private final ProgressBar progress;
    private final Button btnRefresh;

    private boolean awaitingBilling;
    private boolean checking;
    private boolean linkPending;
    private boolean released;
    /** The plan shown by the last render, to tell when it went up. */
    private String shownPlan = Feature.PLAN_FREE;

    public static void show(Activity host) {
        if (host == null || host.isFinishing() || host.isDestroyed()) return;
        new PlansSheet(host).open();
    }

    private PlansSheet(Activity host) {
        this.host = host;
        View root = LayoutInflater.from(host).inflate(R.layout.dialog_plans, null);
        tvSubtitle = root.findViewById(R.id.tv_plans_subtitle);
        container = root.findViewById(R.id.plans_container);
        progress = root.findViewById(R.id.progress_plans);
        btnRefresh = root.findViewById(R.id.btn_plans_refresh);

        dialog = new BottomSheetDialog(host);
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> release());
        dialog.setOnShowListener(d -> expand());
        root.findViewById(R.id.btn_plans_close).setOnClickListener(v -> dialog.dismiss());
        btnRefresh.setOnClickListener(v -> onRefresh());

        watcher = new ReturnWatcher(host, this::onResumed, dialog::dismiss);
    }

    private void open() {
        ActivityTracker.log("sheet", "plans", Meta.of("plan", Entitlements.plan()));
        Entitlements.addListener(listener);
        render();
        dialog.show();
        watcher.attach();
        if (Entitlements.catalogue().plans().isEmpty()) EntitlementsRefresher.refreshNow();
    }

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
        if (released) return;
        String current = Entitlements.plan();
        shownPlan = current;
        tvSubtitle.setText(host.getString(R.string.plans_subtitle, Entitlements.planName()));

        Catalogue catalogue = Entitlements.catalogue();
        List<String> catalogueKeys = new ArrayList<>();
        for (Catalogue.Plan p : catalogue.plans()) catalogueKeys.add(p.key);
        List<String> keys = PlanFeatures.planKeys(catalogueKeys);
        int currentIndex = indexOf(keys, current);
        Map<String, String> lowestByFeature = new LinkedHashMap<>();
        for (String feature : Feature.DEFAULT_LOWEST.keySet()) {
            lowestByFeature.put(feature, Entitlements.lowestPlanFor(feature));
        }
        boolean web = BillingLink.enabled();

        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(host);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            View card = inflater.inflate(R.layout.item_plan, container, false);
            String name = TierBadge.name(key);
            boolean isCurrent = i == currentIndex;
            card.setSelected(isCurrent);
            ((TextView) card.findViewById(R.id.tv_plan_name)).setText(name);

            // Prices only where a buy link exists (see PaywallSheet).
            String price = web ? PlanPrice.monthly(host, catalogue.plan(key)) : null;
            TextView tvPrice = card.findViewById(R.id.tv_plan_price);
            tvPrice.setVisibility(price == null ? View.GONE : View.VISIBLE);
            if (price != null) tvPrice.setText(price);

            TextView tvCurrent = card.findViewById(R.id.tv_plan_current);
            if (isCurrent) {
                TierBadge.style(tvCurrent);
                tvCurrent.setVisibility(View.VISIBLE);
            }

            List<String> features = PlanFeatures.featuresOf(key, lowestByFeature);
            if (i > 0) {
                String previous = TierBadge.name(keys.get(i - 1));
                TextView intro = card.findViewById(R.id.tv_plan_intro);
                intro.setText(host.getString(features.isEmpty()
                        ? R.string.plans_everything_in_only : R.string.plans_everything_in, previous));
                intro.setVisibility(View.VISIBLE);
            }
            LinearLayout list = card.findViewById(R.id.plan_features);
            for (String feature : features) list.addView(featureRow(feature));

            Button upgrade = card.findViewById(R.id.btn_plan_upgrade);
            if (web && i > Math.max(currentIndex, 0)) {
                upgrade.setText(host.getString(R.string.paywall_upgrade, name));
                upgrade.setEnabled(!linkPending);
                upgrade.setVisibility(View.VISIBLE);
                upgrade.setOnClickListener(v -> onUpgrade(key));
            }
            container.addView(card);
        }
        progress.setVisibility(checking ? View.VISIBLE : View.GONE);
        btnRefresh.setVisibility(checking ? View.GONE : View.VISIBLE);
    }

    private TextView featureRow(String feature) {
        TextView row = new TextView(host);
        row.setText(FeatureLabels.name(host, feature));
        row.setTextColor(0xFFE6E8EE);
        row.setTextSize(14f);
        row.setPadding(0, TierBadge.dp(host, 8), 0, 0);
        Drawable check = ContextCompat.getDrawable(host, R.drawable.ic_check_circle);
        if (check != null) {
            Drawable tinted = check.mutate();
            tinted.setTint(CHECK_GREEN);
            int size = TierBadge.dp(host, CHECK_ICON_DP);
            tinted.setBounds(0, 0, size, size);
            row.setCompoundDrawablesRelative(tinted, null, null, null);
            row.setCompoundDrawablePadding(TierBadge.dp(host, CHECK_GAP_DP));
        }
        return row;
    }

    private static int indexOf(List<String> keys, String plan) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equalsIgnoreCase(plan)) return i;
        }
        return -1;
    }

    // ---- taps and coming back ----

    private void onUpgrade(String planKey) {
        if (released || linkPending) return;
        ActivityTracker.log("click", "paywall upgrade", Meta.of("feature", "plans"));
        linkPending = true;
        render();
        BillingLink.open(host, planKey, () -> {
            linkPending = false;
            awaitingBilling = true;
            render(); // the button works again if no browser could open
        }, () -> {
            linkPending = false;
            render();
        });
    }

    private void onRefresh() {
        if (released || checking) return;
        ActivityTracker.log("click", "paywall refresh", Meta.of("feature", "plans"));
        EntitlementsRefresher.refreshBurst();
        startChecking();
    }

    private void onResumed() {
        if (released || !awaitingBilling) return;
        awaitingBilling = false;
        BillingLink.refreshIfPending(host); // the app-level hook may have taken it already
        startChecking();
    }

    private void startChecking() {
        checking = true;
        main.removeCallbacks(stopChecking);
        main.postDelayed(stopChecking, CHECK_MS);
        render();
    }

    private void onCheckTimeout() {
        if (released) return;
        checking = false;
        render();
    }

    private void onEntitlementsChanged() {
        if (released) return;
        String before = shownPlan;
        String now = Entitlements.plan();
        boolean up = Feature.rank(now) > Feature.rank(before) && Feature.rank(before) >= 0;
        if (up) {
            checking = false;
            main.removeCallbacks(stopChecking);
            BillingLink.announceUnlocked(host); // once: the app-level watcher may have said it already
        }
        render();
    }

    private void release() {
        released = true;
        Entitlements.removeListener(listener);
        main.removeCallbacksAndMessages(null);
        watcher.detach();
    }
}
