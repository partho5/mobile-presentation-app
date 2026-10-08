package com.jovoc.facecampresentationrecorder.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import okhttp3.Request;
import okio.Buffer;

public class ApiClientTest {

    @Test
    public void buildsJsonPostWithHeaders() throws Exception {
        ApiClient c = new ApiClient("https://api.example.com/api/", false);
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer abc");
        h.put("x-workspace-id", "ws1");
        Request r = c.buildRequest("POST", "/public/v1/posts", "{\"a\":1}", h);

        assertEquals("https://api.example.com/api/public/v1/posts", r.url().toString());
        assertEquals("POST", r.method());
        assertEquals("Bearer abc", r.header("Authorization"));
        assertEquals("ws1", r.header("x-workspace-id"));
        assertTrue(r.body().contentType().toString().startsWith("application/json"));
        Buffer b = new Buffer();
        r.body().writeTo(b);
        assertEquals("{\"a\":1}", b.readUtf8());
    }

    @Test
    public void getHasNoBodyAndPostWithoutBodyGetsEmpty() {
        ApiClient c = new ApiClient("https://api.example.com", false);
        assertNull(c.buildRequest("GET", "/x", null, null).body());
        assertEquals(0, nullSafeLength(c.buildRequest("POST", "/x", null, null)));
    }

    private static long nullSafeLength(Request r) {
        try {
            return r.body().contentLength();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void releaseRefusesCleartextUrl() {
        try {
            new ApiClient("http://10.0.0.5:3000", false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        new ApiClient("http://10.0.0.5:3000", true); // debug allows it
    }

    @Test
    public void releaseClientCannotUseCleartextSpec() {
        assertEquals(1, ApiClient.newHttp(false).connectionSpecs().size());
        assertEquals(2, ApiClient.newHttp(true).connectionSpecs().size());
    }

    @Test
    public void parsesErrorCodeAndRetryAfter() {
        ApiResponse r = new ApiResponse(429, "{\"statusCode\":429,\"code\":\"AUTH_RATE_LIMITED\"}",
                ApiResponse.parseRetryAfter("42"), false);
        assertEquals("AUTH_RATE_LIMITED", r.errorCode);
        assertEquals(42, r.retryAfterSeconds);
        assertTrue(r.is(429, ApiResponse.AUTH_RATE_LIMITED));
        assertEquals(-1, ApiResponse.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertNull(new ApiResponse(500, "<html>", -1, false).errorCode);
        assertNull(new ApiResponse(204, "", -1, false).errorCode);
    }
}
