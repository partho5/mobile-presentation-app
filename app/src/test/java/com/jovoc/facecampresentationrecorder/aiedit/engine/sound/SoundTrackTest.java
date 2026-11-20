package com.jovoc.facecampresentationrecorder.aiedit.engine.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jovoc.facecampresentationrecorder.aiedit.engine.overlay.AssetFiles;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanAsset;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanCaptions;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** WAV reading and the sound mix: where a cue lands, its level, and what is left out. */
public class SoundTrackTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A 16-bit PCM WAV of {@code samples} (interleaved). */
    private static byte[] wav(int rate, int channels, short[] samples) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int dataLen = samples.length * 2;
        o.write("RIFF".getBytes("US-ASCII"));
        le32(o, 36 + dataLen);
        o.write("WAVEfmt ".getBytes("US-ASCII"));
        le32(o, 16);
        le16(o, 1);
        le16(o, channels);
        le32(o, rate);
        le32(o, rate * channels * 2);
        le16(o, channels * 2);
        le16(o, 16);
        o.write("data".getBytes("US-ASCII"));
        le32(o, dataLen);
        for (short s : samples) le16(o, s);
        return o.toByteArray();
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        le16(o, v & 0xFFFF);
        le16(o, (v >>> 16) & 0xFFFF);
    }

    private static short[] constant(int frames, short value) {
        short[] s = new short[frames];
        Arrays.fill(s, value);
        return s;
    }

    private File write(String id, byte[] bytes) throws IOException {
        File dir = tmp.getRoot();
        File f = AssetFiles.fileFor(dir, id);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return dir;
    }

    private static PlanItem soundItem(String id, long start, String soundId, long offset) {
        return new PlanItem(id, "x_pop", "text", "overlay", start, start + 1000, -1, 0, null, null,
                new HashMap<String, Object>(), soundId, offset, null);
    }

    private static Plan plan(List<PlanItem> items, List<PlanAsset> assets) {
        return new Plan(1, 1, "s", "standard", 10_000, Collections.<Plan.Cut>emptyList(),
                Collections.<Plan.SpeedSegment>emptyList(), Collections.<Plan.FacecamKey>emptyList(),
                new Palette("multi", Arrays.asList("#FF3B30")), items, new PlanCaptions(null, Collections.<Integer>emptyList()),
                Collections.<String>emptyList(), assets);
    }

    private static PlanAsset sound(String id) {
        return new PlanAsset(id, "sound", "https://example.test/" + id, 0, 0, 0);
    }

    @Test
    public void readsPcm16MonoAndStereo() throws Exception {
        WavClip mono = WavClip.parse(wav(8000, 1, new short[]{0, 16384, -32768}), wav(8000, 1, new short[3]).length);
        assertNotNull(mono);
        assertEquals(8000, mono.rate);
        assertEquals(1, mono.channels);
        assertEquals(3, mono.frames);
        assertEquals(0.5f, mono.sample(1, 0, 2), 1e-4f);
        assertEquals(-1f, mono.sample(2, 1, 2), 1e-4f);

        byte[] st = wav(8000, 2, new short[]{16384, -16384, 0, 0});
        WavClip stereo = WavClip.parse(st, st.length);
        assertEquals(2, stereo.channels);
        assertEquals(0.5f, stereo.sample(0, 0, 2), 1e-4f);
        assertEquals(-0.5f, stereo.sample(0, 1, 2), 1e-4f);
        assertEquals(0f, stereo.sample(0, 0, 1), 1e-4f);
    }

    @Test
    public void refusesWhatIsNotAReadableWav() throws Exception {
        assertNull(WavClip.parse(new byte[64], 64));
        byte[] bad = wav(8000, 1, new short[10]);
        bad[0] = 'X';
        assertNull(WavClip.parse(bad, bad.length));
        assertNull(WavClip.read(new File(tmp.getRoot(), "missing.wav")));
        assertNull(WavClip.read(null));
    }

    @Test
    public void cutsAClipLongerThanTheLimit() throws Exception {
        byte[] long6s = wav(8000, 1, new short[8000 * 6]);
        WavClip clip = WavClip.parse(long6s, long6s.length);
        assertEquals(8000 * WavClip.MAX_MS / 1000, clip.frames);
    }

    @Test
    public void aCueIsMixedAtItsOutputTimeUnderTheVoice() throws Exception {
        File dir = write("pop_1", wav(8000, 1, constant(800, (short) 32767)));
        Plan p = plan(Arrays.asList(soundItem("i1", 200, "pop_1", 0)), Arrays.asList(sound("pop_1")));
        SoundTrack track = SoundTrack.fromPlan(p, dir);
        assertNotNull(track);
        assertEquals(1, track.cueCount());

        // 8 kHz output, mono: the 100 ms cue covers output frames 1600..2399
        float[] acc = new float[3200];
        acc[2000] = 0.1f; // the voice
        track.mixInto(acc, 0, 3200, 8000, 1);
        assertEquals(0f, acc[1599], 1e-6f);
        assertEquals(0f, acc[2400], 1e-6f);
        assertEquals(SoundTrack.GAIN + 0.1f, acc[2000], 1e-3f);
        assertTrue(acc[2000] > 0.1f);
        // The first frame is faded in, not a hard edge
        assertTrue(acc[1600] < SoundTrack.GAIN * 0.8f);
    }

    @Test
    public void mixingInChunksGivesTheSameSoundAsOneBlock() throws Exception {
        File dir = write("pop_1", wav(8000, 1, constant(800, (short) 20000)));
        Plan p = plan(Arrays.asList(soundItem("i1", 40, "pop_1", 0)), Arrays.asList(sound("pop_1")));
        SoundTrack track = SoundTrack.fromPlan(p, dir);
        float[] whole = new float[2400];
        track.mixInto(whole, 0, 2400, 8000, 1);
        float[] parts = new float[2400];
        float[] chunk = new float[512];
        for (int first = 0; first < 2400; first += 512) {
            int n = Math.min(512, 2400 - first);
            Arrays.fill(chunk, 0f);
            track.mixInto(chunk, first, n, 8000, 1);
            System.arraycopy(chunk, 0, parts, first, n);
        }
        for (int i = 0; i < 2400; i++) assertEquals(whole[i], parts[i], 1e-6f);
    }

    @Test
    public void anOffsetMovesTheCueAndMonoPlaysOnBothChannels() throws Exception {
        File dir = write("click", wav(8000, 1, constant(400, (short) 32767)));
        Plan p = plan(Arrays.asList(soundItem("i1", 100, "click", 50)), Arrays.asList(sound("click")));
        SoundTrack track = SoundTrack.fromPlan(p, dir);
        // 8 kHz stereo: the 50 ms click starts at 150 ms, output frame 1200
        float[] acc = new float[2 * 2400];
        track.mixInto(acc, 0, 2400, 8000, 2);
        assertEquals(0f, acc[2 * 1199], 1e-6f);
        assertTrue(acc[2 * 1360] > 0.3f);
        assertEquals(acc[2 * 1360], acc[2 * 1360 + 1], 1e-6f);
        assertEquals(0f, acc[2 * 1600], 1e-6f);
    }

    @Test
    public void soundsWithoutAFileAnAssetOrAFolderAreLeftOut() throws Exception {
        File dir = write("pop_1", wav(8000, 1, constant(800, (short) 1000)));
        List<PlanItem> items = new ArrayList<>();
        items.add(soundItem("i1", 0, "missing", 0));
        items.add(soundItem("i2", 0, "pop_1", 0));
        // pop_1 has a file but the plan does not list it as an asset
        assertNull(SoundTrack.fromPlan(plan(items, Arrays.asList(sound("missing"))), dir));
        assertNull(SoundTrack.fromPlan(plan(items, Arrays.asList(sound("pop_1"))), null));
        SoundTrack ok = SoundTrack.fromPlan(plan(items, Arrays.asList(sound("missing"), sound("pop_1"))), dir);
        assertEquals(1, ok.cueCount());
    }
}
