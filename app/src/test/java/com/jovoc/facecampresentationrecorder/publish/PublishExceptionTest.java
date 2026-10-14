package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void validationErrorShowsTheReadableReason() {
        PublishException e = of(400, "{\"statusCode\":400,\"provider\":\"x\",\"name\":\"X\","
                + "\"message\":\"post is too long, please fix it\"}");
        assertEquals(PublishException.Kind.REJECTED, e.kind);
        assertEquals("post is too long, please fix it", e.detail);
        assertTrue(e.spendsKey());
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
