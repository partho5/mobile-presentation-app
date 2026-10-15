package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.UploadSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * The arithmetic and decisions of a direct upload that need no network or database: how a file is
 * cut, which parts are still missing, how URL batches and retries are paced, and whether a stored
 * session can still be used. Everything here is a pure function of its arguments.
 */
final class UploadPlan {

    private UploadPlan() {}

    /** Part PUTs in flight at once (more only competes for the same uplink). */
    static final int PARALLEL_PARTS = 2;
    /** Tries per part before the upload gives up and reports the failure. */
    static final int MAX_TRIES = 5;
    /**
     * Part numbers per signing call. A URL dies 900 s after signing, so URLs are asked for as the
     * parts are about to be sent, a few at a time, never all (the server allows 20 per call).
     */
    static final int URL_BATCH = 4;
    static final long BACKOFF_BASE_MS = 1_000L;
    static final long BACKOFF_MAX_MS = 30_000L;
    /** A signed URL is not used once it is this share (percent) of its life old. */
    static final int URL_LIFE_USED_PERCENT = 66;
    static final int DEFAULT_URL_LIFE_SECONDS = 900;

    // ---- slicing ----

    /** ceil(size / partSize): how many parts a file of {@code size} bytes has. */
    static int partCountFor(long size, long partSize) {
        if (size <= 0 || partSize <= 0) return 0;
        long n = (size + partSize - 1) / partSize;
        return n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
    }

    /** Byte offset of part {@code n} (1-based) in the file. */
    static long partOffset(long partSize, int n) {
        return (n - 1L) * partSize;
    }

    /**
     * Exact byte length of part {@code n}: {@code partSize} for every part but the last, and the
     * remainder (a full part when the size is an exact multiple) for the last.
     */
    static long partLength(long size, long partSize, int n) {
        long offset = partOffset(partSize, n);
        return Math.max(0, Math.min(partSize, size - offset));
    }

    // ---- what is left to send ----

    /** Part numbers in 1..partCount that are not in {@code finished}, ascending. */
    static List<Integer> missing(int partCount, Set<Integer> finished) {
        List<Integer> out = new ArrayList<>();
        for (int n = 1; n <= partCount; n++) {
            if (!finished.contains(n)) out.add(n);
        }
        return out;
    }

    /** True when {@code finished} holds a part for every number 1..partCount. */
    static boolean isComplete(Collection<Integer> finished, int partCount) {
        if (partCount <= 0) return false;
        for (int n = 1; n <= partCount; n++) {
            if (!finished.contains(n)) return false;
        }
        return true;
    }

    /**
     * The parts R2 holds that are whole: right number, an ETag, and exactly the expected length
     * (an unknown length counts as not whole). Part number to ETag.
     */
    static Map<Integer, String> wholeServerParts(List<UploadedPart> listed, long size, long partSize,
                                                 int partCount) {
        Map<Integer, String> out = new TreeMap<>();
        if (listed == null) return out;
        for (UploadedPart p : listed) {
            if (p.n < 1 || p.n > partCount || p.etag == null || p.etag.isEmpty()) continue;
            if (p.size != partLength(size, partSize, p.n)) continue;
            out.put(p.n, p.etag);
        }
        return out;
    }

    /**
     * Which parts count as finished on resume. R2's list is the truth, not the local etags: a part
     * R2 does not list is sent again. The one exception is the documented case of an empty list
     * while every part is stored locally (a complete call ended 502 after R2 finished the file,
     * or R2 no longer lists a finished upload): then the stored etags go straight to complete.
     */
    static Map<Integer, String> resumeParts(List<UploadedPart> listed, Map<Integer, String> local,
                                            long size, long partSize, int partCount) {
        Map<Integer, String> server = wholeServerParts(listed, size, partSize, partCount);
        if (server.isEmpty() && isComplete(local.keySet(), partCount)) {
            return new TreeMap<>(local);
        }
        return server;
    }

    /** Bytes of the given parts together. */
    static long bytesOf(Collection<Integer> parts, long size, long partSize) {
        long total = 0;
        for (int n : parts) total += partLength(size, partSize, n);
        return total;
    }

    // ---- URL batches ----

    /**
     * The part numbers to sign in one call, when part {@code first} needs a URL: it, then the
     * next parts waiting in the queue that have no usable URL yet, at most {@code max} in all.
     * {@code hasUsableUrl} says whether a part already has a fresh URL.
     */
    static List<Integer> nextBatch(int first, Collection<Integer> queued, IntPredicate hasUsableUrl,
                                   int max) {
        List<Integer> batch = new ArrayList<>();
        batch.add(first);
        for (int n : queued) {
            if (batch.size() >= max) break;
            if (n != first && !batch.contains(n) && !hasUsableUrl.test(n)) batch.add(n);
        }
        return batch;
    }

    /** True while a URL signed at {@code signedAtMs} is young enough to use at {@code nowMs}. */
    static boolean urlUsable(long signedAtMs, long nowMs, int lifeSeconds) {
        int life = lifeSeconds > 0 ? lifeSeconds : DEFAULT_URL_LIFE_SECONDS;
        long usableMs = life * 1000L * URL_LIFE_USED_PERCENT / 100;
        return nowMs - signedAtMs < usableMs;
    }

    // ---- retry ----

    /** Wait after the failed try number {@code attempt} (1-based): 1 s, 2 s, 4 s, ... up to 30 s. */
    static long backoffMillis(int attempt) {
        if (attempt < 1) attempt = 1;
        if (attempt > 20) return BACKOFF_MAX_MS;
        return Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << (attempt - 1));
    }

    // ---- stored session ----

    /**
     * True when a stored session may be resumed for a file of {@code fileSize} bytes now. A file
     * of another size (the recording was re-processed) is another object: abort and start over.
     */
    static boolean isUsable(UploadSession s, long fileSize) {
        return s != null && s.uploadId != null && !s.uploadId.isEmpty()
                && s.size == fileSize && s.partSize > 0 && s.partCount > 0
                && s.partCount == partCountFor(s.size, s.partSize);
    }

    // ---- stored parts ----

    /** [{"n":1,"etag":"..."}], ascending by part number. */
    static String partsToJson(Map<Integer, String> parts) {
        JSONArray a = new JSONArray();
        for (Map.Entry<Integer, String> e : new TreeMap<>(parts).entrySet()) {
            try {
                a.put(new JSONObject().put("n", e.getKey()).put("etag", e.getValue()));
            } catch (JSONException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return a.toString();
    }

    /** Reads {@link #partsToJson}; unreadable or empty text is no parts (R2's list decides anyway). */
    static Map<Integer, String> partsFromJson(String json) {
        Map<Integer, String> out = new TreeMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                int n = o.optInt("n", 0);
                String etag = o.optString("etag", "");
                if (n > 0 && !etag.isEmpty()) out.put(n, etag);
            }
        } catch (JSONException e) {
            return new TreeMap<>();
        }
        return out;
    }
}
