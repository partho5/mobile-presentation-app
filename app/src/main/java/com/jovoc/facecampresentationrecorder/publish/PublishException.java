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
    /**
     * RATE_LIMITED only: true for the API throttler's 429. The server stores that answer under
     * the Idempotency-Key for 24 h (and replays it without Retry-After), so the key must be
     * replaced before the next attempt. The auth layer's 429 (AUTH_RATE_LIMITED) is not stored.
     */
    private final boolean storedAnswer;

    public PublishException(Kind kind, int status, String detail, int retryAfterSeconds) {
        this(kind, status, detail, retryAfterSeconds, false);
    }

    private PublishException(Kind kind, int status, String detail, int retryAfterSeconds,
                             boolean storedAnswer) {
        super(kind.name());
        this.kind = kind;
        this.status = status;
        this.detail = detail;
        this.retryAfterSeconds = retryAfterSeconds;
        this.storedAnswer = storedAnswer;
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
     * 5xx, dropped connections and 409-in-progress are never stored. Neither is a 401, nor the
     * auth layer's 429 (AUTH_RATE_LIMITED): they are rejected before the idempotency middleware
     * stores anything, so the key must survive, or a Retry after an unknown-outcome post could
     * post twice. The API throttler's 429 is different: nothing was created, but the answer IS
     * stored, so a retry with the same key only replays it; it needs a new key.
     */
    public boolean spendsKey() {
        if (kind == Kind.RATE_LIMITED) return storedAnswer;
        return status >= 400 && status < 500 && kind != Kind.IN_PROGRESS && kind != Kind.SIGNED_OUT;
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
            boolean throttled = !ApiResponse.AUTH_RATE_LIMITED.equals(r.errorCode)
                    && message != null && message.contains("Throttler");
            return new PublishException(Kind.RATE_LIMITED, 429, null, r.retryAfterSeconds, throttled);
        }
        if (r.status == 400 && "File is too large.".equals(message)) {
            return new PublishException(Kind.TOO_LARGE, 400, message, -1);
        }
        if (r.status == 400 && "Failed to read file".equals(message)) {
            // The upload stream could not be read: worth another try (the key is not in use yet).
            return new PublishException(Kind.SERVER, 400, null, -1);
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
            return new PublishException(Kind.REJECTED, r.status, withChannelName(r.body, message), -1);
        }
        return new PublishException(Kind.SERVER, r.status, null, -1);
    }

    /**
     * A dashboard validation answer {statusCode, provider, name, message} names the channel it
     * is about; with several channels the user needs to know which one to fix on the web.
     */
    static String withChannelName(String body, String message) {
        if (message == null || body == null || body.isEmpty() || body.charAt(0) != '{') return message;
        try {
            JSONObject o = new JSONObject(body);
            if (!o.has("provider")) return message;
            String name = o.optString("name", "").trim();
            if (name.isEmpty() || message.contains(name)) return message;
            return name + ": " + message;
        } catch (Exception e) {
            return message;
        }
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
