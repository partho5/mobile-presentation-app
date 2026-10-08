package com.jovoc.facecampresentationrecorder.account;

import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthException;

/** Why a workspace / balance / devices call failed, in terms the UI can show. */
public class AccountException extends Exception {

    public enum Kind {
        /** No connection, DNS failure, timeout. */
        NETWORK,
        /** The session is gone (revoked, refresh refused, or never signed in). */
        SIGNED_OUT,
        /** 429. */
        RATE_LIMITED,
        /** 403 WORKSPACE_FORBIDDEN: the picked workspace is no longer the user's. */
        WORKSPACE_FORBIDDEN,
        /** 404: the thing (a device session) is not there any more. */
        NOT_FOUND,
        /** Anything else: 5xx, other 4xx, an unreadable answer. */
        SERVER
    }

    public final Kind kind;
    public final int retryAfterSeconds;

    public AccountException(Kind kind) {
        this(kind, -1);
    }

    public AccountException(Kind kind, int retryAfterSeconds) {
        super(kind.name());
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static AccountException from(AuthException e) {
        switch (e.kind) {
            case NETWORK:
                return new AccountException(Kind.NETWORK);
            case RATE_LIMITED:
                return new AccountException(Kind.RATE_LIMITED, e.retryAfterSeconds);
            case SESSION_ENDED:
            case NOT_SIGNED_IN:
                return new AccountException(Kind.SIGNED_OUT);
            default:
                return new AccountException(Kind.SERVER);
        }
    }

    /** Interprets a non-2xx answer. */
    public static AccountException from(ApiResponse r) {
        if (r.status == 401) return new AccountException(Kind.SIGNED_OUT);
        if (r.status == 429) return new AccountException(Kind.RATE_LIMITED, r.retryAfterSeconds);
        if (r.is(403, ApiResponse.WORKSPACE_FORBIDDEN)) return new AccountException(Kind.WORKSPACE_FORBIDDEN);
        if (r.status == 404) return new AccountException(Kind.NOT_FOUND);
        return new AccountException(Kind.SERVER);
    }
}
