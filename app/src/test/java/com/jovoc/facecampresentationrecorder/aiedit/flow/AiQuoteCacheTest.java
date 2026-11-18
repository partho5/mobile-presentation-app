package com.jovoc.facecampresentationrecorder.aiedit.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AiQuoteCacheTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicInteger loads = new AtomicInteger();
    private final AiQuoteCache cache = new AiQuoteCache(now::get);
    private final AiQuoteCache.Loader loader = (seconds, ws) -> {
        loads.incrementAndGet();
        return new AiEditApi.Quote(1, 2, 3);
    };

    @Test
    public void lengthsInOneStartedBlockShareAnEntry() throws Exception {
        cache.get(30, "w1", loader);
        cache.get(180, "w1", loader);
        assertEquals(1, loads.get());
        cache.get(181, "w1", loader);
        assertEquals(2, loads.get());
    }

    @Test
    public void workspacesDoNotShareEntries() throws Exception {
        cache.get(60, "w1", loader);
        cache.get(60, "w2", loader);
        assertEquals(2, loads.get());
        assertNotNull(cache.peek(60, "w2"));
        assertNull(cache.peek(60, "w3"));
    }

    @Test
    public void anEntryExpires() throws Exception {
        cache.get(60, "w1", loader);
        now.addAndGet(AiQuoteCache.TTL_MS + 1);
        assertNull(cache.peek(60, "w1"));
        cache.get(60, "w1", loader);
        assertEquals(2, loads.get());
    }

    @Test
    public void aFailedLoadIsNotRemembered() {
        try {
            cache.get(60, "w1", (s, w) -> {
                throw new AiEditException(AiEditException.NETWORK, true);
            });
            fail();
        } catch (AiEditException e) {
            assertEquals(AiEditException.NETWORK, e.code);
        }
        assertNull(cache.peek(60, "w1"));
    }

    @Test
    public void quoteReadsTheLevelsByName() {
        AiEditApi.Quote q = new AiEditApi.Quote(1, 2, 3);
        assertEquals(1, q.forLevel("basic"));
        assertEquals(2, q.forLevel("standard"));
        assertEquals(3, q.forLevel("heavy"));
        assertEquals(0, q.forLevel("other"));
    }
}
