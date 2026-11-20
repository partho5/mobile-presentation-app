package com.jovoc.facecampresentationrecorder.aiedit.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jovoc.facecampresentationrecorder.publish.AiCaptionStatus;
import com.jovoc.facecampresentationrecorder.publish.PublishException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TranscriptResolverTest {

    /** A phone and a server that only act as told; time moves only when the resolver sleeps. */
    private static final class FakeEnv implements TranscriptResolver.Env {
        int transcribingChecks;   // isTranscribing() is true this many more times
        String savedId;
        boolean cached;
        String lastFailure;
        List<Object> statuses = new ArrayList<>(); // AiCaptionStatus or PublishException, taken in order
        long clock;
        int statusCalls;
        String keptWords;
        boolean forgotten;
        int shown;

        @Override public boolean isTranscribing() {
            if (transcribingChecks > 0) {
                transcribingChecks--;
                return true;
            }
            return false;
        }
        @Override public String savedCaptionId() { return savedId; }
        @Override public boolean wordsCached() { return cached; }
        @Override public String lastFailure() { return lastFailure; }
        @Override public AiCaptionStatus captionStatus(String id) throws PublishException {
            statusCalls++;
            Object next = statuses.size() > 1 ? statuses.remove(0) : statuses.get(0);
            if (next instanceof PublishException) throw (PublishException) next;
            return (AiCaptionStatus) next;
        }
        @Override public void keepWords(String json) { keptWords = json; }
        @Override public void forgetCaptionId() { forgotten = true; savedId = null; }
        @Override public void showTranscribing() { shown++; }
        @Override public long now() { return clock; }
        @Override public void sleep(long millis) { clock += millis; }
    }

    private static AiEditJob job() {
        return new AiEditJob("/v/a.mp4", "basic", "s", "w", Collections.singletonList("9:16"), "auto", false);
    }

    private static AiCaptionStatus ready() { return new AiCaptionStatus(AiCaptionStatus.READY, "{\"words\":[]}"); }

    @Test
    public void neverTranscribed_sendsAudio() throws Exception {
        FakeEnv env = new FakeEnv();
        AiEditJob job = job();
        TranscriptResolver.resolve(job, env);
        assertNull(job.aiCaptionId);
    }

    @Test
    public void cachedWordsAndId_usesIdWithoutAnyCall() throws Exception {
        FakeEnv env = new FakeEnv();
        env.savedId = "cap1";
        env.cached = true;
        AiEditJob job = job();
        TranscriptResolver.resolve(job, env);
        assertEquals("cap1", job.aiCaptionId);
        assertEquals(0, env.statusCalls);
    }

    @Test
    public void runningTranscription_isWaitedForThenItsIdIsUsed() throws Exception {
        FakeEnv env = new FakeEnv();
        env.transcribingChecks = 5;
        env.savedId = "cap1";
        env.cached = true;
        AiEditJob job = job();
        TranscriptResolver.resolve(job, env);
        assertEquals("cap1", job.aiCaptionId);
        assertEquals(4 * TranscriptResolver.POLL_MS, env.clock);
        assertTrue(env.shown >= 1);
    }

    @Test
    public void runningTranscriptionThatFailed_sendsAudioOnce() throws Exception {
        FakeEnv env = new FakeEnv();
        env.transcribingChecks = 2;
        env.lastFailure = "failed";
        AiEditJob job = job();
        TranscriptResolver.resolve(job, env);
        assertNull(job.aiCaptionId);
    }

    @Test
    public void earlierNoSpeech_stopsInsteadOfUploadingAudio() {
        FakeEnv env = new FakeEnv();
        env.lastFailure = "no_speech";
        try {
            TranscriptResolver.resolve(job(), env);
            fail();
        } catch (AiEditException e) {
            assertEquals(AiEditException.NO_SPEECH, e.code);
            assertFalse(e.retryable);
        }
    }

    @Test
    public void idWithoutWords_fetchesTheWordsInsteadOfAudio() throws Exception {
        FakeEnv env = new FakeEnv();
        env.savedId = "cap1";
        env.statuses.add(new AiCaptionStatus(AiCaptionStatus.TRANSCRIBING));
        env.statuses.add(ready());
        AiEditJob job = job();
        TranscriptResolver.resolve(job, env);
        assertEquals("cap1", job.aiCaptionId);
        assertEquals("{\"words\":[]}", env.keptWords);
        assertEquals(2, env.statusCalls);
    }

    @Test
    public void idWithoutWords_serverUnreachable_failsRetryableNeverAudio() {
        FakeEnv env = new FakeEnv();
        env.savedId = "cap1";
        env.statuses.add(new PublishException(PublishException.Kind.NETWORK));
        AiEditJob job = job();
        try {
            TranscriptResolver.resolve(job, env);
            fail();
        } catch (AiEditException e) {
            assertEquals(AiEditException.TIMEOUT, e.code);
            assertTrue(e.retryable);
            assertEquals("cap1", job.aiCaptionId); // still the caption: the retry fetches it again
        }
    }

    @Test
    public void captionNoSpeech_forgetsIdAndStops() {
        FakeEnv env = new FakeEnv();
        env.savedId = "cap1";
        env.statuses.add(new AiCaptionStatus(AiCaptionStatus.NO_SPEECH));
        AiEditJob job = job();
        try {
            TranscriptResolver.resolve(job, env);
            fail();
        } catch (AiEditException e) {
            assertEquals(AiEditException.NO_SPEECH, e.code);
            assertTrue(env.forgotten);
            assertNull(job.aiCaptionId);
        }
    }

    @Test
    public void captionFailedOnServer_forgetsIdAndSendsAudio() throws Exception {
        FakeEnv env = new FakeEnv();
        env.savedId = "cap1";
        env.statuses.add(new AiCaptionStatus(AiCaptionStatus.FAILED));
        AiEditJob job = job();
        TranscriptResolver.resolve(job, env);
        assertNull(job.aiCaptionId);
        assertTrue(env.forgotten);
    }

    @Test
    public void captionUnknownToServer_forgetsIdAndSendsAudio() throws Exception {
        FakeEnv env = new FakeEnv();
        env.savedId = "cap1";
        env.statuses.add(new PublishException(PublishException.Kind.REJECTED));
        AiEditJob job = job();
        TranscriptResolver.resolve(job, env);
        assertNull(job.aiCaptionId);
        assertTrue(env.forgotten);
    }

    @Test
    public void audioAlreadyChosen_isLeftAlone() throws Exception {
        FakeEnv env = new FakeEnv();
        env.savedId = "cap1";
        AiEditJob forced = job();
        forced.forceAudio = true;
        TranscriptResolver.resolve(forced, env);
        assertNull(forced.aiCaptionId);

        AiEditJob uploaded = job();
        uploaded.audioUploadId = "aud1";
        TranscriptResolver.resolve(uploaded, env);
        assertNull(uploaded.aiCaptionId);
        assertEquals(0, env.statusCalls);
    }
}
