package com.jovoc.facecampresentationrecorder.auth;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Sign-in, refresh and sign-out against the backend token endpoints, plus {@link #execute} for
 * authenticated calls (M2/M3 use it). All methods block; call them off the main thread.
 *
 * Contracts: api.email-otp, api.token-auth, api.mobile-access.
 */
public class AuthRepository {

    static final String PATH_OTP_REQUEST = "/auth/otp/request";
    static final String PATH_TOKEN = "/auth/token";
    static final String PATH_REVOKE = "/auth/token/revoke";
    static final int MAX_DEVICE_NAME = 100;
    public static final String HEADER_WORKSPACE = "x-workspace-id";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE = Pattern.compile("^\\d{6}$");

    private final ApiClient api;
    private final TokenStore store;
    private final LongSupplier clock;
    private final Object refreshLock = new Object();
    private volatile Runnable forcedSignOutListener;

    public AuthRepository(ApiClient api, TokenStore store) {
        this(api, store, System::currentTimeMillis);
    }

    public AuthRepository(ApiClient api, TokenStore store, LongSupplier clock) {
        this.api = api;
        this.store = store;
        this.clock = clock;
    }

    /** Called (on the calling thread) when the session ended without the user asking. */
    public void setForcedSignOutListener(Runnable listener) {
        this.forcedSignOutListener = listener;
    }

    public TokenStore store() {
        return store;
    }

    // ---- input rules (the backend lower-cases and trims; we do the same) ----

    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidEmail(String email) {
        return email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    /** Strips everything but digits and keeps the first six (paste-friendly). */
    public static String sanitizeCode(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 6; i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    public static boolean isValidCode(String code) {
        return CODE.matcher(code).matches();
    }

    // ---- sign in ----

    /** POST /auth/otp/request. Same outcome for known and new addresses. */
    public void requestCode(String rawEmail) throws AuthException {
        String email = normalizeEmail(rawEmail);
        if (!isValidEmail(email)) throw new AuthException(AuthException.Kind.BAD_INPUT);
        ApiResponse r = call(() -> api.postJson(PATH_OTP_REQUEST, obj("email", email).toString()));
        if (r.isSuccess()) return;
        throw mapSignInFailure(r);
    }

    /**
     * POST /auth/token with the email_otp grant. Stores the session.
     * @return true when the backend created a new account (header onboarding: true)
     */
    public boolean exchangeCode(String rawEmail, String rawCode, String deviceName) throws AuthException {
        String email = normalizeEmail(rawEmail);
        String code = sanitizeCode(rawCode);
        if (!isValidEmail(email) || !isValidCode(code)) {
            throw new AuthException(AuthException.Kind.BAD_INPUT);
        }
        String device = clampDeviceName(deviceName);
        JSONObject body = obj("grant_type", "email_otp");
        put(body, "email", email);
        put(body, "code", code);
        put(body, "device_name", device);
        ApiResponse r = call(() -> api.postJson(PATH_TOKEN, body.toString()));
        if (!r.isSuccess()) throw mapSignInFailure(r);
        saveTokenPair(r);
        store.saveIdentity(email, device);
        return r.onboarding;
    }

    // ---- refresh ----

    /**
     * Renews the pair. {@code staleAccess} is the access token the caller just used (or saw):
     * if another thread already replaced it, nothing is sent. Concurrent callers therefore
     * produce one refresh request.
     */
    public void refreshIfStale(String staleAccess) throws AuthException {
        refreshIfStale(staleAccess, false);
    }

    /**
     * An access token that is valid now, for a background call that must not disturb the user
     * (the activity log upload). Refreshes when needed, but a refresh the server refuses neither
     * clears the session nor fires the forced-sign-out listener: the user's next real call does
     * that. Null when there is no session or no token could be had.
     */
    public String quietAccessToken() {
        if (!store.hasSession()) return null;
        try {
            String access = store.accessToken();
            if (store.accessNeedsRefresh(clock.getAsLong())) {
                refreshIfStale(access, true);
                access = store.accessToken();
            }
            return access == null || access.isEmpty() ? null : access;
        } catch (AuthException e) {
            return null;
        }
    }

    /** {@code quiet}: a refused refresh leaves the session as it is (see {@link #quietAccessToken}). */
    private void refreshIfStale(String staleAccess, boolean quiet) throws AuthException {
        synchronized (refreshLock) {
            String refresh = store.refreshToken();
            if (refresh == null || refresh.isEmpty()) {
                throw new AuthException(AuthException.Kind.NOT_SIGNED_IN);
            }
            if (!Objects.equals(staleAccess, store.accessToken())) return;

            JSONObject body = obj("grant_type", "refresh_token");
            put(body, "refresh_token", refresh);
            ApiResponse r = call(() -> api.postJson(PATH_TOKEN, body.toString()));
            if (r.isSuccess()) {
                saveTokenPair(r);
                return;
            }
            if (r.status == 401) {
                if (!quiet) endSession(true);
                throw new AuthException(AuthException.Kind.SESSION_ENDED);
            }
            if (r.status == 429) {
                throw new AuthException(AuthException.Kind.RATE_LIMITED, r.retryAfterSeconds, null);
            }
            throw new AuthException(AuthException.Kind.SERVER);
        }
    }

    // ---- authenticated calls ----

    /**
     * Sends an authenticated request. Refreshes first when the access token is about to expire,
     * and once more (then retries once, same headers) on 401 TOKEN_EXPIRED. 401 SESSION_REVOKED
     * (or 401 {"msg":"Invalid API key"}) clears the session, fires the forced-sign-out listener and throws SESSION_ENDED. Any other
     * response, including errors, is returned for the caller to interpret.
     */
    public ApiResponse execute(String method, String path, String jsonBody, Map<String, String> headers)
            throws AuthException {
        return execute(method, path, jsonBody, headers, true);
    }

    /**
     * Like {@link #execute(String, String, String, Map)}. {@code withWorkspace} adds the
     * {@code x-workspace-id} of the workspace picked on this device (unless {@code headers}
     * already names one). A 403 WORKSPACE_FORBIDDEN for the stored workspace drops the pick, so
     * the next call uses the server's default; the 403 is still returned to the caller.
     */
    public ApiResponse execute(String method, String path, String jsonBody, Map<String, String> headers,
                               boolean withWorkspace) throws AuthException {
        Map<String, String> h = withWorkspace ? withWorkspaceHeader(headers) : headers;
        ApiResponse r = executeAuthed(access -> api.send(method, path, jsonBody, withBearer(access, h)));
        return noteWorkspaceResult(r, h);
    }

    /** One authenticated attempt with the given access token. */
    public interface Sender {
        ApiResponse send(String accessToken) throws IOException;
    }

    /**
     * Same refresh / retry / forced-sign-out rules as {@link #execute}, for requests that are not
     * plain JSON (multipart uploads). The sender must be repeatable: it runs twice after an expiry.
     */
    public ApiResponse executeAuthed(Sender sender) throws AuthException {
        if (!store.hasSession()) throw new AuthException(AuthException.Kind.NOT_SIGNED_IN);

        String access = store.accessToken();
        if (store.accessNeedsRefresh(clock.getAsLong())) {
            refreshIfStale(access);
            access = store.accessToken();
        }
        final String first = access;
        ApiResponse r = call(() -> sender.send(first));
        if (r.is(401, ApiResponse.TOKEN_EXPIRED)) {
            refreshIfStale(access);
            final String renewed = store.accessToken();
            r = call(() -> sender.send(renewed));
        }
        if (r.is(401, ApiResponse.SESSION_REVOKED) || isInvalidApiKey(r)) {
            endSession(true);
            throw new AuthException(AuthException.Kind.SESSION_ENDED);
        }
        return r;
    }

    /** 401 {"msg":"Invalid API key"}: the token is not valid (or its user is gone); sign out. */
    private static boolean isInvalidApiKey(ApiResponse r) {
        if (r.status != 401 || r.errorCode != null || r.body.isEmpty() || r.body.charAt(0) != '{') {
            return false;
        }
        try {
            return "Invalid API key".equals(r.json().optString("msg", null));
        } catch (JSONException e) {
            return false;
        }
    }

    /** Multipart upload with the access token; see {@link ApiClient#upload}. */
    public ApiResponse executeUpload(String path, String fieldName, File file, String mime,
                                     ApiClient.ProgressListener progress, BooleanSupplier cancelled)
            throws AuthException {
        return executeUpload(path, fieldName, file, mime, progress, cancelled, null);
    }

    /** As above; {@code headers} may name a workspace (a queued job keeps the one it was made in). */
    public ApiResponse executeUpload(String path, String fieldName, File file, String mime,
                                     ApiClient.ProgressListener progress, BooleanSupplier cancelled,
                                     Map<String, String> headers) throws AuthException {
        Map<String, String> h = withWorkspaceHeader(headers);
        ApiResponse r = executeAuthed(access -> api.upload(path, fieldName, file, mime,
                withBearer(access, h), progress, cancelled));
        return noteWorkspaceResult(r, h);
    }

    private Map<String, String> withWorkspaceHeader(Map<String, String> headers) {
        Map<String, String> h = new HashMap<>();
        if (headers != null) h.putAll(headers);
        String workspace = store.workspaceId();
        if (workspace != null && !h.containsKey(HEADER_WORKSPACE)) h.put(HEADER_WORKSPACE, workspace);
        return h;
    }

    private ApiResponse noteWorkspaceResult(ApiResponse r, Map<String, String> sentHeaders) {
        if (r.is(403, ApiResponse.WORKSPACE_FORBIDDEN)) {
            String sent = sentHeaders == null ? null : sentHeaders.get(HEADER_WORKSPACE);
            if (sent != null && sent.equals(store.workspaceId())) store.clearWorkspace();
        }
        return r;
    }

    private static Map<String, String> withBearer(String access, Map<String, String> headers) {
        Map<String, String> h = new HashMap<>();
        if (headers != null) h.putAll(headers);
        h.put("Authorization", "Bearer " + access);
        return h;
    }

    // ---- sign out ----

    /**
     * Ends the session on this device. Tries to revoke it on the server first (best effort);
     * the local session is always cleared.
     * @return true if the server confirmed the revoke, false if it could not be reached
     */
    public boolean signOut() {
        String refresh = store.refreshToken();
        boolean revoked = refresh == null || refresh.isEmpty();
        if (!revoked) {
            try {
                ApiResponse r = api.postJson(PATH_REVOKE, obj("refresh_token", refresh).toString());
                revoked = r.isSuccess();
            } catch (IOException ignored) {
                // offline: still sign out locally
            }
        }
        store.clear();
        return revoked;
    }

    /** Clears the local session without telling the server (it already ended this session). */
    public void endLocalSession() {
        store.clear();
    }

    // ---- internals ----

    private void endSession(boolean forced) {
        store.clear();
        Runnable l = forcedSignOutListener;
        if (forced && l != null) l.run();
    }

    private void saveTokenPair(ApiResponse r) throws AuthException {
        try {
            JSONObject j = r.json();
            String access = j.getString("access_token");
            String refresh = j.getString("refresh_token");
            String sid = j.optString("session_id", "");
            long expiresIn = j.optLong("expires_in", 900L);
            if (access.isEmpty() || refresh.isEmpty()) throw new JSONException("empty token");
            store.saveTokens(access, refresh, sid, expiresIn, clock.getAsLong());
        } catch (JSONException e) {
            throw new AuthException(AuthException.Kind.SERVER, -1, e);
        }
    }

    private static AuthException mapSignInFailure(ApiResponse r) {
        switch (r.status) {
            case 400:
                return new AuthException(AuthException.Kind.BAD_INPUT);
            case 401:
                return new AuthException(AuthException.Kind.INVALID_CODE);
            case 429:
                return new AuthException(AuthException.Kind.RATE_LIMITED, r.retryAfterSeconds, null);
            case 503:
                return new AuthException(AuthException.Kind.MAIL_FAILED);
            default:
                return new AuthException(AuthException.Kind.SERVER);
        }
    }

    private interface Net {
        ApiResponse run() throws IOException;
    }

    private static ApiResponse call(Net net) throws AuthException {
        try {
            return net.run();
        } catch (IOException e) {
            throw new AuthException(AuthException.Kind.NETWORK, -1, e);
        }
    }

    static String clampDeviceName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "Android";
        return n.length() > MAX_DEVICE_NAME ? n.substring(0, MAX_DEVICE_NAME) : n;
    }

    private static JSONObject obj(String k, String v) {
        JSONObject o = new JSONObject();
        put(o, k, v);
        return o;
    }

    private static void put(JSONObject o, String k, String v) {
        try {
            o.put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
