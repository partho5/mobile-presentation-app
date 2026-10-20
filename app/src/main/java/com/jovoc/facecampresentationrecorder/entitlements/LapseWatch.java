package com.jovoc.facecampresentationrecorder.entitlements;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.auth.KeyValueStore;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the last plan the user was seen on, to tell when a paid plan ended: the one-time
 * "Your plan ended" notice. A drop only counts when it is certain and the user did not cause it:
 * <ul>
 * <li>the lower plan must come from a verified token (a token that merely ran out while the phone
 * was offline says nothing about the subscription);</li>
 * <li>signing out or in, or switching workspace, starts a new baseline (the {@code scope}), because
 * a different plan then is not a lapse.</li>
 * </ul>
 * Each lapse is reported once: the baseline moves to the plan found.
 */
public final class LapseWatch {

    /** A paid plan that ended: the plan the user had and the one in force now. */
    public static final class Lapse {
        public final String endedPlan;
        public final String nowPlan;

        Lapse(String endedPlan, String nowPlan) {
            this.endedPlan = endedPlan;
            this.nowPlan = nowPlan;
        }
    }

    static final String FILE = "plan_notice_prefs";
    private static final String K_PLAN = "last_plan";
    private static final String K_SCOPE = "last_scope";

    private final KeyValueStore kv;

    LapseWatch(KeyValueStore kv) {
        this.kv = kv;
    }

    public static LapseWatch open(Context context) {
        return new LapseWatch(new EntitlementsPrefs.SharedPrefsStore(
                context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)));
    }

    /**
     * Compares the plan in force with the one remembered.
     * @param plan the plan key in force now
     * @param verified true when a verified token backs {@code plan} (false: Free only because no token counts)
     * @param scope who the plan belongs to: session id and workspace id, empty parts when signed out
     * @return the lapse to tell the user about (once), else null
     */
    public Lapse check(String plan, boolean verified, String scope) {
        String last = kv.get(K_PLAN);
        if (last == null || !scope.equals(kv.get(K_SCOPE))) {
            // First look, or another account / workspace: nothing to compare with.
            save(verified ? plan : Feature.PLAN_FREE, scope);
            return null;
        }
        if (!verified) return null;
        if (!plan.equals(last)) save(plan, scope);
        int was = Feature.rank(last);
        int now = Feature.rank(plan);
        // A plan the app does not know has no place in the order: nothing can be said about it.
        if (was >= 0 && now >= 0 && now < was) return new Lapse(last, plan);
        return null;
    }

    private void save(String plan, String scope) {
        Map<String, String> m = new HashMap<>();
        m.put(K_PLAN, plan);
        m.put(K_SCOPE, scope);
        kv.putAll(m);
    }
}
