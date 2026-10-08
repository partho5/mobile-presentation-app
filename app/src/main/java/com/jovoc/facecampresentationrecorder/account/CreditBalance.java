package com.jovoc.facecampresentationrecorder.account;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the server says the workspace can spend. Numbers are shown exactly as received
 * (ux.balance-header: nothing is added or subtracted on the client).
 */
public final class CreditBalance {

    public final long available;
    /** Credits held for scheduled posts, or -1 when the server route in use does not say. */
    public final long reserved;
    /** True when the server flagged the balance as low (only the full route says). */
    public final boolean low;

    public CreditBalance(long available, long reserved, boolean low) {
        this.available = available;
        this.reserved = reserved;
        this.low = low;
    }

    public boolean hasReserved() {
        return reserved >= 0;
    }

    /** GET /credits/balance: {available, reserved, plan, allotment, low, canTopUp}. */
    public static CreditBalance parseBalanceView(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.has("available")) throw new JSONException("no available");
        return new CreditBalance(o.getLong("available"), o.has("reserved") ? o.getLong("reserved") : -1L,
                o.optBoolean("low", false));
    }

    /** GET /copilot/credits: {credits} = the available balance only. */
    public static CreditBalance parseCopilotCredits(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.has("credits")) throw new JSONException("no credits");
        return new CreditBalance(o.getLong("credits"), -1L, false);
    }
}
