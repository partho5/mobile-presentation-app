package com.jovoc.facecampresentationrecorder.aiedit.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderNotes;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class RenderReceiptTest {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern DEVICE = Pattern.compile("^[A-Za-z0-9._+ -]{1,64}$");
    private static final String SHA = RenderReceipt.sha256Hex("{}");

    private static JSONObject build(List<RenderReceipt.Output> outs, RenderNotes notes) throws Exception {
        return new JSONObject(RenderReceipt.build("attempt-1", SHA, true, "1.0", "Pixel 7", outs, notes.notes()));
    }

    @Test
    public void hashIsTheSha256OfTheBodyBytes() {
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", RenderReceipt.sha256Hex("{}"));
        // multi-byte text is hashed as UTF-8, the bytes that were received
        assertEquals(RenderReceipt.sha256Hex("héllo".getBytes(StandardCharsets.UTF_8)),
                RenderReceipt.sha256Hex("héllo"));
        assertTrue(RenderReceipt.sha256Hex("x").matches("^[a-f0-9]{64}$"));
    }

    @Test
    public void receiptCarriesTheContractFields() throws Exception {
        List<RenderReceipt.Output> outs = Arrays.asList(
                RenderReceipt.Output.ok("9:16", 12_345, 4_000_000),
                RenderReceipt.Output.failed("4:5", "RENDER_FAILED", 900));
        JSONObject o = build(outs, new RenderNotes());
        assertEquals("attempt-1", o.getString("attemptId"));
        assertEquals(SHA, o.getString("served_sha256"));
        assertTrue(o.getBoolean("parse_ok"));
        assertEquals("1.0", o.getString("app_version"));
        assertEquals("Pixel 7", o.getString("device"));
        JSONArray a = o.getJSONArray("outputs");
        assertEquals(2, a.length());
        JSONObject first = a.getJSONObject(0);
        assertEquals("9:16", first.getString("ratio"));
        assertTrue(first.getBoolean("ok"));
        assertFalse(first.has("error_code"));
        assertEquals(12_345, first.getLong("duration_ms"));
        assertEquals(4_000_000, first.getLong("bytes"));
        JSONObject second = a.getJSONObject(1);
        assertFalse(second.getBoolean("ok"));
        assertEquals("RENDER_FAILED", second.getString("error_code"));
        assertFalse(second.has("bytes"));
        assertEquals(0, o.getJSONArray("warnings").length());
    }

    @Test
    public void onlyAcceptedFieldsAreSent() throws Exception {
        JSONObject o = build(Arrays.asList(RenderReceipt.Output.ok("1:1", 1, 1)), new RenderNotes());
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys);
        assertEquals(Arrays.asList("app_version", "attemptId", "device", "outputs", "parse_ok", "served_sha256",
                "warnings"), keys);
    }

    @Test
    public void errorCodeIsDroppedWhenItIsNotAPlainCode() throws Exception {
        JSONObject o = build(Arrays.asList(
                RenderReceipt.Output.failed("9:16", "failed: /storage/emulated/0/DCIM/x.mp4", 5)), new RenderNotes());
        JSONObject out = o.getJSONArray("outputs").getJSONObject(0);
        assertFalse(out.getBoolean("ok"));
        assertFalse(out.has("error_code"));
        assertFalse(o.toString().contains("/storage"));
    }

    @Test
    public void unknownRatiosAreDroppedAndOutputsCapAtThree() throws Exception {
        List<RenderReceipt.Output> outs = new ArrayList<>();
        outs.add(RenderReceipt.Output.ok("16:9", 1, 1));
        outs.add(RenderReceipt.Output.ok("9:16", 1, 1));
        outs.add(RenderReceipt.Output.ok("4:5", 1, 1));
        outs.add(RenderReceipt.Output.ok("1:1", 1, 1));
        outs.add(RenderReceipt.Output.ok("1:1", 1, 1));
        JSONArray a = build(outs, new RenderNotes()).getJSONArray("outputs");
        assertEquals(3, a.length());
        assertEquals("9:16", a.getJSONObject(0).getString("ratio"));
    }

    @Test
    public void warningsAreCodesAndItemIdsOnly() throws Exception {
        RenderNotes notes = new RenderNotes();
        notes.add(RenderNotes.OVERLAY_SKIPPED_NO_ROOM, "item_3");
        notes.add(RenderNotes.TEXT_SHRUNK, "it-4");
        notes.add(RenderNotes.ASSET_MISSING, null);
        notes.add(RenderNotes.TEXT_SHRUNK, "it-4");            // a repeat
        notes.add("some_new_code", "item_9");                  // not a code the server takes
        notes.add(RenderNotes.TEXT_SHRUNK, "has a space/é"); // not a plain id: the code stays, the id goes
        JSONArray w = build(Arrays.asList(RenderReceipt.Output.ok("9:16", 1, 1)), notes).getJSONArray("warnings");
        assertEquals(4, w.length());
        assertEquals("overlay_skipped_no_room", w.getJSONObject(0).getString("code"));
        assertEquals("item_3", w.getJSONObject(0).getString("item_id"));
        assertEquals("text_shrunk", w.getJSONObject(1).getString("code"));
        assertEquals("asset_missing", w.getJSONObject(2).getString("code"));
        assertFalse(w.getJSONObject(2).has("item_id"));
        assertEquals("text_shrunk", w.getJSONObject(3).getString("code"));
        assertFalse(w.getJSONObject(3).has("item_id"));
    }

    @Test
    public void notesAreBoundedAtFifty() {
        RenderNotes notes = new RenderNotes();
        for (int i = 0; i < 80; i++) notes.add(RenderNotes.TEXT_SHRUNK, "item" + i);
        assertEquals(50, notes.notes().size());
    }

    @Test
    public void deviceIsSanitisedAndBounded() {
        assertEquals("Pixel 7 Pro", RenderReceipt.sanitizeDevice("Pixel 7 Pro"));
        assertEquals("SM-G991B", RenderReceipt.sanitizeDevice("SM-G991B"));
        String odd = RenderReceipt.sanitizeDevice("Phöne (X) <b>/\"");
        assertTrue(odd, DEVICE.matcher(odd).matches());
        String longName = RenderReceipt.sanitizeDevice(repeat("a", 200));
        assertEquals(64, longName.length());
        assertEquals("unknown", RenderReceipt.sanitizeDevice(null));
        assertEquals("unknown", RenderReceipt.sanitizeDevice("   "));
        assertTrue(DEVICE.matcher(RenderReceipt.sanitizeDevice("  x  ")).matches());
    }

    @Test
    public void versionIsSanitisedAndBounded() {
        assertEquals("1.0", RenderReceipt.sanitizeVersion("1.0"));
        assertEquals("1.0_beta", RenderReceipt.sanitizeVersion("1.0 beta"));
        assertEquals(32, RenderReceipt.sanitizeVersion(repeat("1", 99)).length());
        assertEquals("unknown", RenderReceipt.sanitizeVersion(""));
    }

    @Test
    public void builtReceiptHasNoCharacterOutsideTheAcceptedCharsets() throws Exception {
        RenderNotes notes = new RenderNotes();
        notes.add(RenderNotes.TEXT_SHRUNK, "x1");
        JSONObject o = new JSONObject(RenderReceipt.build("a-b_c.1", SHA, false, "1.0.0-rc+1", "Phöne",
                Arrays.asList(RenderReceipt.Output.failed("9:16", "BAD_PLAN", 3)), notes.notes()));
        assertTrue(CODE.matcher(o.getString("attemptId")).matches());
        assertTrue(DEVICE.matcher(o.getString("device")).matches());
        assertFalse(o.getBoolean("parse_ok"));
    }

    // ---- the pending store and the retry ----

    private static final class MemoryStore implements AiEditStore.Store {
        final Map<String, String> map = new HashMap<>();

        @Override public String get(String key) { return map.get(key); }
        @Override public void put(String key, String value) { map.put(key, value); }
        @Override public void remove(String key) { map.remove(key); }
        @Override public Collection<String> keys() { return new ArrayList<>(map.keySet()); }
    }

    @Test
    public void pendingReceiptsAreBoundedAndOldestDropped() {
        AiEditStore store = new AiEditStore(new MemoryStore());
        for (int i = 0; i < 8; i++) {
            store.savePendingReceipt(new AiEditStore.PendingReceipt("a" + i, "e", null, "{}", 100 + i));
        }
        List<AiEditStore.PendingReceipt> left = store.pendingReceipts();
        assertEquals(AiEditStore.MAX_PENDING_RECEIPTS, left.size());
        assertEquals("a3", left.get(0).attemptId);
        assertEquals("a7", left.get(4).attemptId);
    }

    @Test
    public void flushKeepsWhatCouldNotBeSentAndDropsWhatIsDone() {
        AiEditStore store = new AiEditStore(new MemoryStore());
        store.savePendingReceipt(new AiEditStore.PendingReceipt("old", "e", null, "{}", 1));
        store.savePendingReceipt(new AiEditStore.PendingReceipt("ok", "e", "w", "{\"x\":1}", 1_000_000_000L));
        store.savePendingReceipt(new AiEditStore.PendingReceipt("net", "e2", null, "{}", 1_000_000_001L));
        final List<String> sent = new ArrayList<>();
        AiEditApi api = new AiEditApi(null) {
            @Override
            public void reportRender(String editId, String body, String workspaceId) throws AiEditException {
                if ("e2".equals(editId)) throw new AiEditException(AiEditException.NETWORK, true);
                sent.add(editId + "|" + workspaceId + "|" + body);
            }
        };
        AiEditReceipts.flush(store, api, 1_000_000_000L + AiEditStore.RECEIPT_MAX_AGE_MS - 1);
        assertEquals(Arrays.asList("e|w|{\"x\":1}"), sent);
        List<AiEditStore.PendingReceipt> left = store.pendingReceipts();
        assertEquals(1, left.size());
        assertEquals("net", left.get(0).attemptId);
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
