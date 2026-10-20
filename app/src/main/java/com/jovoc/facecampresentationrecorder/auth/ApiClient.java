package com.jovoc.facecampresentationrecorder.auth;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

/**
 * Thin JSON-over-HTTP client for the backend. No auth logic here; see {@link AuthRepository}.
 * An IOException means the server was not reached (offline, DNS, timeout).
 */
public class ApiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final OkHttpClient http;

    /** @param allowCleartext debug builds only: lets a LAN backend use http. */
    public ApiClient(String baseUrl, boolean allowCleartext) {
        this(baseUrl, newHttp(allowCleartext), allowCleartext);
    }

    /** For tests: supply the client (e.g. pointing at MockWebServer over http). */
    public ApiClient(String baseUrl, OkHttpClient http, boolean allowCleartext) {
        this.baseUrl = validate(baseUrl, allowCleartext);
        this.http = http;
    }

    private static String validate(String baseUrl, boolean allowCleartext) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpUrl parsed = HttpUrl.parse(trimmed);
        if (parsed == null) throw new IllegalArgumentException("Bad backend URL");
        if (!parsed.isHttps() && !allowCleartext) {
            throw new IllegalArgumentException("Backend URL must be https");
        }
        return trimmed;
    }

    static OkHttpClient newHttp(boolean allowCleartext) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        b.connectionSpecs(allowCleartext
                ? java.util.Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
                : Collections.singletonList(ConnectionSpec.MODERN_TLS));
        return b.build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Builds the request; kept separate so tests can inspect it without a network. */
    Request buildRequest(String method, String path, String jsonBody, Map<String, String> headers) {
        Request.Builder rb = new Request.Builder().url(baseUrl + path);
        rb.header("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                rb.header(h.getKey(), h.getValue());
            }
        }
        RequestBody body = jsonBody == null ? null : RequestBody.create(jsonBody, JSON);
        if (body == null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            body = RequestBody.create("", JSON);
        }
        rb.method(method, body);
        return rb.build();
    }

    public ApiResponse send(String method, String path, String jsonBody, Map<String, String> headers)
            throws IOException {
        try (Response r = http.newCall(buildRequest(method, path, jsonBody, headers)).execute()) {
            String text = r.body() == null ? "" : r.body().string();
            return new ApiResponse(r.code(), text,
                    ApiResponse.parseRetryAfter(r.header("Retry-After")),
                    "true".equalsIgnoreCase(r.header("onboarding")));
        }
    }

    public ApiResponse postJson(String path, String jsonBody) throws IOException {
        return send("POST", path, jsonBody, null);
    }

    /** Bytes sent so far out of the total, called from the uploading thread. */
    public interface ProgressListener {
        void onProgress(long sent, long total);
    }

    /**
     * POSTs {@code file} as multipart field {@code fieldName}. Streams from disk (no whole-file
     * buffer), reports progress, and aborts with an InterruptedIOException once {@code cancelled}
     * turns true. A body that is still being sent when the connection drops is an IOException.
     */
    public ApiResponse upload(String path, String fieldName, File file, String mime,
                              Map<String, String> headers, ProgressListener progress,
                              BooleanSupplier cancelled) throws IOException {
        Request request = buildUploadRequest(path, fieldName, file, mime, headers, progress, cancelled);
        try (Response r = uploadClient(http).newCall(request).execute()) {
            String text = r.body() == null ? "" : r.body().string();
            return new ApiResponse(r.code(), text,
                    ApiResponse.parseRetryAfter(r.header("Retry-After")), false);
        }
    }

    /** A write that makes no progress for this long fails the upload (a stalled connection). */
    static final int UPLOAD_WRITE_TIMEOUT_SECONDS = 120;
    /**
     * After the last byte the server streams the file on to storage before it answers; a 1 GB
     * video over a slow link to storage can take minutes.
     */
    static final int UPLOAD_READ_TIMEOUT_SECONDS = 5 * 60;

    /**
     * Timeouts for a large upload, derived from the normal client: no overall call timeout (the
     * transfer may legitimately run for many minutes; the cancel flag stops it), a generous
     * per-write stall limit, and a 5 minute wait for the answer after the last byte. Calls
     * other than uploads keep the short defaults of {@link #newHttp}.
     */
    static OkHttpClient uploadClient(OkHttpClient base) {
        return base.newBuilder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(UPLOAD_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(UPLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    Request buildUploadRequest(String path, String fieldName, File file, String mime,
                               Map<String, String> headers, ProgressListener progress,
                               BooleanSupplier cancelled) {
        RequestBody part = new ProgressFileBody(file, MediaType.get(mime), progress, cancelled);
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(fieldName, file.getName(), part)
                .build();
        Request.Builder rb = new Request.Builder().url(baseUrl + path).header("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) rb.header(h.getKey(), h.getValue());
        }
        return rb.post(body).build();
    }

    /** A file body that reports how much has been written and can be cancelled mid-stream. */
    static final class ProgressFileBody extends RequestBody {
        private static final int CHUNK = 64 * 1024;
        private final File file;
        private final MediaType type;
        private final ProgressListener progress;
        private final BooleanSupplier cancelled;

        ProgressFileBody(File file, MediaType type, ProgressListener progress, BooleanSupplier cancelled) {
            this.file = file;
            this.type = type;
            this.progress = progress;
            this.cancelled = cancelled;
        }

        @Override
        public MediaType contentType() {
            return type;
        }

        @Override
        public long contentLength() {
            return file.length();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            long total = file.length();
            long sent = 0;
            try (Source source = Okio.source(file)) {
                Buffer buf = new Buffer();
                long n;
                while ((n = source.read(buf, CHUNK)) != -1) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new InterruptedIOException("cancelled");
                    }
                    sink.write(buf, n);
                    sent += n;
                    if (progress != null) progress.onProgress(sent, total);
                }
            }
        }
    }
}
