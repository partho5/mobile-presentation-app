package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthException;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Why a publish step failed, with the two facts the pipeline needs: whether trying again can
 * help, and whether the server has already given a final (stored) answer for the request key.
 */
public class PublishException extends Exception {

    public enum Kind {
        /** No connection, timeout, or the connection dropped mid-request. */
        NETWORK,
        /** The session is gone or the token is not valid. */
        SIGNED_OUT,
        /** 402: the workspace cannot afford the post. Nothing was created. */
        INSUFFICIENT_CREDITS,
        /** 4xx with a readable reason from the server (validation, forbidden, ...). */
        REJECTED,
        /** 403 WORKSPACE_FORBIDDEN: the user is no longer a member of the job's workspace. */
        WORKSPACE_FORBIDDEN,
        /** 413 / over the 1 GB video cap. */
        TOO_LARGE,
        /** 429 (too many requests). */
        RATE_LIMITED,
        /** 409 IDEMPOTENCY_IN_PROGRESS: the same publish is still being handled. */
        IN_PROGRESS,
        /** 5xx or an unreadable answer. */
        SERVER,
        /** The recording file is missing or empty. */
        FILE,
        /** The user (or the system) stopped the job. */
        CANCELLED,
        /** Auto-publish: no preset on the web (or no usable channel in it). {@code detail} is the setup link, if known. */
        AUTO_NOT_CONFIGURED,
        /** Auto-publish pre-flight: the balance does not cover the cost. {@code detail} is "cost|available". */
        CREDITS_SHORT,
        /** 404 MEDIA_NOT_FOUND: the server no longer has the uploaded video; it must be uploaded again. */
        MEDIA_NOT_FOUND
    }

    public final Kind kind;
    public final int status;
    /** Readable server message when there is one (REJECTED, INSUFFICIENT_CREDITS), else null. */
    public final String detail;
    public final int retryAfterSeconds;

    public PublishException(Kind kind, int status, String detail, int retryAfterSeconds) {
        super(kind.name());
        this.kind = kind;
        this.status = status;
        this.detail = detail;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public PublishException(Kind kind) {
        this(kind, 0, null, -1);
    }

    /** True when the same request may succeed later without any change. */
    public boolean isRetryable() {
        return kind == Kind.NETWORK || kind == Kind.RATE_LIMITED || kind == Kind.IN_PROGRESS
                || kind == Kind.SERVER;
    }

    /**
     * True when the server answered definitively with a 4xx: it stores that answer against the
     * Idempotency-Key for 24 h and would replay it, so the next attempt needs a new key.
     * 5xx, dropped connections, 429 and 409-in-progress are never stored. Neither is a 401: the
     * auth layer rejected the request before the idempotency middleware stored anything, so the
     * key must survive the sign-in, or a Retry after an unknown-outcome post could post twice.
     */
    public boolean spendsKey() {
        return status >= 400 && status < 500 && kind != Kind.RATE_LIMITED && kind != Kind.IN_PROGRESS
                && kind != Kind.SIGNED_OUT;
    }

    // ---- mapping ----

    public static PublishException from(AuthException e) {
        switch (e.kind) {
            case NETWORK:
                return new PublishException(Kind.NETWORK);
            case RATE_LIMITED:
                return new PublishException(Kind.RATE_LIMITED, 429, null, e.retryAfterSeconds);
            case SESSION_ENDED:
            case NOT_SIGNED_IN:
                return new PublishException(Kind.SIGNED_OUT);
            default:
                return new PublishException(Kind.SERVER);
        }
    }

    /** Interprets a non-2xx answer. */
    public static PublishException from(ApiResponse r) {
        String message = readMessage(r.body);
        if (r.status == 402 && "INSUFFICIENT_CREDITS".equals(r.errorCode)) {
            return new PublishException(Kind.INSUFFICIENT_CREDITS, 402, message, -1);
        }
        if (r.is(403, ApiResponse.WORKSPACE_FORBIDDEN)) {
            return new PublishException(Kind.WORKSPACE_FORBIDDEN, 403, null, -1);
        }
        if (r.status == 413) return new PublishException(Kind.TOO_LARGE, 413, message, -1);
        if (r.status == 429) {
            return new PublishException(Kind.RATE_LIMITED, 429, null, r.retryAfterSeconds);
        }
        if (r.status == 409 && "IDEMPOTENCY_IN_PROGRESS".equals(r.errorCode)) {
            return new PublishException(Kind.IN_PROGRESS, 409, null, -1);
        }
        if (r.is(409, "AUTO_PUBLISH_NOT_CONFIGURED")) {
            return new PublishException(Kind.AUTO_NOT_CONFIGURED, 409, null, -1);
        }
        if (r.is(404, "MEDIA_NOT_FOUND")) {
            return new PublishException(Kind.MEDIA_NOT_FOUND, 404, null, -1);
        }
        if (r.status == 401) return new PublishException(Kind.SIGNED_OUT, 401, null, -1);
        if (r.status >= 400 && r.status < 500) {
            return new PublishException(Kind.REJECTED, r.status, message, -1);
        }
        return new PublishException(Kind.SERVER, r.status, null, -1);
    }

    /** Pulls a readable text out of {message}, {message:[...]} or {msg}; null if there is none. */
    static String readMessage(String body) {
        if (body == null || body.isEmpty() || body.charAt(0) != '{') return null;
        try {
            JSONObject o = new JSONObject(body);
            Object m = o.opt("message");
            if (m instanceof String && !((String) m).isEmpty()) return (String) m;
            if (m instanceof JSONArray && ((JSONArray) m).length() > 0) {
                return ((JSONArray) m).optString(0, null);
            }
            String msg = o.optString("msg", "");
            return msg.isEmpty() ? null : msg;
        } catch (Exception e) {
            return null;
        }
    }
}
