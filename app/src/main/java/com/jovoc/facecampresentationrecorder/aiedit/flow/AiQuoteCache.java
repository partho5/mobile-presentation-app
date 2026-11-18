package com.jovoc.facecampresentationrecorder.aiedit.flow;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers what an AI edit costs, so the "AI Edit · N credits" button has its number the moment a preview opens
 * and the server is asked once per workspace and length class, not once per preview. A price is per started 3
 * minutes, so every length inside one class shares an entry. Entries expire after {@link #TTL_MS} so a changed
 * price list is picked up. Pure; the clock is given.
 */
public final class AiQuoteCache {

    /** The server prices per started block of this many seconds. */
    static final long BLOCK_SECONDS = 180;
    static final long TTL_MS = 10 * 60 * 1000L;

    /** Asks the server; {@link AiEditApi#quote} in the app. */
    public interface Loader {
        AiEditApi.Quote load(long seconds, String workspaceId) throws AiEditException;
    }

    /** The current time in ms. */
    public interface Clock {
        long now();
    }

    private static final AiQuoteCache SHARED = new AiQuoteCache(System::currentTimeMillis);

    private static final class Entry {
        final AiEditApi.Quote quote;
        final long atMs;

        Entry(AiEditApi.Quote quote, long atMs) {
            this.quote = quote;
            this.atMs = atMs;
        }
    }

    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public AiQuoteCache(Clock clock) {
        this.clock = clock;
    }

    /** The cache the screens share. */
    public static AiQuoteCache shared() {
        return SHARED;
    }

    /** The price of an edit of {@code seconds} for {@code workspaceId} if it is known and fresh, else null. */
    public synchronized AiEditApi.Quote peek(long seconds, String workspaceId) {
        String key = key(seconds, workspaceId);
        Entry e = entries.get(key);
        if (e == null) return null;
        if (clock.now() - e.atMs > TTL_MS) {
            entries.remove(key);
            return null;
        }
        return e.quote;
    }

    /**
     * The price from the cache, else from {@code loader} (blocking, so off the main thread), which is then
     * remembered. A failed load is not remembered and is thrown.
     */
    public AiEditApi.Quote get(long seconds, String workspaceId, Loader loader) throws AiEditException {
        AiEditApi.Quote known = peek(seconds, workspaceId);
        if (known != null) return known;
        AiEditApi.Quote fresh = loader.load(seconds, workspaceId);
        synchronized (this) {
            entries.put(key(seconds, workspaceId), new Entry(fresh, clock.now()));
        }
        return fresh;
    }

    /** Forgets every price (the workspace or the plan changed). */
    public synchronized void clear() {
        entries.clear();
    }

    /** Workspace plus the number of started blocks: 1..180 s is block 1, 181..360 s block 2, ... */
    static String key(long seconds, String workspaceId) {
        long blocks = Math.max(1, (Math.max(1, seconds) + BLOCK_SECONDS - 1) / BLOCK_SECONDS);
        return (workspaceId == null ? "" : workspaceId) + "|" + blocks;
    }
}
