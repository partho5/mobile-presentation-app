package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.db.AudioUploadSession;
import com.jovoc.facecampresentationrecorder.db.AudioUploadSessionDao;
import com.jovoc.facecampresentationrecorder.db.UploadSession;
import com.jovoc.facecampresentationrecorder.db.UploadSessionDao;
import com.jovoc.facecampresentationrecorder.db.UploadSessionStore;

import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Purpose, allowed file type and session store per upload kind. */
public class UploadKindTest {

    @Test
    public void wavIsForAudioOnly() {
        assertTrue(UploadKind.AUDIO.allows("job-1.wav"));
        assertTrue(UploadKind.AUDIO.allows("JOB.WAV"));
        assertFalse(UploadKind.AUDIO.allows("rec.mp4"));
        assertTrue(UploadKind.VIDEO.allows("rec.mp4"));
        assertFalse(UploadKind.VIDEO.allows("job-1.wav"));
        assertFalse(UploadKind.AUDIO.allows(""));
        assertFalse(UploadKind.VIDEO.allows(null));
    }

    @Test
    public void onlyAudioSendsAPurpose() {
        assertNull(UploadKind.VIDEO.purpose);
        assertEquals("transcript_audio", UploadKind.AUDIO.purpose);
    }

    @Test
    public void createRefusesAWavForVideoAndAnMp4ForAudio() throws Exception {
        DirectUploadApi api = new DirectUploadApi(null);
        try {
            api.create("a.wav", 10, null, UploadKind.VIDEO);
            throw new AssertionError("wav as video");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            api.create("a.mp4", 10, null, UploadKind.AUDIO);
            throw new AssertionError("mp4 as audio");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void eachKindReadsAndWritesItsOwnTable() {
        MemoryVideo video = new MemoryVideo();
        MemoryAudio audio = new MemoryAudio();
        UploadSessionStore v = UploadKind.VIDEO.store(video, audio);
        UploadSessionStore a = UploadKind.AUDIO.store(video, audio);
        assertSame(video, v);
        assertNotSame(video, a);

        v.put(row(7, "video-up"));
        assertNull(a.get(7));
        a.put(row(7, "audio-up"));
        assertEquals("video-up", v.get(7).uploadId);
        assertEquals("audio-up", a.get(7).uploadId);
        assertEquals("audio-up", audio.rows.get(7L).uploadId);

        a.setParts(7, "[1]");
        a.setStatus(7, UploadSession.COMPLETED);
        assertEquals("[1]", a.get(7).partsJson);
        assertEquals(UploadSession.COMPLETED, a.get(7).status);
        assertEquals(UploadSession.PENDING, v.get(7).status);

        a.delete(7);
        assertNull(a.get(7));
        assertEquals("video-up", v.get(7).uploadId);
    }

    @Test
    public void audioExtractorBuildsAMono16kPcmCommandCappedAtAnHour() {
        String[] c = AudioExtractor.command(new File("/a/in.mp4"), new File("/c/out.wav"));
        String joined = String.join(" ", c);
        assertTrue(joined.contains("-vn -ac 1 -ar 16000 -c:a pcm_s16le -t 3600 /c/out.wav"));
        assertEquals("/a/in.mp4", c[2]);
        assertEquals(new File(new File("/c", "ai_audio"), "job-9.wav"),
                AudioExtractor.outputFor(new File("/c"), 9));
    }

    private static UploadSession row(long jobId, String uploadId) {
        UploadSession s = new UploadSession();
        s.jobId = jobId;
        s.uploadId = uploadId;
        s.size = 10;
        s.partSize = 10;
        s.partCount = 1;
        s.partsJson = "[]";
        s.status = UploadSession.PENDING;
        return s;
    }

    private static final class MemoryVideo implements UploadSessionDao {
        final Map<Long, UploadSession> rows = new HashMap<>();

        @Override public UploadSession get(long jobId) { return rows.get(jobId); }
        @Override public void put(UploadSession s) { rows.put(s.jobId, s); }
        @Override public void setParts(long jobId, String p) { rows.get(jobId).partsJson = p; }
        @Override public void setStatus(long jobId, String st) { rows.get(jobId).status = st; }
        @Override public void delete(long jobId) { rows.remove(jobId); }
    }

    private static final class MemoryAudio implements AudioUploadSessionDao {
        final Map<Long, AudioUploadSession> rows = new HashMap<>();

        @Override public AudioUploadSession get(long jobId) { return rows.get(jobId); }
        @Override public void put(AudioUploadSession s) { rows.put(s.jobId, s); }
        @Override public void setParts(long jobId, String p) { rows.get(jobId).partsJson = p; }
        @Override public void setStatus(long jobId, String st) { rows.get(jobId).status = st; }
        @Override public void delete(long jobId) { rows.remove(jobId); }
    }
}
