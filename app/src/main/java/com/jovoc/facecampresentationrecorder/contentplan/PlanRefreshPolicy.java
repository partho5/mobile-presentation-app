package com.jovoc.facecampresentationrecorder.contentplan;

/**
 * Pure policy for {@link ContentPlanCard#refresh}: whether the full week must be fetched now. Inside the freshness
 * window the cheap week stamp decides; any doubt keeps today's behaviour (no fetch inside the window).
 */
public final class PlanRefreshPolicy {

    private PlanRefreshPolicy() {
    }

    /**
     * True when the week is to be fetched. Outside the freshness window (or {@code force}) always. Inside it, only
     * when the server's stamp is known and differs from the stored one (or none is stored yet); an unknown stamp
     * ({@code serverStamp} null: the call failed) never triggers a fetch.
     */
    public static boolean shouldFetchWeek(boolean fresh, boolean force, String storedStamp, String serverStamp) {
        if (!fresh || force) return true;
        if (serverStamp == null) return false;
        return !serverStamp.equals(storedStamp);
    }
}
