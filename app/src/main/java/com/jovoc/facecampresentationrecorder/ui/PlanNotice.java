package com.jovoc.facecampresentationrecorder.ui;

import android.app.Activity;
import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.entitlements.LapseWatch;

/**
 * "Your plan ended. Pro features are off. Your recordings are safe." Shown once, the next time the
 * app is opened after a paid plan dropped (see {@link LapseWatch} for what counts as a drop).
 */
public final class PlanNotice {

    private PlanNotice() {
    }

    /** Screens that only forward or sign in are skipped; the notice waits for the next one. */
    public static boolean canShowOn(Activity activity) {
        return activity instanceof AppCompatActivity
                && !(activity instanceof StartupActivity)
                && !(activity instanceof OnboardingActivity)
                && !(activity instanceof SignInActivity);
    }

    /** Call once per app open, on a screen where {@link #canShowOn} is true. Never throws. */
    public static void showIfDue(Activity host) {
        try {
            if (host.isFinishing() || host.isDestroyed()) return;
            Context app = host.getApplicationContext();
            TokenStore store = SessionManager.get(app).repository().store();
            String scope = nz(store.sessionId()) + "|" + nz(store.workspaceId());
            boolean verified = Entitlements.tokenExpiresAtSec() > 0;
            LapseWatch.Lapse lapse = LapseWatch.open(app).check(Entitlements.plan(), verified, scope);
            if (lapse == null) return;
            String ended = TierBadge.name(lapse.endedPlan);
            String message = Feature.PLAN_FREE.equalsIgnoreCase(lapse.nowPlan)
                    ? host.getString(R.string.plans_lapse_message, ended)
                    : host.getString(R.string.plans_lapse_partial, TierBadge.name(lapse.nowPlan), ended);
            new MaterialAlertDialogBuilder(host)
                    .setMessage(message)
                    .setPositiveButton(R.string.plans_lapse_ok, null)
                    .show();
        } catch (RuntimeException e) {
            // A notice must never take a screen down.
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
