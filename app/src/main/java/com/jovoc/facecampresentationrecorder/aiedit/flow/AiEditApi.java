package com.jovoc.facecampresentationrecorder.aiedit.flow;

import com.jovoc.facecampresentationrecorder.BuildConfig;
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
 * The backend calls an AI edit needs (api.ai-video-editing). Blocking: call off the main thread. Every failure is
 * an {@link AiEditException}; the session is refreshed / ended by {@link AuthRepository}. Same client style as
 * {@code PublishApi}.
 */
public class AiEditApi {

    public static final String PATH_AI_EDITS = "/public/v1/ai-edits";
    public static final String PATH_CONTRACT = PATH_AI_EDITS + "/contract";
    public static final String PATH_QUOTE = PATH_AI_EDITS + "/quote";
    /** Names the app build to the server on the plan reads, so a plan can be traced to the build that got it. */
    static final String HEADER_APP_VERSION = "x-app-version";

    /** What an edit of one length costs this workspace at each level, in credits. */
    public static final class Quote {
        public final int basic;
        public final int standard;
        public final int heavy;

        Quote(int basic, int standard, int heavy) {
            this.basic = basic;
            this.standard = standard;
            this.heavy = heavy;
        }

        /** Credits of {@code level} ("basic", "standard", "heavy"); 0 for an unknown name. */
        public int forLevel(String level) {
            if ("basic".equals(level)) return basic;
            if ("standard".equals(level)) return standard;
            if ("heavy".equals(level)) return heavy;
            return 0;
        }
    }

    private final AuthRepository repo;

    public AiEditApi(AuthRepository repo) {
        this.repo = repo;
    }

    /** GET /public/v1/ai-edits/contract: the engine contract as JSON text. */
    public String contract(String workspaceId) throws AiEditException {
        ApiResponse r = send("GET", PATH_CONTRACT, null, workspaceId);
        if (r.body.isEmpty() || r.body.charAt(0) != '{') throw new AiEditException(AiEditException.SERVER, true);
        return r.body;
    }

    /** GET /public/v1/ai-edits/quote?seconds=N: the price of each level for a recording this long. */
    public Quote quote(long seconds, String workspaceId) throws AiEditException {
        ApiResponse r = send("GET", PATH_QUOTE + "?seconds=" + Math.max(1, seconds), null, workspaceId);
        try {
            JSONObject o = r.json();
            return new Quote(Math.max(0, o.getInt("basic")), Math.max(0, o.getInt("standard")),
                    Math.max(0, o.getInt("heavy")));
        } catch (JSONException e) {
            throw new AiEditException(AiEditException.SERVER, true, e);
        }
    }

    /**
     * POST /public/v1/ai-edits with {@code body} (see {@link AiEditRequestBuilder}): starts the job and holds its
     * credits. A repeat for the same recording and level answers the job that already exists, so a lost answer
     * never charges twice; a job that FAILED is replaced by a new one.
     */
    public AiEditStatus create(String body, String workspaceId) throws AiEditException {
        return parse(send("POST", PATH_AI_EDITS, body, workspaceId));
    }

    /** GET /public/v1/ai-edits/:id: the status and, once READY, the plan with its assets. */
    public AiEditStatus get(String editId, String workspaceId) throws AiEditException {
        Map<String, String> extra = new HashMap<>();
        extra.put(HEADER_APP_VERSION, RenderReceipt.sanitizeVersion(BuildConfig.VERSION_NAME));
        return parse(send("GET", PATH_AI_EDITS + "/" + pathSegment(editId), null, workspaceId, extra));
    }

    /**
     * POST /public/v1/ai-edits/:id/render-report: the receipt of one render attempt (see {@link RenderReceipt}).
     * Idempotent per attempt on the server, so a repeat is harmless.
     */
    public void reportRender(String editId, String body, String workspaceId) throws AiEditException {
        send("POST", PATH_AI_EDITS + "/" + pathSegment(editId) + "/render-report", body, workspaceId);
    }

    // ---- internals ----

    private static AiEditStatus parse(ApiResponse r) throws AiEditException {
        try {
            return AiEditStatus.parse(r.body);
        } catch (JSONException e) {
            throw new AiEditException(AiEditException.SERVER, true, e);
        }
    }

    /** Sends the call and returns the answer when it is 2xx; else throws the mapped failure. */
    private ApiResponse send(String method, String path, String body, String workspaceId)
            throws AiEditException {
        return send(method, path, body, workspaceId, null);
    }

    private ApiResponse send(String method, String path, String body, String workspaceId,
                             Map<String, String> extraHeaders) throws AiEditException {
        Map<String, String> headers = new HashMap<>();
        if (extraHeaders != null) headers.putAll(extraHeaders);
        if (workspaceId != null && !workspaceId.isEmpty()) headers.put(AuthRepository.HEADER_WORKSPACE, workspaceId);
        ApiResponse r;
        try {
            r = repo.execute(method, path, body, headers);
        } catch (AuthException e) {
            switch (e.kind) {
                case NETWORK:
                    throw new AiEditException(AiEditException.NETWORK, true, e);
                case RATE_LIMITED:
                    throw new AiEditException(AiEditException.RATE_LIMITED, true, e);
                case SESSION_ENDED:
                case NOT_SIGNED_IN:
                    throw new AiEditException(AiEditException.SIGNED_OUT, false, e);
                default:
                    throw new AiEditException(AiEditException.SERVER, true, e);
            }
        }
        if (!r.isSuccess()) throw AiEditException.from(r);
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
