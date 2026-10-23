package com.jovoc.facecampresentationrecorder.publish;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The answer of POST /posts/charge-preview: what the publish costs and, when the request carried
 * {@code aiCaptionsSeconds}, what the AI captions add. Both numbers are the server's.
 */
public final class ChargePreview {

    /** Credits the publish costs; 0 means free. */
    public final int amount;
    /** Credits for AI captions for the length asked about; 0 when free, absent or not asked. */
    public final int aiCaptions;

    public ChargePreview(int amount, int aiCaptions) {
        this.amount = Math.max(0, amount);
        this.aiCaptions = Math.max(0, aiCaptions);
    }

    /** {@code amount} must be present; {@code aiCaptions} is read leniently (an older server omits it). */
    public static ChargePreview parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        if (!o.has("amount")) throw new JSONException("no amount");
        return new ChargePreview(o.getInt("amount"), o.optInt("aiCaptions", 0));
    }
}
