package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

/** Parsing GET /posts/status and what each state means. */
public class PostStatusTest {

    @Test
    public void parsesEveryState_withUrlAndError() throws Exception {
        List<PostStatus> l = PostStatus.parseList("["
                + "{\"id\":\"a\",\"state\":\"QUEUE\",\"publishDate\":\"2026-10-14T10:00:00.000Z\"},"
                + "{\"id\":\"b\",\"state\":\"PUBLISHED\",\"publishDate\":\"x\",\"releaseURL\":\"https://y.test/1\"},"
                + "{\"id\":\"c\",\"state\":\"ERROR\",\"publishDate\":\"x\",\"error\":\"Token expired\"},"
                + "{\"id\":\"d\",\"state\":\"DELETED\",\"publishDate\":\"x\"},"
                + "{\"id\":\"e\",\"state\":\"DRAFT\",\"publishDate\":\"x\"}]");
        assertEquals(5, l.size());
        assertEquals(PostStatus.State.QUEUE, l.get(0).state);
        assertEquals(PostStatus.State.PUBLISHED, l.get(1).state);
        assertEquals("https://y.test/1", l.get(1).releaseUrl);
        assertEquals(PostStatus.State.ERROR, l.get(2).state);
        assertEquals("Token expired", l.get(2).error);
        assertEquals(PostStatus.State.DELETED, l.get(3).state);
        assertEquals(PostStatus.State.DRAFT, l.get(4).state);
        assertNull(l.get(0).releaseUrl);
        assertNull(l.get(0).error);
    }

    @Test
    public void finalStates_areOnlyPublishedErrorDeleted() {
        assertTrue(PostStatus.State.PUBLISHED.isFinal());
        assertTrue(PostStatus.State.ERROR.isFinal());
        assertTrue(PostStatus.State.DELETED.isFinal());
        assertFalse(PostStatus.State.QUEUE.isFinal());
        assertFalse(PostStatus.State.DRAFT.isFinal());
        assertFalse(PostStatus.State.UNKNOWN.isFinal());
    }

    @Test
    public void unknownOrMissingState_isUnknown_neverFinal() throws Exception {
        List<PostStatus> l = PostStatus.parseList(
                "[{\"id\":\"a\",\"state\":\"SOMETHING_NEW\"},{\"id\":\"b\"},{\"state\":\"ERROR\"},7]");
        assertEquals(2, l.size());
        assertEquals(PostStatus.State.UNKNOWN, l.get(0).state);
        assertEquals(PostStatus.State.UNKNOWN, l.get(1).state);
    }

    @Test
    public void blankAndNullFields_areNull() throws Exception {
        PostStatus s = PostStatus.parseList(
                "[{\"id\":\"a\",\"state\":\"ERROR\",\"error\":\"  \",\"releaseURL\":null}]").get(0);
        assertNull(s.error);
        assertNull(s.releaseUrl);
    }

    @Test(expected = JSONException.class)
    public void aNonArrayBody_isRejected() throws Exception {
        PostStatus.parseList("{\"statusCode\":500}");
    }
}
