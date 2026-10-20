package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;
import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The backend calls publishing needs. Blocking: call off the main thread. Every failure
 * is a {@link PublishException}; the session is refreshed / ended by {@link AuthRepository}.
 */
public class PublishApi {

    public static final String PATH_UPLOAD = "/public/v1/upload";
    public static final String PATH_INTEGRATIONS = "/public/v1/integrations";
    public static final String PATH_POSTS = "/public/v1/posts";
    public static final String PATH_CHARGE_PREVIEW = "/posts/charge-preview";
    public static final String PATH_AUTO_PUBLISH = "/public/v1/auto-publish";
    public static final String PATH_POSTS_STATUS = "/public/v1/posts/status";
    public static final String PATH_PLATFORMS = "/public/v1/platforms";
    public static final String PATH_AUTO_PUBLISH_CHANNELS = "/public/v1/auto-publish/channels/";
    public static final String PATH_WEB_LINK = "/public/v1/web-link";
    /** web-link targets: the app-keys page of a provider, or its connect flow. */
    public static final String LINK_CREDENTIALS = "credentials";
    public static final String LINK_CONNECT = "connect";
    /** web-link target: the billing page, with the plan to highlight as the identifier. */
    public static final String LINK_BILLING = "billing";
    /** The backend answers at most this many ids per GET /posts/status. */
    public static final int MAX_STATUS_IDS = 50;
    public static final String HEADER_IDEMPOTENCY = "Idempotency-Key";
    /**
     * The video cap of the legacy {@code POST /upload} only (credits.publish /
     * custom.upload.validation). The direct upload has no client-side cap: the server's
     * {@code MOBILE_UPLOAD_MAX_BYTES} (4 GiB by default) is authoritative and answers
     * 400 FILE_TOO_LARGE on create.
     */
    public static final long LEGACY_MAX_VIDEO_BYTES = 1024L * 1024L * 1024L;

    /** What the backend stored for an upload; the post refers to the video by these. */
    public static final class Media {
        public final String id;
        public final String path;

        Media(String id, String path) {
            this.id = id;
            this.path = path;
        }
    }

    /** The posts a create call made: ids, and the channel of each (same order, "" when unknown). */
    public static final class Posted {
        public final List<String> ids;
        public final List<String> integrations;

        Posted(List<String> ids, List<String> integrations) {
            this.ids = ids;
            this.integrations = integrations;
        }
    }

    private final AuthRepository repo;

    public PublishApi(AuthRepository repo) {
        this.repo = repo;
    }

    /** GET /public/v1/integrations */
    public List<Channel> listChannels() throws PublishException {
        ApiResponse r = authed(() -> repo.execute("GET", PATH_INTEGRATIONS, null, null));
        if (!r.isSuccess()) throw PublishException.from(r);
        try {
            return Channel.parseList(r.body);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /** POST /posts/charge-preview: credits this publish would cost. 0 means free. */
    public int chargePreview(List<String> integrationIds) throws PublishException {
        ApiResponse r = authed(() -> repo.execute("POST", PATH_CHARGE_PREVIEW,
                PublishRequestBuilder.chargePreviewBody(integrationIds), null));
        if (!r.isSuccess()) throw PublishException.from(r);
        try {
            JSONObject o = r.json();
            if (!o.has("amount")) throw new JSONException("no amount");
            return Math.max(0, o.getInt("amount"));
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /** GET /public/v1/auto-publish for the device's current workspace. */
    public AutoPublishInfo autoPublishInfo() throws PublishException {
        return autoPublishInfo(null);
    }

    /**
     * GET /public/v1/auto-publish: the workspace's auto-publish preset, its cost and the balance.
     * @param workspaceId the workspace to ask about; null or empty uses the device's pick / default
     */
    public AutoPublishInfo autoPublishInfo(String workspaceId) throws PublishException {
        Map<String, String> headers = workspaceHeaders(workspaceId, null);
        ApiResponse r = authed(() -> repo.execute("GET", PATH_AUTO_PUBLISH, null, headers));
        if (!r.isSuccess()) throw PublishException.from(r);
        try {
            return AutoPublishInfo.parse(r.body);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /** GET /public/v1/platforms: every supported platform, its state and connected channels. */
    public Platforms platforms() throws PublishException {
        ApiResponse r = authed(() -> repo.execute("GET", PATH_PLATFORMS, null, null));
        if (!r.isSuccess()) throw PublishException.from(r);
        try {
            return Platforms.parse(r.body);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /** PUT /public/v1/auto-publish/channels/:id {enabled}: include the channel in auto-publish or not. */
    public void setChannelAutoPublish(String integrationId, boolean enabled) throws PublishException {
        String path = PATH_AUTO_PUBLISH_CHANNELS + pathSegment(integrationId);
        String body;
        try {
            body = new JSONObject().put("enabled", enabled).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        ApiResponse r = authed(() -> repo.execute("PUT", path, body, null));
        if (!r.isSuccess()) throw PublishException.from(r);
    }

    /**
     * POST /public/v1/web-link: a one-time link that opens the website already signed in, on the
     * page for {@code target} ({@link #LINK_CREDENTIALS} / {@link #LINK_CONNECT}) of the provider.
     * Single use and short-lived: request it right before opening, never keep it.
     */
    public String webLink(String target, String identifier) throws PublishException {
        String body;
        try {
            body = new JSONObject().put("target", target).put("identifier", identifier).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        ApiResponse r = authed(() -> repo.execute("POST", PATH_WEB_LINK, body, null));
        if (!r.isSuccess()) throw PublishException.from(r);
        try {
            String url = r.json().optString("url", "");
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw new JSONException("no url");
            }
            return url;
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /** POST /public/v1/upload (multipart "file"). */
    public Media upload(File file, String workspaceId, ApiClient.ProgressListener progress,
                        BooleanSupplier cancelled) throws PublishException {
        ApiResponse r;
        try {
            r = repo.executeUpload(PATH_UPLOAD, "file", file, "video/mp4", progress, cancelled,
                    workspaceHeaders(workspaceId, null));
        } catch (AuthException e) {
            if (isCancelled(e, cancelled)) throw new PublishException(PublishException.Kind.CANCELLED);
            throw PublishException.from(e);
        }
        if (!r.isSuccess()) throw PublishException.from(r);
        try {
            JSONObject o = r.json();
            String id = o.optString("id", "");
            String path = o.optString("path", "");
            if (id.isEmpty() || path.isEmpty()) throw new JSONException("no media");
            return new Media(id, path);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /**
     * POST /public/v1/posts with the job's Idempotency-Key.
     * @return the created posts (never empty)
     */
    public Posted createPost(PublishJob job, long nowMs) throws PublishException {
        Map<String, String> headers = workspaceHeaders(job.workspaceId, null);
        headers.put(HEADER_IDEMPOTENCY, job.idempotencyKey);
        String body = PublishRequestBuilder.createPostBody(job, nowMs);
        ApiResponse r = authed(() -> repo.execute("POST", PATH_POSTS, body, headers));
        if (!r.isSuccess()) throw PublishException.from(r);
        return postedIds(r);
    }

    /**
     * POST /public/v1/auto-publish with the job's Idempotency-Key: the server builds the post
     * from the workspace's preset and runs the same checks and charging as {@link #createPost}.
     * @return the created posts (never empty)
     */
    public Posted autoPublish(PublishJob job) throws PublishException {
        Map<String, String> headers = workspaceHeaders(job.workspaceId, null);
        headers.put(HEADER_IDEMPOTENCY, job.idempotencyKey);
        String body = PublishRequestBuilder.autoPublishBody(job);
        ApiResponse r = authed(() -> repo.execute("POST", PATH_AUTO_PUBLISH, body, headers));
        if (!r.isSuccess()) throw PublishException.from(r);
        return postedIds(r);
    }

    /**
     * GET /public/v1/posts/status for up to {@link #MAX_STATUS_IDS} posts of one workspace. Ids the
     * server does not know (or that belong to another workspace) are simply absent from the answer.
     * @param workspaceId the workspace the posts were made in; null or empty uses the device's pick
     */
    public List<PostStatus> postStatus(List<String> ids, String workspaceId) throws PublishException {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_STATUS_IDS) {
            throw new IllegalArgumentException("1 to " + MAX_STATUS_IDS + " ids");
        }
        String path = PATH_POSTS_STATUS + "?ids=" + PostStatusSync.idsQuery(ids);
        Map<String, String> headers = workspaceHeaders(workspaceId, null);
        ApiResponse r = authed(() -> repo.execute("GET", path, null, headers));
        if (!r.isSuccess()) throw PublishException.from(r);
        try {
            return PostStatus.parseList(r.body);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    private static Posted postedIds(ApiResponse r) throws PublishException {
        try {
            List<String> ids = PublishRequestBuilder.postIds(r.body);
            if (ids.isEmpty()) {
                throw new PublishException(PublishException.Kind.REJECTED, r.status,
                        "The server did not create a post. Check the channel and try again.", -1);
            }
            return new Posted(Collections.unmodifiableList(ids),
                    Collections.unmodifiableList(PublishRequestBuilder.postIntegrations(r.body)));
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    // ---- internals ----

    /** A job keeps the workspace it was made in; without one the device's pick / default applies. */
    static Map<String, String> workspaceHeaders(String workspaceId, Map<String, String> base) {
        Map<String, String> h = new HashMap<>();
        if (base != null) h.putAll(base);
        if (workspaceId != null && !workspaceId.isEmpty()) {
            h.put(AuthRepository.HEADER_WORKSPACE, workspaceId);
        }
        return h;
    }

    /** An id as one URL path segment. */
    private static String pathSegment(String id) {
        try {
            return URLEncoder.encode(id, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private interface Call {
        ApiResponse run() throws AuthException;
    }

    private static ApiResponse authed(Call call) throws PublishException {
        try {
            return call.run();
        } catch (AuthException e) {
            throw PublishException.from(e);
        }
    }

    private static boolean isCancelled(AuthException e, BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean()
                && e.getCause() instanceof InterruptedIOException;
    }
}
