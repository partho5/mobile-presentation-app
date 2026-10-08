package com.jovoc.facecampresentationrecorder.auth;

/** Why an auth call failed, in terms the UI can show. Never carries a token or code. */
public class AuthException extends Exception {

    public enum Kind {
        /** No connection, DNS failure, timeout. */
        NETWORK,
        /** 400: not an email / not six digits. */
        BAD_INPUT,
        /** 401 INVALID_GRANT on a code: wrong, expired, used or dead (the backend does not say which). */
        INVALID_CODE,
        /** 429 AUTH_RATE_LIMITED. */
        RATE_LIMITED,
        /** 503 MAIL_DELIVERY_FAILED. */
        MAIL_FAILED,
        /** The session is gone (revoked, or refresh refused). The store has been cleared. */
        SESSION_ENDED,
        /** The app has no valid session to call with. */
        NOT_SIGNED_IN,
        /** Anything else (5xx, unexpected body). */
        SERVER
    }

    public final Kind kind;
    /** Seconds to wait before retrying (RATE_LIMITED), or -1. */
    public final int retryAfterSeconds;

    public AuthException(Kind kind) {
        this(kind, -1, null);
    }

    public AuthException(Kind kind, int retryAfterSeconds, Throwable cause) {
        super(kind.name(), cause);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
