package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PublishRequestBuilderTest {

    private static PublishJob job(String mode) {
        PublishJob j = new PublishJob();
        j.fileName = "demo-talk.mp4";
        j.caption = "Hello <world> & friends\nsecond line";
        j.mode = mode;
        j.scheduleAt = 1_790_000_000_000L; // 2026-09-21T... UTC
        j.mediaId = "m1";
        j.mediaPath = "https://cdn.example.com/up/abc.mp4";
        j.channelsJson = Channel.toJson(Arrays.asList(
                new Channel("c1", "My YT", "youtube", null, false),
                new Channel("c2", "My X", "x", null, false)));
        return j;
    }

    @Test
    public void nowPostHasOneEntryPerChannelWithTheVideo() throws Exception {
        JSONObject b = new JSONObject(PublishRequestBuilder.createPostBody(job(PublishJob.MODE_NOW),
                1_700_000_000_000L));
        assertEquals("now", b.getString("type"));
        assertFalse(b.getBoolean("shortLink"));
        assertEquals("2023-11-14T22:13:20.000Z", b.getString("date"));
        assertEquals(0, b.getJSONArray("tags").length());

        JSONArray posts = b.getJSONArray("posts");
        assertEquals(2, posts.length());
        JSONObject first = posts.getJSONObject(0);
        assertEquals("c1", first.getJSONObject("integration").getString("id"));
        JSONObject value = first.getJSONArray("value").getJSONObject(0);
        assertEquals("<p>Hello &lt;world&gt; &amp; friends</p><p>second line</p>", value.getString("content"));
        JSONObject media = value.getJSONArray("image").getJSONObject(0);
        assertEquals("m1", media.getString("id"));
        assertEquals("https://cdn.example.com/up/abc.mp4", media.getString("path"));
    }

    @Test
    public void schedulePostUsesTheChosenTimeInUtc() throws Exception {
        JSONObject b = new JSONObject(PublishRequestBuilder.createPostBody(job(PublishJob.MODE_SCHEDULE), 0L));
        assertEquals("schedule", b.getString("type"));
        assertEquals(PublishRequestBuilder.isoUtc(1_790_000_000_000L), b.getString("date"));
        assertTrue(b.getString("date").endsWith("Z"));
    }

    @Test
    public void providerSettingsAreFilledForKnownProviders() throws Exception {
        JSONObject b = new JSONObject(PublishRequestBuilder.createPostBody(job(PublishJob.MODE_NOW), 0L));
        JSONObject yt = b.getJSONArray("posts").getJSONObject(0).getJSONObject("settings");
        assertEquals("Hello <world> & friends", yt.getString("title"));
        assertEquals("public", yt.getString("type"));
        JSONObject x = b.getJSONArray("posts").getJSONObject(1).getJSONObject("settings");
        assertEquals("everyone", x.getString("who_can_reply_post"));
    }

    @Test
    public void titleFallsBackToFileNameAndIsClamped() {
        assertEquals("demo-talk", PublishRequestBuilder.firstLine("", "demo-talk.mp4"));
        assertEquals("Video", ProviderSettings.clamp("a", 2, 100));
        assertEquals(100, ProviderSettings.clamp(new String(new char[300]).replace('\0', 'x'), 2, 100).length());
        assertEquals(0, ProviderSettings.forProvider("unknown-provider", "t").length());
    }

    @Test
    public void emptyCaptionStaysEmpty() {
        assertEquals("", PublishRequestBuilder.captionToHtml("  "));
        assertEquals("", PublishRequestBuilder.captionToHtml(null));
    }

    @Test
    public void chargePreviewBodyListsChannelIds() throws Exception {
        List<String> ids = Arrays.asList("a", "b");
        JSONObject o = new JSONObject(PublishRequestBuilder.chargePreviewBody(ids));
        assertEquals("b", o.getJSONArray("integrationIds").getString(1));
    }

    @Test
    public void postIdsAreReadFromTheResponse() throws Exception {
        List<String> ids = PublishRequestBuilder.postIds(
                "[{\"postId\":\"p1\",\"integration\":\"c1\"},{\"postId\":\"p2\",\"integration\":\"c2\"}]");
        assertEquals(Arrays.asList("p1", "p2"), ids);
        assertTrue(PublishRequestBuilder.postIds("[]").isEmpty());
    }

    @Test
    public void channelListSkipsIncompleteEntries() throws Exception {
        List<Channel> list = Channel.parseList("[{\"id\":\"1\",\"name\":\"A\",\"identifier\":\"tiktok\","
                + "\"picture\":null,\"disabled\":true},{\"name\":\"no id\"}]");
        assertEquals(1, list.size());
        assertTrue(list.get(0).disabled);
        assertEquals("tiktok", list.get(0).identifier);
    }
}
