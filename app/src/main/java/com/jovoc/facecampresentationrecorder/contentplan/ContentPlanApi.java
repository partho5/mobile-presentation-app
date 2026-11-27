package com.jovoc.facecampresentationrecorder.contentplan;

import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * The backend calls of the AI content plan. Blocking: call off the main thread. Every failure is a
 * {@link ContentPlanException}; the session is refreshed / ended by {@link AuthRepository}. Same client style as
 * {@code AiEditApi}.
 */
public class ContentPlanApi {

    public static final String PATH_WEEK = "/public/v1/content-plan/week";
    static final String PATH_WEEK_STAMP = "/public/v1/content-plan/week/stamp";
    static final String PATH_DAYS = "/public/v1/content-plan/days/";
    static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private final AuthRepository repo;

    public ContentPlanApi(AuthRepository repo) {
        this.repo = repo;
    }

    /** GET the week: the raw body, so the cache keeps exactly what came. */
    public String week(String workspaceId) throws ContentPlanException {
        ApiResponse r = send("GET", PATH_WEEK, null, workspaceId, null);
        if (r.body.isEmpty() || r.body.charAt(0) != '{') throw new ContentPlanException(ContentPlanException.SERVER, true);
        return r.body;
    }

    /**
     * GET the week stamp: a cheap opaque value that changes whenever anything {@link #week} returns changes. Sent like
     * {@link #week}; a body that is not {@code {"stamp": "<text>"}} is a {@link ContentPlanException#SERVER} failure.
     */
    public String stamp(String workspaceId) throws ContentPlanException {
        ApiResponse r = send("GET", PATH_WEEK_STAMP, null, workspaceId, null);
        try {
            String stamp = new JSONObject(r.body).getString("stamp");
            if (stamp.isEmpty()) throw new ContentPlanException(ContentPlanException.SERVER, true);
            return stamp;
        } catch (JSONException e) {
            throw new ContentPlanException(ContentPlanException.SERVER, true, e);
        }
    }

    /** POST days/:id/recorded: tells the server the day was recorded. Idempotent per day. */
    public void recorded(String dayId, String workspaceId) throws ContentPlanException {
        Map<String, String> extra = new HashMap<>();
        extra.put(HEADER_IDEMPOTENCY, "plan-recorded-" + dayId);
        send("POST", PATH_DAYS + pathSegment(dayId) + "/recorded", "{}", workspaceId, extra);
    }

    // ---- internals ----

    /** Sends the call and returns the answer when it is 2xx; else throws the mapped failure. */
    private ApiResponse send(String method, String path, String body, String workspaceId,
                             Map<String, String> extraHeaders) throws ContentPlanException {
        Map<String, String> headers = new HashMap<>();
        if (extraHeaders != null) headers.putAll(extraHeaders);
        if (workspaceId != null && !workspaceId.isEmpty()) headers.put(AuthRepository.HEADER_WORKSPACE, workspaceId);
        ApiResponse r;
        try {
            r = repo.execute(method, path, body, headers);
        } catch (AuthException e) {
            switch (e.kind) {
                case NETWORK:
                    throw new ContentPlanException(ContentPlanException.NETWORK, true, e);
                case RATE_LIMITED:
                    throw new ContentPlanException(ContentPlanException.RATE_LIMITED, true, e);
                case SESSION_ENDED:
                case NOT_SIGNED_IN:
                    throw new ContentPlanException(ContentPlanException.SIGNED_OUT, false, e);
                default:
                    throw new ContentPlanException(ContentPlanException.SERVER, true, e);
            }
        }
        if (!r.isSuccess()) throw ContentPlanException.from(r);
        return r;
    }

    private static String pathSegment(String id) {
        try {
            return URLEncoder.encode(id, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
