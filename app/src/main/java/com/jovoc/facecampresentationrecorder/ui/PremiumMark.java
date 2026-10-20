package com.jovoc.facecampresentationrecorder.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;

/**
 * The premium symbol: a small crown beside the label of every feature whose lowest plan is above
 * Free (so it follows the server: a feature moved to Free loses its crown, a new paid one gets it).
 * Locked: gold crown, plus the tier name when a badge view is given. Unlocked: the crown stays,
 * muted, with no tier text. Call again after the plan changes (e.g. from an
 * {@link Entitlements.Listener}); each call sets the whole look.
 */
public final class PremiumMark {

    static final int GOLD = 0xFFFBBF24;
    static final int MUTED = 0xFF8A8F9C;
    private static final int LABEL_ICON_DP = 16;
    private static final int LABEL_GAP_DP = 6;

    private PremiumMark() {
    }

    /** True when the feature needs a paid plan, as the server (or the app's defaults before the first fetch) says. */
    public static boolean isPremium(String feature) {
        return isPaid(Entitlements.lowestPlanFor(feature));
    }

    static boolean isPaid(String lowestPlan) {
        return lowestPlan != null && !lowestPlan.isEmpty()
                && !Feature.PLAN_FREE.equalsIgnoreCase(lowestPlan);
    }

    /** A crown ImageView beside a label: hidden for a free feature, gold when locked, muted when unlocked. */
    public static void apply(ImageView crown, String feature) {
        apply(crown, null, feature);
    }

    /** As above, plus {@code badge} (any TextView, may be null) showing the tier name while the feature is locked. */
    public static void apply(ImageView crown, TextView badge, String feature) {
        String lowest = Entitlements.lowestPlanFor(feature);
        boolean premium = isPaid(lowest);
        boolean locked = premium && !Entitlements.has(feature);
        if (crown != null) {
            crown.setVisibility(premium ? View.VISIBLE : View.GONE);
            if (premium) {
                crown.setImageResource(R.drawable.ic_premium);
                crown.setImageTintList(ColorStateList.valueOf(locked ? GOLD : MUTED));
                crown.setContentDescription(crown.getContext().getString(R.string.premium_cd));
            }
        }
        if (badge != null) {
            if (locked) TierBadge.show(badge, lowest);
            else TierBadge.hide(badge);
        }
    }

    /**
     * For a label that cannot get a separate icon (a switch or a radio button): puts the crown in
     * the label's end drawable, or removes it for a free feature. Tier text needs the badge variant.
     */
    public static void apply(TextView label, String feature) {
        Drawable[] d = label.getCompoundDrawablesRelative();
        Drawable crown = null;
        if (isPremium(feature)) {
            Drawable base = ContextCompat.getDrawable(label.getContext(), R.drawable.ic_premium);
            if (base != null) {
                crown = base.mutate();
                crown.setTint(Entitlements.has(feature) ? MUTED : GOLD);
                int size = TierBadge.dp(label.getContext(), LABEL_ICON_DP);
                crown.setBounds(0, 0, size, size);
                label.setCompoundDrawablePadding(TierBadge.dp(label.getContext(), LABEL_GAP_DP));
            }
        }
        label.setCompoundDrawablesRelative(d[0], d[1], crown, d[3]);
    }
}
