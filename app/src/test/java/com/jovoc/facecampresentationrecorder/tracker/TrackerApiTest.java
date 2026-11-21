package com.jovoc.facecampresentationrecorder.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.jovoc.facecampresentationrecorder.auth.ApiClient;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public class TrackerApiTest {

    private static final String BODY = "{\"batchId\":\"b1\",\"activities\":[{\"id\":\"a\"}]}";

    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        try {
            server.shutdown();
        } catch (Throwable ignored) {
            // already shut down in a test
        }
    }

    private TrackerApi apiWithToken(final String token) {
        ApiClient client = new ApiClient(server.url("/").toString(), new OkHttpClient(), true);
        return new TrackerApi(client, () -> token);
    }

    private static String gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void okIsSentGzippedWithTheToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

        assertEquals(TrackerApi.Outcome.SENT, apiWithToken("t").send(BODY));

        RecordedRequest req = server.takeRequest();
        assertEquals("/tracker/batches", req.getPath());
        assertEquals("gzip", req.getHeader("Content-Encoding"));
        assertEquals("Bearer t", req.getHeader("Authorization"));
        assertEquals(BODY, gunzip(req.getBody().readByteArray()));
    }

    @Test
    public void badRequestUnprocessableAndTooLargeAreDropped() {
        int[] codes = {400, 413, 422};
        for (int code : codes) {
            server.enqueue(new MockResponse().setResponseCode(code));
            assertEquals("status " + code, TrackerApi.Outcome.DROP, apiWithToken(null).send(BODY));
        }
    }

    @Test
    public void unauthorizedIsResentOnceWithoutTheToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(200));

        assertEquals(TrackerApi.Outcome.SENT, apiWithToken("t").send(BODY));

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals("Bearer t", first.getHeader("Authorization"));
        assertNull(second.getHeader("Authorization"));
    }

    @Test
    public void serviceUnavailableIsRetried() {
        server.enqueue(new MockResponse().setResponseCode(503));
        assertEquals(TrackerApi.Outcome.RETRY, apiWithToken(null).send(BODY));
    }

    @Test
    public void notFoundIsRetried() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertEquals(TrackerApi.Outcome.RETRY, apiWithToken(null).send(BODY));
    }

    @Test
    public void unreachableServerIsRetried() throws Exception {
        TrackerApi api = apiWithToken(null);
        server.shutdown();
        assertEquals(TrackerApi.Outcome.RETRY, api.send(BODY));
    }

    @Test
    public void nullTokenSendsNoAuthorizationHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        assertEquals(TrackerApi.Outcome.SENT, apiWithToken(null).send(BODY));

        RecordedRequest req = server.takeRequest();
        assertNull(req.getHeader("Authorization"));
        assertEquals(BODY, gunzip(req.getBody().readByteArray()));
    }
}
