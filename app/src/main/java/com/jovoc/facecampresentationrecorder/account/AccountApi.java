package com.jovoc.facecampresentationrecorder.account;

import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;

import org.json.JSONException;

import java.util.List;

/**
 * Workspace list, credit balance and device sessions. Blocking: call off the main thread.
 * Every failure is an {@link AccountException}; the session itself is refreshed / ended by
 * {@link AuthRepository}.
 */
public class AccountApi {

    public static final String PATH_WORKSPACES = "/user/organizations";
    public static final String PATH_SELF = "/user/self";
    /** Declared by ux.balance-header; not built in the backend yet (see WORKSPACE_PLAN.md). */
    public static final String PATH_BALANCE = "/credits/balance";
    /** Exists today: {credits} = available only. */
    public static final String PATH_COPILOT_CREDITS = "/copilot/credits";
    public static final String PATH_SESSIONS = "/auth/sessions";
    /**
     * TODO(DELETE-ACCOUNT): placeholder route. The backend's account-deletion contract is not
     * written yet (PROD_AUTH_BACKEND_PROMPT.md item 4); change this one constant to match it.
     */
    public static final String PATH_DELETE_ACCOUNT = "/auth/account";

    /** Remembered for the process so a backend without /credits/balance costs one probe, not one per screen. */
    private static volatile boolean balanceRouteMissing;

    private final AuthRepository repo;

    public AccountApi(AuthRepository repo) {
        this.repo = repo;
    }

    /** Forget that /credits/balance was missing (used when the backend URL changes, and by tests). */
    public static void resetBalanceProbe() {
        balanceRouteMissing = false;
    }

    // ---- workspaces ----

    /**
     * GET /user/organizations. Sent without the picked workspace: the list must stay reachable
     * even when the pick is stale.
     */
    public List<Workspace> listWorkspaces() throws AccountException {
        ApiResponse r = authed("GET", PATH_WORKSPACES, false);
        try {
            return Workspace.parseList(r.body);
        } catch (JSONException e) {
            throw new AccountException(AccountException.Kind.SERVER);
        }
    }

    /** GET /user/self: the id of the workspace the server resolves for this device right now. */
    public String currentWorkspaceId() throws AccountException {
        ApiResponse r = authed("GET", PATH_SELF, true);
        try {
            String id = r.json().optString("orgId", "");
            if (id.isEmpty()) throw new JSONException("no orgId");
            return id;
        } catch (JSONException e) {
            throw new AccountException(AccountException.Kind.SERVER);
        }
    }

    // ---- credits ----

    /**
     * The current workspace's balance. Uses the contract route when the backend has it, else the
     * live {@code /copilot/credits} (available only).
     */
    public CreditBalance balance() throws AccountException {
        try {
            if (!balanceRouteMissing) {
                ApiResponse r = call("GET", PATH_BALANCE, true);
                if (r.isSuccess()) return CreditBalance.parseBalanceView(r.body);
                if (r.status != 404) throw AccountException.from(r);
                balanceRouteMissing = true;
            }
            ApiResponse r = authed("GET", PATH_COPILOT_CREDITS, true);
            return CreditBalance.parseCopilotCredits(r.body);
        } catch (JSONException e) {
            throw new AccountException(AccountException.Kind.SERVER);
        }
    }

    // ---- devices (never sent with a workspace: api.device-sessions ignores it) ----

    public List<DeviceSession> listDevices() throws AccountException {
        ApiResponse r = authed("GET", PATH_SESSIONS, false);
        try {
            return DeviceSession.parseList(r.body);
        } catch (JSONException e) {
            throw new AccountException(AccountException.Kind.SERVER);
        }
    }

    /** DELETE /auth/sessions/:id. 404 (already gone) counts as done. */
    public void revokeDevice(String sessionId) throws AccountException {
        if (!isSafeId(sessionId)) throw new AccountException(AccountException.Kind.NOT_FOUND);
        ApiResponse r = call("DELETE", PATH_SESSIONS + "/" + sessionId, false);
        if (r.isSuccess() || r.status == 404) return;
        throw AccountException.from(r);
    }

    /** DELETE /auth/sessions?except=current. */
    public void revokeOtherDevices() throws AccountException {
        authed("DELETE", PATH_SESSIONS + "?except=current", false);
    }

    // ---- account deletion ----

    /**
     * DELETE the signed-in account and its data on the server (Bearer, no workspace). On success
     * the server has ended every session of the account; the caller clears the local one. A
     * missing route (404/405/501) is {@link AccountException.Kind#UNAVAILABLE}: nothing deleted.
     */
    public void deleteAccount() throws AccountException {
        ApiResponse r = call("DELETE", PATH_DELETE_ACCOUNT, false);
        if (r.isSuccess()) return;
        if (r.status == 404 || r.status == 405 || r.status == 501) {
            throw new AccountException(AccountException.Kind.UNAVAILABLE);
        }
        throw AccountException.from(r);
    }

    // ---- internals ----

    /** Sends the request and returns it only if it succeeded. */
    private ApiResponse authed(String method, String path, boolean withWorkspace) throws AccountException {
        ApiResponse r = call(method, path, withWorkspace);
        if (!r.isSuccess()) throw AccountException.from(r);
        return r;
    }

    private ApiResponse call(String method, String path, boolean withWorkspace) throws AccountException {
        try {
            return repo.execute(method, path, null, null, withWorkspace);
        } catch (AuthException e) {
            throw AccountException.from(e);
        }
    }

    /** Session ids are UUIDs; anything else must not be able to change the request path. */
    static boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }
}
