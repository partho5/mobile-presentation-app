package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** The speech WAV of a job: made once, whole or not at all, reused when made ahead of the upload. */
public class AudioExtractorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File cache;
    private File video;
    private int runs;
    private long audioSeconds = 60;
    private int wavBytes = 2_000;

    private final AudioExtractor.Engine engine = new AudioExtractor.Engine() {
        @Override
        public long audioSeconds(File v) {
            return audioSeconds;
        }

        @Override
        public boolean run(File in, File out) {
            runs++;
            if (wavBytes < 0) return false;
            write(out, wavBytes);
            return true;
        }
    };

    @Before
    public void setUp() throws IOException {
        cache = tmp.newFolder("cache");
        video = tmp.newFile("demo.mp4");
        write(video, 100);
    }

    private static void write(File f, int bytes) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private File extract(long now) {
        return AudioExtractor.extract(engine, cache, 7, video, now);
    }

    @Test
    public void theWavAppearsOnlyWhenWhole() {
        File wav = extract(System.currentTimeMillis());
        assertEquals(AudioExtractor.outputFor(cache, 7), wav);
        assertEquals(2_000, wav.length());
        assertFalse(AudioExtractor.partFor(cache, 7).exists());
    }

    @Test
    public void noAudioTrackMeansNoWavAndNoFfmpeg() {
        audioSeconds = -1;
        assertNull(extract(System.currentTimeMillis()));
        assertEquals(0, runs);
    }

    @Test
    public void aFailedOrEmptyRunLeavesNothingBehind() {
        wavBytes = -1;
        assertNull(extract(System.currentTimeMillis()));
        wavBytes = 10; // just a header's worth: not a usable WAV
        assertNull(extract(System.currentTimeMillis()));
        assertFalse(AudioExtractor.outputFor(cache, 7).exists());
        assertFalse(AudioExtractor.partFor(cache, 7).exists());
    }

    @Test
    public void aWavMadeAheadIsReusedByTheWorker() {
        long now = System.currentTimeMillis();
        AudioExtractor.prepare(engine, cache, 7, video, () -> true, now);
        assertEquals(1, runs);
        File wav = extract(now);
        assertNotNull(wav);
        assertEquals(1, runs); // not made twice
    }

    @Test
    public void aWavMadeAheadForAnUndoneJobIsDeleted() {
        AudioExtractor.prepare(engine, cache, 7, video, () -> false, System.currentTimeMillis());
        assertEquals(1, runs);
        assertFalse(AudioExtractor.outputFor(cache, 7).exists());
    }

    @Test
    public void leftoversOlderThanADayArePurgedOnTheNextRun() {
        File old = new File(AudioExtractor.outputFor(cache, 3).getParentFile(), "job-3.wav");
        assertTrue(old.getParentFile().mkdirs());
        write(old, 500);
        long now = System.currentTimeMillis();
        assertTrue(old.setLastModified(now - AudioExtractor.STALE_MS - 60_000L));
        File fresh = new File(old.getParentFile(), "job-4.wav");
        write(fresh, 500);

        extract(now);
        assertFalse(old.exists());
        assertTrue(fresh.exists());
    }

    @Test
    public void commandDropsVideoAndCapsTheLength() {
        List<String> cmd = Arrays.asList(AudioExtractor.command(new File("in.mp4"), new File("out.wav")));
        assertTrue(cmd.contains("-vn"));
        assertTrue(cmd.contains("pcm_s16le"));
        assertEquals(String.valueOf(AudioExtractor.MAX_SECONDS), cmd.get(cmd.indexOf("-t") + 1));
    }
}
