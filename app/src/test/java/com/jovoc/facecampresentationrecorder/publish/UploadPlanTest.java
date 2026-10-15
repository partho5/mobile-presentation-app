package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.UploadSession;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The pure decisions of a direct upload: slicing, URL batches, resume, backoff, session checks. */
public class UploadPlanTest {

    private static final long MIB = 1024 * 1024;
    private static final long PART = 16 * MIB;

    private static UploadedPart part(int n, String etag, long size) {
        return new UploadedPart(n, etag, size);
    }

    // ---- slicing ----

    @Test
    public void partCount_isCeilOfSizeOverPartSize() {
        assertEquals(1, UploadPlan.partCountFor(1, PART));
        assertEquals(1, UploadPlan.partCountFor(PART, PART));
        assertEquals(2, UploadPlan.partCountFor(PART + 1, PART));
        assertEquals(64, UploadPlan.partCountFor(1024 * MIB, PART));
        assertEquals(0, UploadPlan.partCountFor(0, PART));
        assertEquals(0, UploadPlan.partCountFor(10, 0));
    }

    @Test
    public void slicing_everyPartFullExceptTheRemainderOfTheLast() {
        long size = 2 * PART + 5;
        assertEquals(0, UploadPlan.partOffset(PART, 1));
        assertEquals(PART, UploadPlan.partOffset(PART, 2));
        assertEquals(2 * PART, UploadPlan.partOffset(PART, 3));
        assertEquals(PART, UploadPlan.partLength(size, PART, 1));
        assertEquals(PART, UploadPlan.partLength(size, PART, 2));
        assertEquals(5, UploadPlan.partLength(size, PART, 3));
    }

    @Test
    public void slicing_exactMultipleMakesTheLastPartFullSize() {
        long size = 3 * PART;
        assertEquals(3, UploadPlan.partCountFor(size, PART));
        assertEquals(PART, UploadPlan.partLength(size, PART, 3));
    }

    @Test
    public void slicing_partLengthsAddUpToTheFile() {
        long size = 1_000_000_007L;
        long partSize = 7 * MIB;
        int count = UploadPlan.partCountFor(size, partSize);
        long total = 0;
        for (int n = 1; n <= count; n++) total += UploadPlan.partLength(size, partSize, n);
        assertEquals(size, total);
    }

    @Test
    public void slicing_usesTheServersPartSizeNotAConstant() {
        // A server that cuts at 8 MiB gets 8 MiB parts.
        long size = 20 * MIB;
        assertEquals(3, UploadPlan.partCountFor(size, 8 * MIB));
        assertEquals(4 * MIB, UploadPlan.partLength(size, 8 * MIB, 3));
    }

    // ---- batching ----

    @Test
    public void nextBatch_takesTheFirstPlusTheNextQueuedUpToTheLimit() {
        List<Integer> queue = Arrays.asList(4, 5, 6, 7, 8, 9);
        List<Integer> batch = UploadPlan.nextBatch(3, queue, n -> false, UploadPlan.URL_BATCH);
        assertEquals(Arrays.asList(3, 4, 5, 6), batch);
    }

    @Test
    public void nextBatch_skipsPartsThatAlreadyHaveAUsableUrl() {
        List<Integer> queue = Arrays.asList(4, 5, 6, 7);
        List<Integer> batch = UploadPlan.nextBatch(3, queue, n -> n == 4 || n == 6, 4);
        assertEquals(Arrays.asList(3, 5, 7), batch);
    }

    @Test
    public void nextBatch_neverExceedsTheServersSignLimitAndNeverRepeatsAPart() {
        List<Integer> queue = new ArrayList<>();
        for (int n = 1; n <= 50; n++) queue.add(n);
        List<Integer> batch = UploadPlan.nextBatch(1, queue, n -> false, DirectUploadApi.MAX_SIGN_PARTS);
        assertEquals(DirectUploadApi.MAX_SIGN_PARTS, batch.size());
        assertEquals(batch.size(), new HashSet<>(batch).size());
        assertTrue(UploadPlan.URL_BATCH <= DirectUploadApi.MAX_SIGN_PARTS);
    }

    @Test
    public void nextBatch_aloneWhenNothingElseWaits() {
        assertEquals(Collections.singletonList(9),
                UploadPlan.nextBatch(9, Collections.<Integer>emptyList(), n -> false, 4));
    }

    @Test
    public void urlUsable_expiresAfterTwoThirdsOfItsLife() {
        assertTrue(UploadPlan.urlUsable(0, 590_000, 900));
        assertFalse(UploadPlan.urlUsable(0, 600_000, 900));
        // No expiresIn in the answer: assume the documented 900 s.
        assertTrue(UploadPlan.urlUsable(0, 590_000, 0));
        assertFalse(UploadPlan.urlUsable(0, 700_000, 0));
    }

    // ---- resume ----

    @Test
    public void missing_listsEveryPartNotFinished() {
        assertEquals(Arrays.asList(2, 4, 5),
                UploadPlan.missing(5, new HashSet<>(Arrays.asList(1, 3))));
        assertTrue(UploadPlan.missing(3, new HashSet<>(Arrays.asList(1, 2, 3))).isEmpty());
    }

    @Test
    public void resume_skipsOnlyPartsR2ListsAtTheRightSize() {
        long size = 3 * 100L - 40; // 3 parts of 100, the last 60
        List<UploadedPart> listed = Arrays.asList(
                part(1, "\"a\"", 100),
                part(2, "\"b\"", 90),   // wrong size: sent again
                part(3, "\"c\"", 60));
        Map<Integer, String> done = UploadPlan.resumeParts(listed, new TreeMap<>(), size, 100, 3);
        assertEquals(2, done.size());
        assertEquals("\"a\"", done.get(1));
        assertEquals("\"c\"", done.get(3));
        assertEquals(Collections.singletonList(2), UploadPlan.missing(3, done.keySet()));
    }

    @Test
    public void resume_trustsR2OverLocalEtags() {
        Map<Integer, String> local = new TreeMap<>();
        local.put(1, "\"old1\"");
        local.put(2, "\"old2\"");
        List<UploadedPart> listed = Collections.singletonList(part(1, "\"new1\"", 100));
        Map<Integer, String> done = UploadPlan.resumeParts(listed, local, 300, 100, 3);
        assertEquals(1, done.size());
        assertEquals("\"new1\"", done.get(1)); // R2's etag, and part 2 is sent again
    }

    @Test
    public void resume_emptyListWithEveryPartStoredGoesStraightToComplete() {
        Map<Integer, String> local = new TreeMap<>();
        local.put(1, "\"a\"");
        local.put(2, "\"b\"");
        Map<Integer, String> done = UploadPlan.resumeParts(
                Collections.<UploadedPart>emptyList(), local, 200, 100, 2);
        assertEquals(local, done);
    }

    @Test
    public void resume_emptyListWithSomePartsStoredSendsEverythingAgain() {
        Map<Integer, String> local = new TreeMap<>();
        local.put(1, "\"a\"");
        Map<Integer, String> done = UploadPlan.resumeParts(
                Collections.<UploadedPart>emptyList(), local, 200, 100, 2);
        assertTrue(done.isEmpty());
    }

    @Test
    public void resume_ignoresPartsOutsideTheRangeOrWithoutEtag() {
        List<UploadedPart> listed = Arrays.asList(
                part(0, "\"z\"", 100), part(4, "\"z\"", 100), part(1, "", 100), part(2, "\"b\"", -1));
        assertTrue(UploadPlan.resumeParts(listed, new TreeMap<>(), 300, 100, 3).isEmpty());
    }

    @Test
    public void bytesOf_sumsTheRealLengthsIncludingTheShortLastPart() {
        assertEquals(160, UploadPlan.bytesOf(Arrays.asList(1, 3), 260, 100));
    }

    // ---- backoff ----

    @Test
    public void backoff_doublesFromOneSecondAndCapsAtThirty() {
        assertEquals(1_000, UploadPlan.backoffMillis(1));
        assertEquals(2_000, UploadPlan.backoffMillis(2));
        assertEquals(4_000, UploadPlan.backoffMillis(3));
        assertEquals(8_000, UploadPlan.backoffMillis(4));
        assertEquals(16_000, UploadPlan.backoffMillis(5));
        assertEquals(30_000, UploadPlan.backoffMillis(6));
        assertEquals(30_000, UploadPlan.backoffMillis(99));
        assertEquals(1_000, UploadPlan.backoffMillis(0));
    }

    // ---- session validity ----

    private static UploadSession session(long size, long partSize, int partCount) {
        UploadSession s = new UploadSession();
        s.jobId = 1;
        s.uploadId = "up1";
        s.size = size;
        s.partSize = partSize;
        s.partCount = partCount;
        s.status = UploadSession.PENDING;
        return s;
    }

    @Test
    public void session_usableOnlyForTheSameFileSize() {
        UploadSession s = session(250, 100, 3);
        assertTrue(UploadPlan.isUsable(s, 250));
        assertFalse(UploadPlan.isUsable(s, 251)); // re-processed recording
        assertFalse(UploadPlan.isUsable(s, 249));
    }

    @Test
    public void session_unusableWhenItsOwnNumbersDoNotFit() {
        assertFalse(UploadPlan.isUsable(null, 250));
        assertFalse(UploadPlan.isUsable(session(250, 100, 4), 250));
        assertFalse(UploadPlan.isUsable(session(250, 0, 3), 250));
        UploadSession noId = session(250, 100, 3);
        noId.uploadId = "";
        assertFalse(UploadPlan.isUsable(noId, 250));
    }

    // ---- stored parts ----

    @Test
    public void partsJson_roundTripsEtagsWithQuotesInOrder() {
        Map<Integer, String> parts = new TreeMap<>();
        parts.put(3, "\"c\"");
        parts.put(1, "\"a\"");
        String json = UploadPlan.partsToJson(parts);
        assertEquals(parts, UploadPlan.partsFromJson(json));
        assertTrue(json.indexOf("\"n\":1") < json.indexOf("\"n\":3"));
    }

    @Test
    public void partsJson_garbageIsNoParts() {
        assertTrue(UploadPlan.partsFromJson(null).isEmpty());
        assertTrue(UploadPlan.partsFromJson("").isEmpty());
        assertTrue(UploadPlan.partsFromJson("not json").isEmpty());
        assertTrue(UploadPlan.partsFromJson("[]").isEmpty());
    }
}
