package com.jovoc.facecampresentationrecorder.auth;

import org.json.JSONException;
import org.json.JSONObject;

/** A backend answer: status, raw body, the error {@code code} field and Retry-After. */
public final class ApiResponse {

    public static final String INVALID_GRANT = "INVALID_GRANT";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String AUTH_RATE_LIMITED = "AUTH_RATE_LIMITED";
    public static final String WORKSPACE_FORBIDDEN = "WORKSPACE_FORBIDDEN";
    /** 403 body {statusCode, code, feature, requiredPlan}: a server-run feature the plan does not include. */
    public static final String FEATURE_NOT_IN_PLAN = "FEATURE_NOT_IN_PLAN";
    public static final String MAIL_DELIVERY_FAILED = "MAIL_DELIVERY_FAILED";

    public final int status;
    public final String body;
    /** Value of the error body's {@code code} field, or null. */
    public final String errorCode;
    /** Seconds from the Retry-After header, or -1 when absent / not a number. */
    public final int retryAfterSeconds;
    /** True when the response carried header {@code onboarding: true} (account just created). */
    public final boolean onboarding;

    public ApiResponse(int status, String body, int retryAfterSeconds, boolean onboarding) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.errorCode = parseErrorCode(this.body);
        this.retryAfterSeconds = retryAfterSeconds;
        this.onboarding = onboarding;
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean is(int httpStatus, String code) {
        return status == httpStatus && code.equals(errorCode);
    }

    public JSONObject json() throws JSONException {
        return new JSONObject(body);
    }

    static String parseErrorCode(String body) {
        if (body == null || body.isEmpty() || body.charAt(0) != '{') return null;
        try {
            Object code = new JSONObject(body).opt("code");
            return code instanceof String ? (String) code : null;
        } catch (JSONException e) {
            return null;
        }
    }

    static int parseRetryAfter(String header) {
        if (header == null) return -1;
        try {
            int v = Integer.parseInt(header.trim());
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
