package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.auth.ApiResponse;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * The five backend calls of the direct (multipart, straight to R2) upload. Blocking: call off the
 * main thread. Every failure is a {@link PublishException}.
 *
 * <p>None of these calls sends an Idempotency-Key: the server would store every answer under it
 * and replay stale part URLs or a stored 429. Part bytes go to R2 with a separate client, never
 * through here.
 */
public class DirectUploadApi {

    public static final String PATH_MULTIPART = "/public/v1/upload/multipart";
    /** The backend signs at most this many part numbers per call. */
    public static final int MAX_SIGN_PARTS = 20;

    private final AuthRepository repo;

    public DirectUploadApi(AuthRepository repo) {
        this.repo = repo;
    }

    /**
     * POST /upload/multipart.
     * @param filename its extension decides the type; send ".mp4"
     * @param size bytes, at least 1
     * @param workspaceId a job keeps the workspace it was made in; null uses the device's pick
     */
    public UploadSessionInfo create(String filename, long size, String workspaceId)
            throws PublishException {
        return create(filename, size, workspaceId, UploadKind.VIDEO);
    }

    /**
     * POST /upload/multipart for a given {@link UploadKind}: audio sends
     * {@code purpose: "transcript_audio"} and only accepts a ".wav" name; the video never does.
     */
    public UploadSessionInfo create(String filename, long size, String workspaceId, UploadKind kind)
            throws PublishException {
        if (filename == null || filename.isEmpty() || size < 1) {
            throw new IllegalArgumentException("filename and size are required");
        }
        if (!kind.allows(filename)) {
            throw new IllegalArgumentException("file type not allowed for " + kind);
        }
        String body;
        try {
            JSONObject o = new JSONObject().put("filename", filename).put("size", size);
            if (kind.purpose != null) o.put("purpose", kind.purpose);
            body = o.toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        Map<String, String> headers = PublishApi.workspaceHeaders(workspaceId, null);
        ApiResponse r = authed("POST", PATH_MULTIPART, body, headers);
        try {
            return UploadSessionInfo.parse(r.body);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /**
     * POST /upload/multipart/:id/parts: fresh URLs for the parts about to be sent (the API takes 1 to
     * {@link #MAX_SIGN_PARTS} = 20 per call; the uploader asks for {@link UploadPlan#URL_BATCH} = 4;
     * a URL dies 900 s after signing).
     */
    public PartUrls signParts(String uploadId, List<Integer> partNumbers, String workspaceId)
            throws PublishException {
        if (partNumbers == null || partNumbers.isEmpty() || partNumbers.size() > MAX_SIGN_PARTS) {
            throw new IllegalArgumentException("1 to " + MAX_SIGN_PARTS + " part numbers");
        }
        String body;
        try {
            body = new JSONObject().put("partNumbers", new JSONArray(partNumbers)).toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        ApiResponse r = authed("POST", path(uploadId, "/parts"), body,
                PublishApi.workspaceHeaders(workspaceId, null));
        try {
            PartUrls urls = PartUrls.parse(r.body);
            for (Integer n : partNumbers) {
                if (!urls.urls.containsKey(n)) throw new JSONException("missing url " + n);
            }
            return urls;
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /** GET /upload/multipart/:id: the status and, while pending, the parts R2 already holds. */
    public UploadStatus status(String uploadId, String workspaceId) throws PublishException {
        ApiResponse r = authed("GET", path(uploadId, ""), null,
                PublishApi.workspaceHeaders(workspaceId, null));
        try {
            return UploadStatus.parse(r.body);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /**
     * POST /upload/multipart/:id/complete with every part (exactly 1..partCount). Idempotent: a
     * repeat on a completed upload answers the same media.
     * @param posterBase64 optional poster image (plain base64, no data: prefix); null or empty sends
     *                     none. 400 INVALID_POSTER means resend without it.
     */
    public CompletedUpload complete(String uploadId, List<UploadedPart> parts, String posterBase64,
                                    String workspaceId) throws PublishException {
        if (parts == null || parts.isEmpty()) throw new IllegalArgumentException("no parts");
        String body;
        try {
            JSONArray a = new JSONArray();
            for (UploadedPart p : parts) {
                a.put(new JSONObject().put("n", p.n).put("etag", p.etag));
            }
            JSONObject o = new JSONObject().put("parts", a);
            if (posterBase64 != null && !posterBase64.isEmpty()) o.put("poster", posterBase64);
            body = o.toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        ApiResponse r = authed("POST", path(uploadId, "/complete"), body,
                PublishApi.workspaceHeaders(workspaceId, null));
        try {
            return CompletedUpload.parse(r.body);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /**
     * POST /upload/multipart/:id/complete of a transcript_audio upload: answers {@code { id }} (no
     * path, no poster), so it has its own parser. A repeat answers the same id.
     */
    public CompletedAudio completeAudio(String uploadId, List<UploadedPart> parts, String workspaceId)
            throws PublishException {
        if (parts == null || parts.isEmpty()) throw new IllegalArgumentException("no parts");
        String body;
        try {
            JSONArray a = new JSONArray();
            for (UploadedPart p : parts) {
                a.put(new JSONObject().put("n", p.n).put("etag", p.etag));
            }
            body = new JSONObject().put("parts", a).toString();
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        ApiResponse r = authed("POST", path(uploadId, "/complete"), body,
                PublishApi.workspaceHeaders(workspaceId, null));
        try {
            return CompletedAudio.parse(r.body);
        } catch (JSONException e) {
            throw new PublishException(PublishException.Kind.SERVER);
        }
    }

    /**
     * DELETE /upload/multipart/:id: frees one of the workspace's 5 pending slots. A repeat on an
     * aborted or expired upload is still a success; completed / completing is 409.
     */
    public void abort(String uploadId, String workspaceId) throws PublishException {
        authed("DELETE", path(uploadId, ""), null, PublishApi.workspaceHeaders(workspaceId, null));
    }

    // ---- internals ----

    /** Sends the call and returns the answer when it is 2xx; else throws the mapped failure. */
    private ApiResponse authed(String method, String path, String body, Map<String, String> headers)
            throws PublishException {
        ApiResponse r;
        try {
            r = repo.execute(method, path, body, headers);
        } catch (AuthException e) {
            throw PublishException.from(e);
        }
        if (!r.isSuccess()) throw PublishException.fromUpload(r);
        return r;
    }

    private static String path(String uploadId, String suffix) {
        if (uploadId == null || !uploadId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("bad upload id");
        }
        return PATH_MULTIPART + "/" + uploadId + suffix;
    }
}
