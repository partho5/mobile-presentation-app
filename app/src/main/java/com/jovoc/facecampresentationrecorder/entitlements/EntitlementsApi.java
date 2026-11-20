package com.jovoc.facecampresentationrecorder.entitlements;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code GET /entitlements}: the signed plan token and the price list. Signed in, the call carries
 * the access token from {@link AuthRepository#quietAccessToken()} (a dead session never shows
 * "session ended" from here) and the picked workspace; signed out it goes anonymously. Blocking:
 * call off the main thread.
 */
final class EntitlementsApi {

    static final String PATH = "/entitlements";

    /**
     * A 200 answer: the token as sent (not yet verified), the catalogue object as JSON text or null,
     * and the brand the request was sent for (null: none picked, the server's default).
     */
    static final class Fetched {
        final String token;
        final String catalogueJson;
        final String workspaceId;

        Fetched(String token, String catalogueJson, String workspaceId) {
            this.token = token;
            this.catalogueJson = catalogueJson;
            this.workspaceId = workspaceId;
        }
    }

    private final ApiClient client;
    private final AuthRepository auth;

    /** {@code auth} may be null: every call is then anonymous. */
    EntitlementsApi(ApiClient client, AuthRepository auth) {
        this.client = client;
        this.auth = auth;
    }

    /** Stands for a brand that refused this phone: no token is ever fetched or kept for it. */
    static final String LOST_BRAND = "!lost";

    /**
     * The brand a plan request is sent for, and the brand a token must have been fetched for: the
     * stored pick while signed in, else null (an anonymous call carries no brand). A brand whose
     * access is lost counts as {@link #LOST_BRAND}, so the plan it had no longer counts.
     */
    static String brandInUse(TokenStore store) {
        if (!store.hasSession()) return null;
        if (store.workspaceAccessLost()) return LOST_BRAND;
        return store.workspaceId();
    }

    static EntitlementsApi create(Context context) {
        return new EntitlementsApi(new ApiClient(BuildConfig.API_BASE_URL, BuildConfig.DEBUG),
                SessionManager.get(context).repository());
    }

    /**
     * Asks the server for the current token. Null means "keep what is stored": no usable access
     * token for a signed-in phone, offline, any status but 200 (401 / 403 / 429 / 5xx never
     * downgrade the plan) or an answer without a token.
     */
    Fetched fetch() {
        Map<String, String> headers = null;
        String workspace = null;
        if (auth != null && auth.store().hasSession()) {
            String access = auth.quietAccessToken();
            if (access == null) return null;
            // The brand in use (pinned now if none was yet). No call goes out without one, and a brand
            // that has lost access asks for no plan: its plan stays off until a choice is made.
            try {
                workspace = auth.brandForCall();
            } catch (AuthException e) {
                return null;
            }
            if (auth.store().workspaceAccessLost()) return null;
            headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + access);
            if (workspace != null) headers.put(AuthRepository.HEADER_WORKSPACE, workspace);
        }
        try {
            ApiResponse r = client.send("GET", PATH, null, headers);
            // As in AuthRepository.execute: a 403 for the picked brand marks its access lost; the pick stays.
            if (workspace != null && r.is(403, ApiResponse.WORKSPACE_FORBIDDEN)
                    && workspace.equals(auth.store().workspaceId())) {
                auth.store().markWorkspaceAccessLost();
            }
            Fetched f = parse(r);
            return f == null ? null : new Fetched(f.token, f.catalogueJson, workspace);
        } catch (IOException e) {
            return null;
        }
    }

    static Fetched parse(ApiResponse r) {
        if (r.status != 200) return null;
        try {
            JSONObject j = r.json();
            Object token = j.opt("token");
            if (!(token instanceof String) || ((String) token).isEmpty()) return null;
            JSONObject catalogue = j.optJSONObject("catalogue");
            return new Fetched((String) token, catalogue == null ? null : catalogue.toString(), null);
        } catch (JSONException e) {
            return null;
        }
    }
}
