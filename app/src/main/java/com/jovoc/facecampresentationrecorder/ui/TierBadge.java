package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.entitlements.Catalogue;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;

/**
 * The small gold pill with a plan's name ("Pro") shown next to a locked feature. Any bare
 * TextView will do: {@link #show} sets the look as well as the text.
 */
public final class TierBadge {

    private TierBadge() {
    }

    /** The plan's display name ("Pro"): the server's name when the catalogue is at hand, else the key capitalised. */
    public static String name(String planKey) {
        Catalogue.Plan p = Entitlements.catalogue().plan(planKey);
        return p != null ? p.name : Feature.defaultName(planKey);
    }

    /** Shows the badge with the name of {@code planKey}. */
    public static void show(TextView badge, String planKey) {
        style(badge);
        badge.setText(name(planKey));
        badge.setVisibility(View.VISIBLE);
    }

    public static void hide(TextView badge) {
        badge.setVisibility(View.GONE);
    }

    /** The pill look, without touching the text. */
    static void style(TextView badge) {
        badge.setBackgroundResource(R.drawable.bg_tier_badge);
        badge.setTextColor(PremiumMark.GOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setSingleLine(true);
        int h = dp(badge.getContext(), 7);
        int v = dp(badge.getContext(), 2);
        badge.setPadding(h, v, h, v);
    }

    static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
