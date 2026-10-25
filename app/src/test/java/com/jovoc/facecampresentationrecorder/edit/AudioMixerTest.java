package com.jovoc.facecampresentationrecorder.edit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioMixerTest {

    @Test
    public void gainIsZeroWhenMutedAndClamped() {
        assertEquals(0f, AudioMixer.gainFor(1.5f, true), 0f);
        assertEquals(1.5f, AudioMixer.gainFor(1.5f, false), 0f);
        assertEquals(2f, AudioMixer.gainFor(5f, false), 0f);
        assertEquals(0f, AudioMixer.gainFor(-1f, false), 0f);
    }

    @Test
    public void accumulateScalesAndSums() {
        float[] acc = new float[4];
        short[] a = {100, -100, 200, -200};
        short[] b = {10, 10, 10, 10};
        AudioMixer.accumulate(acc, 0, a, 0, 2, 2, 0.5f);
        AudioMixer.accumulate(acc, 1, b, 0, 1, 2, 2f);
        assertArrayEquals(new float[]{50, -50, 120, -80}, acc, 0.001f);
    }

    @Test
    public void accumulateWithZeroGainChangesNothing() {
        float[] acc = new float[2];
        AudioMixer.accumulate(acc, 0, new short[]{1000, 1000}, 0, 2, 1, 0f);
        assertArrayEquals(new float[]{0, 0}, acc, 0f);
    }

    @Test
    public void quietSamplesPassTheLimiterUnchanged() {
        assertEquals(1000f, AudioMixer.limit(1000f), 0f);
        assertEquals(-20000f, AudioMixer.limit(-20000f), 0f);
    }

    @Test
    public void loudSumIsBentNotWrapped() {
        float limited = AudioMixer.limit(60000f);
        assertTrue(limited > 26000f);
        assertTrue(limited <= 32767f);
        assertEquals(-limited, AudioMixer.limit(-60000f), 0.001f);
    }

    @Test
    public void limiterNeverDecreasesAndStaysInRange() {
        float last = -40000f;
        for (float x = -200000f; x <= 200000f; x += 997f) {
            float y = AudioMixer.limit(x);
            assertTrue(y >= last - 0.001f || x == -200000f);
            assertTrue(Math.abs(y) <= 32767f);
            last = y;
        }
    }

    @Test
    public void toPcm16ClipsAHugeSumWithoutOverflow() {
        short[] out = new short[3];
        AudioMixer.toPcm16(new float[]{1e9f, -1e9f, 5f}, 3, out);
        assertEquals(32767, out[0]);
        assertTrue(out[1] <= -32766);
        assertEquals(5, out[2]);
    }

    @Test
    public void twoLoudTracksDoNotWrap() {
        float[] acc = new float[1];
        AudioMixer.accumulate(acc, 0, new short[]{30000}, 0, 1, 1, 1f);
        AudioMixer.accumulate(acc, 0, new short[]{30000}, 0, 1, 1, 1f);
        short[] out = new short[1];
        AudioMixer.toPcm16(acc, 1, out);
        assertTrue(out[0] > 30000);
    }

    @Test
    public void monoToStereoCopies() {
        short[] out = AudioMixer.convertChannels(new short[]{1, 2, 3}, 3, 1, 2);
        assertArrayEquals(new short[]{1, 1, 2, 2, 3, 3}, out);
    }

    @Test
    public void stereoToMonoAverages() {
        short[] out = AudioMixer.convertChannels(new short[]{100, 300, -50, -150}, 2, 2, 1);
        assertArrayEquals(new short[]{200, -100}, out);
    }

    @Test
    public void sameChannelsIsACopy() {
        short[] in = {1, 2, 3, 4};
        short[] out = AudioMixer.convertChannels(in, 2, 2, 2);
        assertArrayEquals(in, out);
    }

    @Test
    public void placementOffsetCases() {
        assertEquals(0, AudioMixer.placementOffset(1000, 100, 500));
        assertEquals(0, AudioMixer.placementOffset(1000, 100, -300));
        assertEquals(0, AudioMixer.placementOffset(1000, 100, 1000));
        assertEquals(40, AudioMixer.placementOffset(1000, 100, 1040));
        assertEquals(-1, AudioMixer.placementOffset(1000, 100, 1100));
        assertEquals(-1, AudioMixer.placementOffset(1000, 100, 5000));
    }

    @Test
    public void framesForMsRoundsDown() {
        assertEquals(44100, AudioMixer.framesForMs(1000, 44100));
        assertEquals(44, AudioMixer.framesForMs(1, 44100));
        assertEquals(0, AudioMixer.framesForMs(0, 48000));
    }

    @Test
    public void resamplerSameRateKeepsEverySampleInOrder() {
        AudioMixer.Resampler r = new AudioMixer.Resampler(44100, 44100, 1);
        short[] in1 = {1, 2, 3, 4};
        short[] in2 = {5, 6, 7, 8};
        short[] out1 = new short[8];
        short[] out2 = new short[8];
        int n1 = r.process(in1, 4, out1);
        int n2 = r.process(in2, 4, out2);
        // All but the last frame has been emitted; it follows with the next chunk
        short[] got = new short[n1 + n2];
        System.arraycopy(out1, 0, got, 0, n1);
        System.arraycopy(out2, 0, got, n1, n2);
        assertArrayEquals(new short[]{1, 2, 3, 4, 5, 6, 7}, got);
    }

    @Test
    public void resamplerDoublesTheFrameCountWhenRateDoubles() {
        AudioMixer.Resampler r = new AudioMixer.Resampler(22050, 44100, 1);
        short[] in = new short[100];
        for (int i = 0; i < 100; i++) in[i] = (short) (i * 10);
        short[] out = new short[r.maxOutputFrames(100)];
        int n = r.process(in, 100, out);
        assertTrue("got " + n, n >= 196 && n <= 200);
        assertEquals(0, out[0]);
        assertEquals(5, out[1]);
        assertEquals(10, out[2]);
    }

    @Test
    public void resamplerHalvesTheFrameCountWhenRateHalves() {
        AudioMixer.Resampler r = new AudioMixer.Resampler(48000, 24000, 2);
        short[] in = new short[2 * 100];
        short[] out = new short[2 * r.maxOutputFrames(100)];
        int n = r.process(in, 100, out);
        assertTrue("got " + n, n >= 48 && n <= 51);
    }

    @Test
    public void aacRateKeepsSupportedRatesAndSnapsOddOnes() {
        assertEquals(44100, AudioMixer.aacRateFor(44100));
        assertEquals(8000, AudioMixer.aacRateFor(8000));
        assertEquals(16000, AudioMixer.aacRateFor(16000));
        assertEquals(48000, AudioMixer.aacRateFor(96000));
        assertEquals(22050, AudioMixer.aacRateFor(22000));
        assertEquals(8000, AudioMixer.aacRateFor(4000));
        assertEquals(44100, AudioMixer.aacRateFor(0));
    }
}
