package com.jovoc.facecampresentationrecorder.entitlements;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

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

    /** A 200 answer: the token as sent (not yet verified) and the catalogue object as JSON text, or null. */
    static final class Fetched {
        final String token;
        final String catalogueJson;

        Fetched(String token, String catalogueJson) {
            this.token = token;
            this.catalogueJson = catalogueJson;
        }
    }

    private final ApiClient client;
    private final AuthRepository auth;

    /** {@code auth} may be null: every call is then anonymous. */
    EntitlementsApi(ApiClient client, AuthRepository auth) {
        this.client = client;
        this.auth = auth;
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
            headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + access);
            workspace = auth.store().workspaceId();
            if (workspace != null) headers.put(AuthRepository.HEADER_WORKSPACE, workspace);
        }
        try {
            ApiResponse r = client.send("GET", PATH, null, headers);
            // As in AuthRepository.execute: a workspace the account lost is dropped, or every later
            // fetch would be refused the same way and the token would run out.
            if (workspace != null && r.is(403, ApiResponse.WORKSPACE_FORBIDDEN)
                    && workspace.equals(auth.store().workspaceId())) {
                auth.store().clearWorkspace();
            }
            return parse(r);
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
            return new Fetched((String) token, catalogue == null ? null : catalogue.toString());
        } catch (JSONException e) {
            return null;
        }
    }
}
