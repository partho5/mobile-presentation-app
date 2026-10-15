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
        MEDIA_NOT_FOUND,

        // ---- direct upload (/upload/multipart*): none of these involves an Idempotency-Key ----

        /** 400 UNSUPPORTED_TYPE: the file is not an allowed video type. Final. */
        UNSUPPORTED_TYPE,
        /** 400 FILE_TOO_LARGE: over the server's upload cap. Final. */
        FILE_TOO_LARGE,
        /** 400 SIZE_MISMATCH: the stored object is not the declared size; the upload is aborted. Final for it. */
        SIZE_MISMATCH,
        /** 400 CONTENT_TYPE_MISMATCH: the bytes are not the declared type; the upload is aborted. Final. */
        CONTENT_TYPE_MISMATCH,
        /** 429 TOO_MANY_PENDING_UPLOADS: 5 unfinished uploads in the workspace; wait or free some. */
        TOO_MANY_PENDING_UPLOADS,
        /** 502 STORAGE_ERROR: R2 failed; the upload stays pending and the same call is safe to repeat. */
        STORAGE_ERROR,
        /** 404 UPLOAD_NOT_FOUND: no such upload in this workspace; create a new one. */
        UPLOAD_NOT_FOUND,
        /** 409 UPLOAD_NOT_PENDING: ask for the status; completed replays, aborted/expired starts over. */
        UPLOAD_NOT_PENDING,
        /** 400 INVALID_PARTS: a part is missing or refused; re-upload it and complete again. */
        INVALID_PARTS,
        /** 400 INVALID_POSTER: the poster was refused, nothing was claimed; resend without it. */
        INVALID_POSTER
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
    /**
     * True for a failure of the upload step: the post's Idempotency-Key has not been used yet,
     * so no answer of an upload call may spend it (see {@link #forUploadStep}).
     */
    private final boolean uploadStep;
    /**
     * True for a 404 from a direct-upload route that carries no {@code code}: the server has no
     * such route (an older deploy, or a proxy). A 404 with a code is a real answer of the routes.
     */
    private final boolean routeMissing;

    public PublishException(Kind kind, int status, String detail, int retryAfterSeconds) {
        this(kind, status, detail, retryAfterSeconds, false, false, false);
    }

    private PublishException(Kind kind, int status, String detail, int retryAfterSeconds,
                             boolean storedAnswer, boolean uploadStep, boolean routeMissing) {
        super(kind.name());
        this.kind = kind;
        this.status = status;
        this.detail = detail;
        this.retryAfterSeconds = retryAfterSeconds;
        this.storedAnswer = storedAnswer;
        this.uploadStep = uploadStep;
        this.routeMissing = routeMissing;
    }

    public PublishException(Kind kind) {
        this(kind, 0, null, -1);
    }

    /** True when the same request may succeed later without any change. */
    public boolean isRetryable() {
        return kind == Kind.NETWORK || kind == Kind.RATE_LIMITED || kind == Kind.IN_PROGRESS
                || kind == Kind.SERVER || kind == Kind.TOO_MANY_PENDING_UPLOADS
                || kind == Kind.STORAGE_ERROR;
    }

    /** True for the failures only the direct-upload routes answer (none has an Idempotency-Key). */
    public boolean isUploadSession() {
        switch (kind) {
            case UNSUPPORTED_TYPE:
            case FILE_TOO_LARGE:
            case SIZE_MISMATCH:
            case CONTENT_TYPE_MISMATCH:
            case TOO_MANY_PENDING_UPLOADS:
            case STORAGE_ERROR:
            case UPLOAD_NOT_FOUND:
            case UPLOAD_NOT_PENDING:
            case INVALID_PARTS:
            case INVALID_POSTER:
                return true;
            default:
                return false;
        }
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
        if (uploadStep) return false;
        if (kind == Kind.RATE_LIMITED) return storedAnswer;
        if (isUploadSession()) return false;
        return status >= 400 && status < 500 && kind != Kind.IN_PROGRESS && kind != Kind.SIGNED_OUT;
    }

    /**
     * The same failure, marked as coming from the upload step. The post has not been sent, so no
     * key is stored on the server for it: {@link #spendsKey()} is false whatever the status (a
     * plain 4xx of the upload routes, such as a 404 from a server without them, must not
     * rotate the key).
     */
    public PublishException forUploadStep() {
        if (uploadStep) return this;
        return new PublishException(kind, status, detail, retryAfterSeconds, storedAnswer, true,
                routeMissing);
    }

    /**
     * True when a direct-upload route answered 404 without a {@code code} (see
     * {@link #fromUpload}): the only answer that may fall back to the legacy POST /upload.
     */
    public boolean isRouteMissing() {
        return routeMissing;
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
            return new PublishException(Kind.RATE_LIMITED, 429, null, r.retryAfterSeconds, throttled,
                    false, false);
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
     * Interprets a non-2xx answer of a direct-upload route (/upload/multipart*). These routes
     * never carry an Idempotency-Key, so nothing here spends or rotates one; answers without an
     * upload code (401, 403, 5xx, the auth layer's 429, a plain 400) are mapped as {@link #from}.
     */
    public static PublishException fromUpload(ApiResponse r) {
        String message = readMessage(r.body);
        if (r.status == 400) {
            Kind k = null;
            if ("UNSUPPORTED_TYPE".equals(r.errorCode)) k = Kind.UNSUPPORTED_TYPE;
            else if ("FILE_TOO_LARGE".equals(r.errorCode)) k = Kind.FILE_TOO_LARGE;
            else if ("SIZE_MISMATCH".equals(r.errorCode)) k = Kind.SIZE_MISMATCH;
            else if ("CONTENT_TYPE_MISMATCH".equals(r.errorCode)) k = Kind.CONTENT_TYPE_MISMATCH;
            else if ("INVALID_PARTS".equals(r.errorCode)) k = Kind.INVALID_PARTS;
            else if ("INVALID_POSTER".equals(r.errorCode)) k = Kind.INVALID_POSTER;
            if (k != null) return new PublishException(k, 400, message, -1);
        }
        if (r.is(404, "UPLOAD_NOT_FOUND")) {
            return new PublishException(Kind.UPLOAD_NOT_FOUND, 404, message, -1);
        }
        if (r.is(409, "UPLOAD_NOT_PENDING")) {
            return new PublishException(Kind.UPLOAD_NOT_PENDING, 409, message, -1);
        }
        if (r.is(429, "TOO_MANY_PENDING_UPLOADS")) {
            return new PublishException(Kind.TOO_MANY_PENDING_UPLOADS, 429, message,
                    r.retryAfterSeconds);
        }
        if (r.is(502, "STORAGE_ERROR")) {
            return new PublishException(Kind.STORAGE_ERROR, 502, message, -1);
        }
        if (r.status == 429 && !ApiResponse.AUTH_RATE_LIMITED.equals(r.errorCode)) {
            // The create throttle (API_LIMIT per hour): no key is involved, a plain retry after
            // Retry-After. Not "stored", so nothing rotates.
            return new PublishException(Kind.RATE_LIMITED, 429, null, r.retryAfterSeconds);
        }
        PublishException e = from(r);
        if (r.status == 404 && r.errorCode == null && e.kind == Kind.REJECTED) {
            return new PublishException(e.kind, e.status, e.detail, e.retryAfterSeconds, false, false,
                    true);
        }
        return e;
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
