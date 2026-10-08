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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The four backend calls publishing needs. Blocking: call off the main thread. Every failure
 * is a {@link PublishException}; the session is refreshed / ended by {@link AuthRepository}.
 */
public class PublishApi {

    public static final String PATH_UPLOAD = "/public/v1/upload";
    public static final String PATH_INTEGRATIONS = "/public/v1/integrations";
    public static final String PATH_POSTS = "/public/v1/posts";
    public static final String PATH_CHARGE_PREVIEW = "/posts/charge-preview";
    public static final String HEADER_IDEMPOTENCY = "Idempotency-Key";
    /** The backend's video cap (credits.publish / custom.upload.validation). */
    public static final long MAX_VIDEO_BYTES = 1024L * 1024L * 1024L;

    /** What the backend stored for an upload; the post refers to the video by these. */
    public static final class Media {
        public final String id;
        public final String path;

        Media(String id, String path) {
            this.id = id;
            this.path = path;
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

    /** POST /public/v1/upload (multipart "file"). */
    public Media upload(File file, ApiClient.ProgressListener progress, BooleanSupplier cancelled)
            throws PublishException {
        ApiResponse r;
        try {
            r = repo.executeUpload(PATH_UPLOAD, "file", file, "video/mp4", progress, cancelled);
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
     * @return the created post ids (never empty)
     */
    public List<String> createPost(PublishJob job, long nowMs) throws PublishException {
        Map<String, String> headers = new HashMap<>();
        headers.put(HEADER_IDEMPOTENCY, job.idempotencyKey);
        String body = PublishRequestBuilder.createPostBody(job, nowMs);
        ApiResponse r = authed(() -> repo.execute("POST", PATH_POSTS, body, headers));
        if (!r.isSuccess()) throw PublishException.from(r);
        try {
            List<String> ids = PublishRequestBuilder.postIds(r.body);
            if (ids.isEmpty()) {
                throw new PublishException(PublishException.Kind.REJECTED, r.status,
                        "The server did not create a post. Check the channel and try again.", -1);
            }
            return Collections.unmodifiableList(ids);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    // ---- internals ----

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
