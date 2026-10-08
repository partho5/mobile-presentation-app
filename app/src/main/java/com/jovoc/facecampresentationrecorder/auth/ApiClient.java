package com.jovoc.facecampresentationrecorder.auth;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpUrl parsed = HttpUrl.parse(trimmed);
        if (parsed == null) throw new IllegalArgumentException("Bad backend URL");
        if (!parsed.isHttps() && !allowCleartext) {
            throw new IllegalArgumentException("Backend URL must be https");
        }
        this.baseUrl = trimmed;
        this.http = http;
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
}
