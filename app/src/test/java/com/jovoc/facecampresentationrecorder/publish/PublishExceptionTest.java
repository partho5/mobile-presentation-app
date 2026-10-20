package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.auth.ApiResponse;

import org.junit.Test;

public class PublishExceptionTest {

    private static PublishException of(int status, String body) {
        return PublishException.from(new ApiResponse(status, body, -1, false));
    }

    @Test
    public void insufficientCreditsKeepsTheServerMessageAndSpendsTheKey() {
        PublishException e = of(402, "{\"statusCode\":402,\"code\":\"INSUFFICIENT_CREDITS\","
                + "\"message\":\"This needs 5 credits and you have 2.\",\"required\":5,\"available\":2}");
        assertEquals(PublishException.Kind.INSUFFICIENT_CREDITS, e.kind);
        assertEquals("This needs 5 credits and you have 2.", e.detail);
        assertFalse(e.isRetryable());
        assertTrue(e.spendsKey());
    }

    @Test
    public void featureNotInPlanNeedsAPlanAndIsNeverRetried() {
        PublishException e = of(403, "{\"statusCode\":403,\"code\":\"FEATURE_NOT_IN_PLAN\","
                + "\"feature\":\"auto_publish\",\"requiredPlan\":\"starter\"}");
        assertEquals(PublishException.Kind.NEEDS_PLAN, e.kind);
        assertEquals("auto_publish", e.detail);
        assertFalse(e.isRetryable());
        // A final 4xx the server stores under the key: the next attempt needs a new one.
        assertTrue(e.spendsKey());
        // No feature in the body: still a plan problem.
        PublishException bare = of(403, "{\"code\":\"FEATURE_NOT_IN_PLAN\"}");
        assertEquals(PublishException.Kind.NEEDS_PLAN, bare.kind);
        assertNull(bare.detail);
    }

    @Test
    public void otherForbiddenAnswersAreNotNeedsPlan() {
        assertEquals(PublishException.Kind.WORKSPACE_FORBIDDEN,
                of(403, "{\"code\":\"WORKSPACE_FORBIDDEN\"}").kind);
        assertEquals(PublishException.Kind.REJECTED, of(403, "{\"message\":\"no\"}").kind);
        // The code alone is not enough: it is a 403 answer.
        assertEquals(PublishException.Kind.REJECTED,
                of(400, "{\"code\":\"FEATURE_NOT_IN_PLAN\",\"message\":\"x\"}").kind);
    }

    @Test
    public void validationErrorShowsTheReadableReason() {
        PublishException e = of(400, "{\"statusCode\":400,\"provider\":\"x\",\"name\":\"X\","
                + "\"message\":\"post is too long, please fix it\"}");
        assertEquals(PublishException.Kind.REJECTED, e.kind);
        // The channel is named so the user knows which one to fix on the web.
        assertEquals("X: post is too long, please fix it", e.detail);
        assertTrue(e.spendsKey());

        // Not repeated when the message already names it; plain 400s are untouched.
        assertEquals("YouTube title is empty", of(400, "{\"statusCode\":400,\"provider\":\"youtube\","
                + "\"name\":\"YouTube\",\"message\":\"YouTube title is empty\"}").detail);
        assertEquals("no", of(400, "{\"message\":\"no\",\"name\":\"Z\"}").detail);
    }

    @Test
    public void throttlerRateLimitIsStoredByTheServerSoItRotatesTheKey() {
        PublishException e = of(429, "{\"statusCode\":429,"
                + "\"message\":\"ThrottlerException: Too Many Requests\"}");
        assertEquals(PublishException.Kind.RATE_LIMITED, e.kind);
        assertTrue(e.isRetryable());
        assertTrue(e.spendsKey());
        // An unreadable 429 (a proxy's) cannot be told apart from an unknown outcome: keep the key.
        assertFalse(of(429, "").spendsKey());
        assertFalse(of(429, "{\"message\":\"slow down\"}").spendsKey());
    }

    @Test
    public void uploadAnswersAreMappedPerTheGuide() {
        assertEquals(PublishException.Kind.TOO_LARGE, of(400, "{\"message\":\"File is too large.\","
                + "\"error\":\"Bad Request\",\"statusCode\":400}").kind);
        assertFalse(of(400, "{\"message\":\"File is too large.\"}").isRetryable());
        PublishException unreadable = of(400, "{\"message\":\"Failed to read file\",\"statusCode\":400}");
        assertEquals(PublishException.Kind.SERVER, unreadable.kind);
        assertTrue(unreadable.isRetryable());
        PublishException unsupported = of(400, "{\"message\":\"Unsupported file type.\",\"statusCode\":400}");
        assertEquals(PublishException.Kind.REJECTED, unsupported.kind);
        assertEquals("Unsupported file type.", unsupported.detail);
        assertFalse(unsupported.isRetryable());
    }

    @Test
    public void autoPublishAnswersAreMappedPerTheGuide() {
        PublishException notSetUp = of(409, "{\"statusCode\":409,\"code\":\"AUTO_PUBLISH_NOT_CONFIGURED\"}");
        assertEquals(PublishException.Kind.AUTO_NOT_CONFIGURED, notSetUp.kind);
        assertTrue(notSetUp.spendsKey());
        assertFalse(notSetUp.isRetryable());

        PublishException media = of(404, "{\"statusCode\":404,\"code\":\"MEDIA_NOT_FOUND\"}");
        assertEquals(PublishException.Kind.MEDIA_NOT_FOUND, media.kind);
        assertTrue(media.spendsKey());

        PublishException purged = of(410, "{\"statusCode\":410,\"code\":\"MEDIA_PURGED\","
                + "\"message\":\"The video was removed\"}");
        assertEquals(PublishException.Kind.MEDIA_PURGED, purged.kind);
        assertFalse(purged.isRetryable()); // never retried on its own
        assertFalse(purged.isUploadSession());
        // A 4xx is stored under the key for 24 h: the next publish needs a new one.
        assertTrue(purged.spendsKey());
        // An upload-step failure never touches the key, whatever the status.
        assertFalse(purged.forUploadStep().spendsKey());
        // Any other 410 stays a plain rejection.
        assertEquals(PublishException.Kind.REJECTED, of(410, "{\"statusCode\":410}").kind);

        PublishException forbidden = of(403, "{\"statusCode\":403,\"code\":\"WORKSPACE_FORBIDDEN\"}");
        assertEquals(PublishException.Kind.WORKSPACE_FORBIDDEN, forbidden.kind);
        assertFalse(forbidden.isRetryable());

        PublishException reused = of(422, "{\"statusCode\":422,\"code\":\"IDEMPOTENCY_KEY_REUSED\"}");
        assertEquals(PublishException.Kind.REJECTED, reused.kind);
        assertTrue(reused.spendsKey());
        assertFalse(reused.isRetryable());
    }

    @Test
    public void arrayMessageUsesTheFirstEntry() {
        PublishException e = of(400, "{\"message\":[\"title must be longer\",\"other\"]}");
        assertEquals("title must be longer", e.detail);
        assertEquals("upload failed", of(400, "{\"msg\":\"upload failed\"}").detail);
    }

    @Test
    public void transientAnswersAreRetryableAndKeepTheKey() {
        PublishException server = of(502, "bad gateway");
        assertEquals(PublishException.Kind.SERVER, server.kind);
        assertTrue(server.isRetryable());
        assertFalse(server.spendsKey());

        PublishException busy = of(409, "{\"statusCode\":409,\"code\":\"IDEMPOTENCY_IN_PROGRESS\"}");
        assertEquals(PublishException.Kind.IN_PROGRESS, busy.kind);
        assertTrue(busy.isRetryable());
        assertFalse(busy.spendsKey());

        PublishException limited = of(429, "{\"statusCode\":429,\"code\":\"AUTH_RATE_LIMITED\"}");
        assertEquals(PublishException.Kind.RATE_LIMITED, limited.kind);
        assertTrue(limited.isRetryable());
        assertFalse(limited.spendsKey());
    }

    @Test
    public void tooLargeAndUnauthorizedAreFinal() {
        assertEquals(PublishException.Kind.TOO_LARGE, of(413, "").kind);
        PublishException unauthorized = of(401, "{\"statusCode\":401}");
        assertEquals(PublishException.Kind.SIGNED_OUT, unauthorized.kind);
        assertFalse(unauthorized.isRetryable());
    }

    @Test
    public void unauthorizedNeverSpendsTheKey() {
        // The server stored nothing for a 401, so a Retry after an unknown-outcome post must
        // reuse the key (and be replayed) instead of creating a second post.
        PublishException unauthorized = of(401, "{\"statusCode\":401}");
        assertEquals(401, unauthorized.status);
        assertFalse(unauthorized.spendsKey());
        assertFalse(new PublishException(PublishException.Kind.SIGNED_OUT).spendsKey());
        // Definitive 4xx answers still rotate it.
        assertTrue(of(403, "{\"message\":\"no\"}").spendsKey());
    }
}
