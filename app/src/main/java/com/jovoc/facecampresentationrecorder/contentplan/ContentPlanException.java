package com.jovoc.facecampresentationrecorder.contentplan;

import com.jovoc.facecampresentationrecorder.auth.ApiResponse;

/**
 * Why a content plan call failed, as one of our own codes (never the server's text). {@link #retryable} says whether
 * the same call may work later without any change.
 */
public final class ContentPlanException extends Exception {

    public static final String NETWORK = "NETWORK";
    public static final String SIGNED_OUT = "SIGNED_OUT";
    /** 404: the server does not know the day. */
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String SERVER = "SERVER";
    /** Any other refusal of the server; a retry would be refused the same way. */
    public static final String REJECTED = "REJECTED";

    public final String code;
    public final boolean retryable;

    public ContentPlanException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public ContentPlanException(String code, boolean retryable, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.retryable = retryable;
    }

    /** The failure of a non-2xx answer of the {@code /content-plan} routes. */
    static ContentPlanException from(ApiResponse r) {
        if (r.status == 401) return new ContentPlanException(SIGNED_OUT, false);
        if (r.status == 404) return new ContentPlanException(NOT_FOUND, false);
        if (r.status == 429) return new ContentPlanException(RATE_LIMITED, true);
        if (r.status >= 500) return new ContentPlanException(SERVER, true);
        return new ContentPlanException(REJECTED, false);
    }
}
